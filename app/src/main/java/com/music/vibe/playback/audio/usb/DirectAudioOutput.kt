/*
 * Copyright (C) 2026 Vibe Music Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package com.music.vibe.playback.audio.usb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import com.music.vibe.playback.audio.PcmEncoding
import java.nio.ByteBuffer

/**
 * Isolated abstraction for direct USB hardware audio streaming.
 *
 * Designed to bypass system audio mixers when an authorized, compatible USB Audio Class (UAC 1.0 / 2.0)
 * DAC is active. When direct streaming is inactive, unavailable, or unpermitted, Vibe Music
 * cleanly falls back to [com.music.vibe.playback.audio.PrecisionAudioSink] and AudioTrack.
 */
interface DirectAudioOutput {

    /** Whether direct hardware streaming is active and ready to accept audio frames. */
    val isActive: Boolean

    /** Current hardware probe diagnosis. */
    val probeResult: DirectUsbProbeResult

    /**
     * Attempts to configure the direct USB output stream for the given audio format.
     *
     * @return true if direct stream was successfully opened, false if fallback is required.
     */
    fun configure(
        sampleRate: Int,
        channelCount: Int,
        encoding: PcmEncoding,
    ): Boolean

    /**
     * Writes processed PCM samples directly to the USB endpoint.
     *
     * @param buffer Direct [ByteBuffer] containing audio frames.
     * @return Number of bytes accepted by the hardware endpoint, or -1 on error.
     */
    fun write(buffer: ByteBuffer): Int

    /** Flushes any buffered packets in the direct USB pipeline. */
    fun flush()

    /** Releases the USB interface and device connection. */
    fun release()
}

/**
 * Custom userspace USB Audio Class (UAC 1.0 / UAC 2.0) driver implementation.
 *
 * Claims the USB AudioStreaming interface directly via [UsbManager.openDevice] and
 * [UsbDeviceConnection.claimInterface] (`force = true` to detach the kernel `snd-usb-audio` driver
 * when permitted), configures the UAC sample frequency via USB control transfer, and writes
 * packetized PCM frames to the USB OUT endpoint.
 */
class DefaultDirectAudioOutput(
    private val context: Context? = null,
    override var probeResult: DirectUsbProbeResult = DirectUsbProbeResult.notPresent(),
) : DirectAudioOutput {

    private var connection: UsbDeviceConnection? = null
    private var claimedInterface: UsbInterface? = null
    private var outEndpoint: UsbEndpoint? = null
    private var transferScratch = ByteArray(4096)

    @Volatile
    override var isActive: Boolean = false
        private set

    @Synchronized
    override fun configure(sampleRate: Int, channelCount: Int, encoding: PcmEncoding): Boolean {
        val ctx = context ?: return false
        release()

        val usbManager = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        val device = UsbDirectManager.findAudioUsbDevice(ctx) ?: return false
        probeResult = UsbDirectManager.probe(ctx)
        if (!usbManager.hasPermission(device)) {
            UsbDirectManager.requestPermissionIfNeeded(ctx)
            return false
        }

        // Locate the AudioStreaming interface with the widest OUT endpoint
        var bestIface: UsbInterface? = null
        var bestEndpoint: UsbEndpoint? = null
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO && iface.interfaceSubclass == 2) {
                for (e in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(e)
                    if (ep.direction == UsbConstants.USB_DIR_OUT &&
                        (bestEndpoint == null || ep.maxPacketSize > bestEndpoint.maxPacketSize)
                    ) {
                        bestEndpoint = ep
                        bestIface = iface
                    }
                }
            }
        }

        val targetIface = bestIface ?: return false
        val targetEndpoint = bestEndpoint ?: return false

        val conn = runCatching { usbManager.openDevice(device) }.getOrNull() ?: return false
        val claimed = runCatching { conn.claimInterface(targetIface, true) }.getOrDefault(false)
        if (!claimed) {
            runCatching { conn.close() }
            return false
        }

        runCatching { conn.setInterface(targetIface) }

        // Configure UAC 1.0 endpoint sample rate via class-specific SET_CUR (0x01) request
        // to endpoint address (bmRequestType = 0x22, bRequest = 0x01, wValue = 0x0100SamplingFreq)
        val rateBytes = byteArrayOf(
            (sampleRate and 0xFF).toByte(),
            ((sampleRate shr 8) and 0xFF).toByte(),
            ((sampleRate shr 16) and 0xFF).toByte(),
        )
        runCatching {
            conn.controlTransfer(
                0x22, // Host-to-device | Class | Endpoint
                0x01, // SET_CUR
                0x0100, // Sampling Frequency Control
                targetEndpoint.address,
                rateBytes,
                rateBytes.size,
                200,
            )
        }

        connection = conn
        claimedInterface = targetIface
        outEndpoint = targetEndpoint
        isActive = true
        probeResult = probeResult.copy(isInterfaceClaimed = true)
        Log.i(TAG, "Opened custom USB Audio driver for ${device.productName} @ $sampleRate Hz")
        return true
    }

    @Synchronized
    override fun write(buffer: ByteBuffer): Int {
        val conn = connection ?: return -1
        val ep = outEndpoint ?: return -1
        if (!isActive) return -1

        val remaining = buffer.remaining()
        if (remaining <= 0) return 0

        if (transferScratch.size < remaining) {
            transferScratch = ByteArray(remaining.coerceAtLeast(4096))
        }
        val pos = buffer.position()
        buffer.get(transferScratch, 0, remaining)

        // Packetize writes in maxPacketSize chunks
        val packetSize = ep.maxPacketSize.coerceAtLeast(192)
        var offset = 0
        while (offset < remaining) {
            val chunk = minOf(packetSize, remaining - offset)
            val written = conn.bulkTransfer(ep, transferScratch, offset, chunk, 50)
            if (written < 0) {
                // Endpoint requires kernel isochronous URB scheduling; rewind and signal fallback
                buffer.position(pos + offset)
                return if (offset > 0) offset else -1
            }
            offset += chunk
        }
        return offset
    }

    @Synchronized
    override fun flush() {
        // Stateless packet queue; nothing to flush in userspace buffer
    }

    @Synchronized
    override fun release() {
        isActive = false
        val conn = connection
        val iface = claimedInterface
        connection = null
        claimedInterface = null
        outEndpoint = null
        if (conn != null) {
            if (iface != null) {
                runCatching { conn.releaseInterface(iface) }
            }
            runCatching { conn.close() }
        }
    }

    companion object {
        private const val TAG = "DirectAudioOutput"
    }
}
