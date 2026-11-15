/*
 * Copyright (C) 2026 Vibe Music Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Architectural concepts for USB descriptor parsing adapted from:
 * decent-player (https://github.com/Ma145/decent-player)
 * Copyright (c) 2026 Ma145 (MIT License)
 */

package com.music.vibe.playback.audio.usb

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log

/**
 * Probes, audits, and manages permissions for USB Audio Class (UAC 1.0 / UAC 2.0) hardware
 * attached to the device.
 */
object UsbDirectManager {

    private const val TAG = "UsbDirectManager"
    const val ACTION_USB_PERMISSION = "com.music.vibe.USB_PERMISSION"

    // Standard UAC Protocol constants
    private const val UAC_VERSION_1_PROTOCOL = 0x00
    private const val UAC_VERSION_2_PROTOCOL = 0x20
    private const val UAC_VERSION_3_PROTOCOL = 0x30

    /**
     * Dynamically probes all attached USB devices for audio streaming capabilities.
     */
    fun probe(context: Context): DirectUsbProbeResult {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return DirectUsbProbeResult.notPresent("UsbManager system service unavailable")

        val deviceList = try {
            usbManager.deviceList
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read USB device list", e)
            return DirectUsbProbeResult.notPresent("Permission or OS error reading USB device list: ${e.message}")
        }

        if (deviceList.isEmpty()) {
            return DirectUsbProbeResult.notPresent("No USB devices connected")
        }

        for (device in deviceList.values) {
            val audioInfo = auditDevice(device, usbManager)
            if (audioInfo != null) {
                return audioInfo
            }
        }

        return DirectUsbProbeResult.notPresent("No USB Audio Class device found among ${deviceList.size} connected device(s)")
    }

    /**
     * Finds the first connected USB Audio Class [UsbDevice], if any.
     */
    fun findAudioUsbDevice(context: Context): UsbDevice? {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return null
        val devices = runCatching { usbManager.deviceList.values }.getOrNull() ?: return null
        return devices.firstOrNull { device ->
            (0 until device.interfaceCount).any { idx ->
                device.getInterface(idx).interfaceClass == UsbConstants.USB_CLASS_AUDIO
            }
        }
    }

    /**
     * Requests Android USB host permission for the connected USB DAC if not yet granted.
     */
    fun requestPermissionIfNeeded(context: Context): Boolean {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        val device = findAudioUsbDevice(context) ?: return false
        if (usbManager.hasPermission(device)) return true

        return try {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
            val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, flags)
            usbManager.requestPermission(device, pendingIntent)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to request USB permission: ${t.message}")
            false
        }
    }

    private fun auditDevice(device: UsbDevice, usbManager: UsbManager): DirectUsbProbeResult? {
        var hasAudioInterface = false
        var streamingInterface: UsbInterface? = null
        var uacVersion = 0
        var altSettingCount = 0
        var isoOutEndpoint: UsbEndpoint? = null

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO) {
                hasAudioInterface = true

                // Subclass 1 = AudioControl, Subclass 2 = AudioStreaming
                if (iface.interfaceSubclass == 1) {
                    when (iface.interfaceProtocol) {
                        UAC_VERSION_2_PROTOCOL -> uacVersion = maxOf(uacVersion, 2)
                        UAC_VERSION_3_PROTOCOL -> uacVersion = maxOf(uacVersion, 3)
                        UAC_VERSION_1_PROTOCOL -> if (uacVersion == 0) uacVersion = 1
                    }
                } else if (iface.interfaceSubclass == 2) {
                    altSettingCount++
                    when (iface.interfaceProtocol) {
                        UAC_VERSION_2_PROTOCOL -> uacVersion = maxOf(uacVersion, 2)
                        UAC_VERSION_3_PROTOCOL -> uacVersion = maxOf(uacVersion, 3)
                        UAC_VERSION_1_PROTOCOL -> if (uacVersion == 0) uacVersion = 1
                    }

                    for (e in 0 until iface.endpointCount) {
                        val ep = iface.getEndpoint(e)
                        if (ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC &&
                            ep.direction == UsbConstants.USB_DIR_OUT
                        ) {
                            if (isoOutEndpoint == null || ep.maxPacketSize > isoOutEndpoint.maxPacketSize) {
                                isoOutEndpoint = ep
                                streamingInterface = iface
                            }
                        }
                    }
                }
            }
        }

        if (!hasAudioInterface) return null

        val productName = device.productName ?: "USB Audio Device"
        val vendorId = device.vendorId
        val productId = device.productId

        if (isoOutEndpoint == null) {
            return DirectUsbProbeResult(
                isViable = false,
                productName = productName,
                vendorId = vendorId,
                vendorIdHex = "0x%04X".format(vendorId),
                productId = productId,
                productIdHex = "0x%04X".format(productId),
                uacVersion = uacVersion,
                hasPermission = usbManager.hasPermission(device),
                diagnosticReason = "AudioStreaming interface found, but no isochronous OUT endpoint is exposed",
            )
        }

        val maxPacketSize = isoOutEndpoint.maxPacketSize
        val hasPermission = usbManager.hasPermission(device)

        val supportedDepths = calculateSupportedBitDepths(maxPacketSize)
        val maxCalculatedSr = calculateMaxSampleRate(maxPacketSize)
        val (isViable, diagnosticReason) = evaluateViability(productName, uacVersion, maxPacketSize, hasPermission)

        return DirectUsbProbeResult(
            isViable = isViable,
            productName = productName,
            vendorId = vendorId,
            vendorIdHex = "0x%04X".format(vendorId),
            productId = productId,
            productIdHex = "0x%04X".format(productId),
            uacVersion = uacVersion,
            hasPermission = hasPermission,
            endpointOutAddress = isoOutEndpoint.address,
            maxPacketSize = maxPacketSize,
            altSettingCount = altSettingCount,
            isInterfaceClaimed = false,
            supportedBitDepths = supportedDepths,
            maxCalculatedSampleRate = maxCalculatedSr,
            diagnosticReason = diagnosticReason,
        )
    }

    internal fun calculateSupportedBitDepths(maxPacketSize: Int): List<Int> {
        val depths = mutableListOf<Int>()
        if (maxPacketSize >= 176) depths.add(16)
        if (maxPacketSize >= 288) depths.add(24)
        if (maxPacketSize >= 384) depths.add(32)
        return depths
    }

    internal fun calculateMaxSampleRate(maxPacketSize: Int): Int = when {
        maxPacketSize >= 1536 -> 192000
        maxPacketSize >= 768 -> 96000
        maxPacketSize >= 384 -> 96000
        maxPacketSize >= 192 -> 48000
        else -> 44100
    }

    internal fun evaluateViability(
        productName: String,
        uacVersion: Int,
        maxPacketSize: Int,
        hasPermission: Boolean,
    ): Pair<Boolean, String> {
        // Support both UAC 1.0 and UAC 2.0 when USB permission is granted
        val isViable = hasPermission && uacVersion >= 1 && maxPacketSize >= 176
        val reason = buildString {
            if (!hasPermission) {
                append("Direct USB requires USB host permission for '$productName'")
            } else if (uacVersion < 1) {
                append("Non-standard USB Audio interface (max packet $maxPacketSize bytes)")
            } else {
                append("UAC $uacVersion.0 device ready for direct USB streaming (max packet $maxPacketSize bytes)")
            }
        }
        return Pair(isViable, reason)
    }
}
