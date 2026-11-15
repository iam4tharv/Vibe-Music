package com.music.vibe.playback.audio

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.music.vibe.BuildConfig
import com.music.vibe.playback.AudioOutputStatus
import com.music.vibe.playback.audio.usb.DirectAudioOutput
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Precision audio sink intercepting decoder PCM buffers before Media3's internal
 * processing pipeline can bypass Vibe Music's custom DSP and resampling chain.
 *
 * Architecture:
 * - In Precision Mode (for all supported linear PCM sources):
 *   decoder PCM -> PcmBoundary.decode -> AudioBlock (Float32)
 *   -> HighPrecisionResampler (when matching Bluetooth/hardware native rate)
 *   -> DspChain (Spatial -> EQ -> Transition -> Loudness, bypassed in Bit-Perfect mode)
 *   -> PcmBoundary.encode -> delegate [DefaultAudioSink] (or [DirectAudioOutput]).
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PrecisionAudioSink(
    private val delegate: AudioSink,
    val dspChain: DspChain,
    private val enableFloatOutput: Boolean,
    val directAudioOutput: DirectAudioOutput? = null,
    private val preferredOutputEncodingProvider: ((Format) -> PcmEncoding?)? = null,
    private val targetSampleRateProvider: ((Format) -> Int)? = null,
    private val bitPerfectModeProvider: () -> Boolean = { false },
    private val usbDirectModeProvider: () -> Boolean = { false },
    private val isAudible: () -> Boolean = { true },
) : ForwardingAudioSink(delegate) {

    /** Whether the precision Float32 DSP path is currently active for the configured format. */
    var isPrecisionActive: Boolean = false
        private set

    /** The PCM encoding format of the incoming decoder buffers when precision mode is active. */
    var inputPcmEncoding: PcmEncoding? = null
        private set

    /** The target PCM encoding format written to [delegate] when precision mode is active. */
    var targetOutputEncoding: PcmEncoding? = null
        private set

    /** The format passed to the most recent [configure] invocation. */
    var activeFormat: Format? = null
        private set

    private var processCounter: Long = 0L

    /** Sample rate of the incoming source format. */
    private var configuredSampleRate: Int = 0

    /** Sample rate written to the output sink (may differ when in-app resampling is active). */
    private var configuredOutputSampleRate: Int = 0

    private val resampler = HighPrecisionResampler()

    private var audioBlock: AudioBlock = AudioBlock(
        channelCount = AudioBlock.DEFAULT_CHANNELS,
        capacityFrames = DEFAULT_CAPACITY_FRAMES,
    )

    private var resampledBlock: AudioBlock = AudioBlock(
        channelCount = AudioBlock.DEFAULT_CHANNELS,
        capacityFrames = DEFAULT_CAPACITY_FRAMES * 4,
    )

    private var outputByteBuffer: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())

    private var pendingPresentationTimeUs: Long = C.TIME_UNSET
    private var pendingAccessUnitCount: Int = 0

    private var timestampedInputTimeUs: Long = C.TIME_UNSET
    private var outputFramesEmittedForInput: Long = 0L

    private var streaming = false

    private var processedEndUs: Long = C.TIME_UNSET

    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        val format = audioSinkConfig.format
        activeFormat = format

        if (shouldActivatePrecision(format)) {
            val encoding = mapToPcmEncoding(format.pcmEncoding)
            if (encoding != null) {
                val sampleRate = format.sampleRate
                val channelCount = format.channelCount
                val desiredOutRate = targetSampleRateProvider?.invoke(format)?.takeIf { it in 8000..384000 }
                    ?: sampleRate

                // 1. Configure the in-app HighPrecisionResampler & Float32 DSP chain
                resampler.configure(sampleRate, desiredOutRate, channelCount)
                dspChain.configure(desiredOutRate, channelCount)

                // 2. Ensure internal buffers are sized for max frames, resample ratio, and channel count
                val maxRatio = ((desiredOutRate.toDouble() / sampleRate.toDouble()).toInt() + 2).coerceIn(1, 8)
                ensureBuffers(channelCount, maxRatio)
                configuredSampleRate = sampleRate
                configuredOutputSampleRate = desiredOutRate

                // 3. Determine target output encoding
                val floatFormat = format.buildUpon()
                    .setSampleRate(desiredOutRate)
                    .setPcmEncoding(C.ENCODING_PCM_FLOAT)
                    .build()

                val targetEncoding = resolveTargetEncoding(
                    preferred = preferredOutputEncodingProvider?.invoke(format),
                    floatFormat = floatFormat,
                )

                // Configure or release userspace USB driver based on active transport preference
                if (usbDirectModeProvider()) {
                    directAudioOutput?.configure(desiredOutRate, channelCount, targetEncoding)
                } else {
                    directAudioOutput?.release()
                }

                // 4. Configure delegate DefaultAudioSink at desiredOutRate
                val delegateFormat = format.buildUpon()
                    .setSampleRate(desiredOutRate)
                    .setPcmEncoding(targetEncoding.toMedia3PcmEncoding())
                    .build()

                val delegateConfig = AudioSink.AudioSinkConfig.Builder(delegateFormat)
                    .setPreferredBufferSizeOverride(audioSinkConfig.preferredBufferSizeOverride)
                    .setOutputChannelMapping(audioSinkConfig.outputChannelMapping)
                    .setTimeline(audioSinkConfig.timeline)
                    .setMediaPeriodId(audioSinkConfig.mediaPeriodId)
                    .build()

                try {
                    delegate.configure(delegateConfig)
                    inputPcmEncoding = encoding
                    targetOutputEncoding = targetEncoding
                    isPrecisionActive = true
                    publishTelemetry(encoding, targetEncoding)
                    logConfig(
                        precisionActive = true,
                        inputEncoding = encoding.name,
                        sampleRate = sampleRate,
                        outputSampleRate = desiredOutRate,
                        channelCount = channelCount,
                        targetOutputEncoding = targetEncoding.name,
                        enableFloatOutput = enableFloatOutput,
                        delegateEncoding = delegateFormat.pcmEncoding,
                    )
                    return
                } catch (e: Exception) {
                    // If desiredOutRate or Float32 failed, retry at source sampleRate and/or PCM16
                    resampler.configure(sampleRate, sampleRate, channelCount)
                    dspChain.configure(sampleRate, channelCount)
                    configuredOutputSampleRate = sampleRate
                    val fallbackPcm16AtSource = format.buildUpon()
                        .setSampleRate(sampleRate)
                        .setPcmEncoding(C.ENCODING_PCM_16BIT)
                        .build()
                    try {
                        val fallbackPcm16Config = AudioSink.AudioSinkConfig.Builder(fallbackPcm16AtSource)
                            .setPreferredBufferSizeOverride(audioSinkConfig.preferredBufferSizeOverride)
                            .setOutputChannelMapping(audioSinkConfig.outputChannelMapping)
                            .setTimeline(audioSinkConfig.timeline)
                            .setMediaPeriodId(audioSinkConfig.mediaPeriodId)
                            .build()
                        delegate.configure(fallbackPcm16Config)
                        inputPcmEncoding = encoding
                        targetOutputEncoding = PcmEncoding.PCM_16BIT
                        isPrecisionActive = true
                        publishTelemetry(encoding, PcmEncoding.PCM_16BIT)
                        logConfig(
                            precisionActive = true,
                            inputEncoding = encoding.name,
                            sampleRate = sampleRate,
                            outputSampleRate = sampleRate,
                            channelCount = channelCount,
                            targetOutputEncoding = PcmEncoding.PCM_16BIT.name,
                            enableFloatOutput = enableFloatOutput,
                            delegateEncoding = fallbackPcm16AtSource.pcmEncoding,
                        )
                        return
                    } catch (_: Exception) {
                        // Delegate configuration failed completely; fall back safely
                    }
                    isPrecisionActive = false
                    inputPcmEncoding = null
                    targetOutputEncoding = null
                }
            }
        }

        // Fallback / legacy mode: forward configuration unchanged.
        isPrecisionActive = false
        inputPcmEncoding = null
        targetOutputEncoding = null
        configuredSampleRate = 0
        configuredOutputSampleRate = 0
        resampler.reset()
        directAudioOutput?.release()
        if (isAudible()) {
            AudioOutputStatus.publishOutputExactness(
                exact = false,
                detail = "${encodingLabel(format.pcmEncoding)} is not linear PCM",
            )
            AudioOutputStatus.publishDsp(
                decoderOutputEncoding = null,
                dspFormat = "Legacy PCM",
                dspAvailable = false,
            )
        }
        delegate.configure(audioSinkConfig)
        logConfig(
            precisionActive = false,
            inputEncoding = format.pcmEncoding.toString(),
            sampleRate = format.sampleRate,
            outputSampleRate = format.sampleRate,
            channelCount = format.channelCount,
            targetOutputEncoding = "NONE",
            enableFloatOutput = enableFloatOutput,
            delegateEncoding = format.pcmEncoding,
        )
    }

    override fun handleBuffer(
        inputBuffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        if (!isPrecisionActive) {
            return delegate.handleBuffer(inputBuffer, presentationTimeUs, encodedAccessUnitCount)
        }

        val inEncoding = inputPcmEncoding ?: return delegate.handleBuffer(
            inputBuffer,
            presentationTimeUs,
            encodedAccessUnitCount,
        )
        val outEncoding = targetOutputEncoding ?: return delegate.handleBuffer(
            inputBuffer,
            presentationTimeUs,
            encodedAccessUnitCount,
        )

        val channelCount = audioBlock.channelCount
        val bytesPerFrame = inEncoding.bytesPerFrame(channelCount)
        if (bytesPerFrame <= 0) return true
        streaming = true

        // When Bit-Perfect mode is on, bypass internal sample-altering DSP (EQ, Spatial, Loudness)
        val isBitPerfectMode = bitPerfectModeProvider()
        val bypassDsp = isBitPerfectMode || !dspChain.isAnyActive()
        val isResampling = resampler.isActive

        // Direct Bit-Exact Zero-Copy Passthrough when encodings and sample rates match and DSP is bypassed
        if (bypassDsp && !isResampling && inEncoding == outEncoding && !outputByteBuffer.hasRemaining()) {
            val framesInBuffer = inputBuffer.remaining() / bytesPerFrame
            if (usbDirectModeProvider() && directAudioOutput?.isActive == true) {
                val dup = inputBuffer.duplicate().order(inputBuffer.order())
                directAudioOutput.write(dup)
            }
            val consumed = delegate.handleBuffer(inputBuffer, presentationTimeUs, encodedAccessUnitCount)
            if (consumed && presentationTimeUs != C.TIME_UNSET && configuredSampleRate > 0 && framesInBuffer > 0) {
                processedEndUs = presentationTimeUs + Util.sampleCountToDurationUs(framesInBuffer.toLong(), configuredSampleRate)
            }
            return consumed
        }

        // 1. Drain pending output from previous cycle if delegate had backpressure
        if (outputByteBuffer.hasRemaining()) {
            val consumed = delegate.handleBuffer(
                outputByteBuffer,
                pendingPresentationTimeUs,
                pendingAccessUnitCount,
            )
            if (!consumed || outputByteBuffer.hasRemaining()) {
                return false
            }
        }

        // 2. Output buffer is drained; process available input in blocks.
        if (presentationTimeUs != timestampedInputTimeUs) {
            timestampedInputTimeUs = presentationTimeUs
            outputFramesEmittedForInput = 0L
        }
        val clockSampleRate = configuredOutputSampleRate.takeIf { it > 0 } ?: configuredSampleRate
        while (inputBuffer.remaining() >= bytesPerFrame) {
            val availableFrames = inputBuffer.remaining() / bytesPerFrame
            if (availableFrames <= 0) break

            val framesToRead = minOf(availableFrames, audioBlock.capacityFrames)
            val decodedFrames = PcmBoundary.decode(
                inputBuffer = inputBuffer,
                encoding = inEncoding,
                destinationBlock = audioBlock,
                maxFrames = framesToRead,
            )
            if (decodedFrames <= 0) break

            if (BuildConfig.DEBUG) {
                processCounter++
                if (processCounter == 1L || processCounter % 500L == 0L) {
                    try {
                        Log.d(
                            TAG,
                            "handleBuffer() #$processCounter precisionActive=$isPrecisionActive " +
                                "inEnc=${inEncoding.name} outEnc=${outEncoding.name} " +
                                "inSr=$configuredSampleRate outSr=$configuredOutputSampleRate frames=$decodedFrames",
                        )
                    } catch (_: Throwable) {
                    }
                }
            }

            // 2a. Apply in-app high-precision polyphase sinc resampling if output rate differs
            val blockForDsp = if (isResampling) {
                resampler.process(audioBlock, resampledBlock)
                resampledBlock
            } else {
                audioBlock
            }

            val emittedOutFrames = blockForDsp.frameCount
            if (emittedOutFrames <= 0) {
                continue
            }

            // 2b. Process normalized Float32 samples through custom DSP chain (unless Bit-Perfect mode is on)
            if (!bypassDsp) {
                dspChain.process(blockForDsp)
            }

            // 2c. Encode processed samples to target PCM output buffer (Float32 or PCM16)
            outputByteBuffer.clear()
            PcmBoundary.encode(
                sourceBlock = blockForDsp,
                encoding = outEncoding,
                outputBuffer = outputByteBuffer,
            )
            outputByteBuffer.flip()

            if (usbDirectModeProvider() && directAudioOutput?.isActive == true) {
                val dup = outputByteBuffer.duplicate().order(outputByteBuffer.order())
                directAudioOutput.write(dup)
            }

            val blockTimeUs = advanceTimestamp(presentationTimeUs, outputFramesEmittedForInput, clockSampleRate)
            if (blockTimeUs != C.TIME_UNSET && clockSampleRate > 0) {
                processedEndUs = blockTimeUs + Util.sampleCountToDurationUs(emittedOutFrames.toLong(), clockSampleRate)
            }
            val blockAccessUnits = if (outputFramesEmittedForInput == 0L) encodedAccessUnitCount else 0
            pendingPresentationTimeUs = blockTimeUs
            pendingAccessUnitCount = blockAccessUnits
            outputFramesEmittedForInput += emittedOutFrames

            val consumed = delegate.handleBuffer(
                outputByteBuffer,
                blockTimeUs,
                blockAccessUnits,
            )

            if (!consumed || outputByteBuffer.hasRemaining()) {
                return false
            }
        }

        // Drop any incomplete trailing frame bytes so we don't stall
        if (inputBuffer.remaining() in 1 until bytesPerFrame) {
            inputBuffer.position(inputBuffer.limit())
        }

        return !inputBuffer.hasRemaining() && !outputByteBuffer.hasRemaining()
    }

    private fun advanceTimestamp(presentationTimeUs: Long, frames: Long, sampleRate: Int): Long {
        if (frames <= 0L || sampleRate <= 0 || presentationTimeUs == C.TIME_UNSET) {
            return presentationTimeUs
        }
        return presentationTimeUs + Util.sampleCountToDurationUs(frames, sampleRate)
    }

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        if (streaming) dspChain.onStreamBoundary()
        super.setOutputStreamOffsetUs(outputStreamOffsetUs)
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val position = super.getCurrentPositionUs(sourceEnded)
        val processed = processedEndUs
        if (position != AudioSink.CURRENT_POSITION_NOT_SET && processed != C.TIME_UNSET) {
            dspChain.transition.leadUs = (processed - position).coerceAtLeast(0L)
        }
        return position
    }

    override fun flush() {
        processedEndUs = C.TIME_UNSET
        dspChain.transition.leadUs = 0L
        streaming = false
        outputByteBuffer.clear()
        outputByteBuffer.flip()
        audioBlock.clear()
        resampledBlock.clear()
        pendingPresentationTimeUs = C.TIME_UNSET
        pendingAccessUnitCount = 0
        timestampedInputTimeUs = C.TIME_UNSET
        outputFramesEmittedForInput = 0L
        if (isPrecisionActive) {
            resampler.flush()
            dspChain.flush()
        }
        directAudioOutput?.flush()
        delegate.flush()
    }

    override fun reset() {
        processedEndUs = C.TIME_UNSET
        dspChain.transition.leadUs = 0L
        streaming = false
        processCounter = 0L
        outputByteBuffer.clear()
        outputByteBuffer.flip()
        audioBlock.clear()
        resampledBlock.clear()
        pendingPresentationTimeUs = C.TIME_UNSET
        pendingAccessUnitCount = 0
        timestampedInputTimeUs = C.TIME_UNSET
        outputFramesEmittedForInput = 0L
        if (isPrecisionActive) {
            resampler.reset()
            dspChain.reset()
        }
        isPrecisionActive = false
        inputPcmEncoding = null
        targetOutputEncoding = null
        activeFormat = null
        configuredSampleRate = 0
        configuredOutputSampleRate = 0
        directAudioOutput?.release()
        if (isAudible()) {
            AudioOutputStatus.publishOutputExactness(exact = false, detail = null)
            AudioOutputStatus.publishResampler(
                sourceRateHz = 0,
                outputRateHz = 0,
                active = false,
                type = null,
                cutoffHz = null,
            )
            AudioOutputStatus.publishDsp(
                decoderOutputEncoding = null,
                dspFormat = "Float32",
                dspAvailable = true,
            )
        }
        delegate.reset()
    }

    override fun play() {
        super.play()
    }

    override fun pause() {
        super.pause()
    }

    override fun handleDiscontinuity() {
        delegate.handleDiscontinuity()
    }

    override fun playToEndOfStream() {
        if (isPrecisionActive && outputByteBuffer.hasRemaining()) {
            delegate.handleBuffer(outputByteBuffer, pendingPresentationTimeUs, pendingAccessUnitCount)
        }
        delegate.playToEndOfStream()
    }

    override fun isEnded(): Boolean {
        if (isPrecisionActive && outputByteBuffer.hasRemaining()) {
            return false
        }
        return delegate.isEnded()
    }

    override fun hasPendingData(): Boolean {
        if (isPrecisionActive && outputByteBuffer.hasRemaining()) {
            return true
        }
        return delegate.hasPendingData()
    }

    override fun supportsFormat(format: Format): Boolean =
        getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getFormatSupport(format: Format): Int {
        if (shouldActivatePrecision(format)) {
            val pcm16Format = format.buildUpon()
                .setPcmEncoding(C.ENCODING_PCM_16BIT)
                .build()
            val floatFormat = format.buildUpon()
                .setPcmEncoding(C.ENCODING_PCM_FLOAT)
                .build()
            val pcm16Playable =
                delegate.getFormatSupport(pcm16Format) != AudioSink.SINK_FORMAT_UNSUPPORTED
            val floatIsNative = floatIsNative(floatFormat)

            if (format.pcmEncoding == C.ENCODING_PCM_FLOAT) {
                return when {
                    floatIsNative -> AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
                    pcm16Playable -> AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING
                    else -> AudioSink.SINK_FORMAT_UNSUPPORTED
                }
            }
            if (floatIsNative || pcm16Playable) {
                return AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
            }
        }
        return delegate.getFormatSupport(format)
    }

    private fun floatIsNative(floatFormat: Format): Boolean =
        enableFloatOutput &&
            delegate.getFormatSupport(floatFormat) == AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY

    private fun resolveTargetEncoding(preferred: PcmEncoding?, floatFormat: Format): PcmEncoding {
        val wantsAbove16Bit = when (preferred) {
            PcmEncoding.PCM_16BIT -> return PcmEncoding.PCM_16BIT
            PcmEncoding.PCM_FLOAT, PcmEncoding.PCM_24BIT_PACKED, PcmEncoding.PCM_32BIT -> true
            null -> enableFloatOutput
        }
        return if (wantsAbove16Bit && floatIsNative(floatFormat)) {
            PcmEncoding.PCM_FLOAT
        } else {
            PcmEncoding.PCM_16BIT
        }
    }

    private fun shouldActivatePrecision(format: Format): Boolean {
        val sampleMimeType = format.sampleMimeType
        if (sampleMimeType != null && sampleMimeType != MimeTypes.AUDIO_RAW) return false
        if (!Util.isEncodingLinearPcm(format.pcmEncoding)) return false
        if (mapToPcmEncoding(format.pcmEncoding) == null) return false
        if (format.channelCount !in 1..MAX_CHANNELS) return false
        if (format.sampleRate <= 0) return false
        return true
    }

    private fun ensureBuffers(channelCount: Int, maxResampleRatio: Int) {
        if (audioBlock.channelCount != channelCount) {
            audioBlock = AudioBlock(channelCount = channelCount, capacityFrames = DEFAULT_CAPACITY_FRAMES)
        } else {
            audioBlock.reset(0)
        }

        val resampledCap = (DEFAULT_CAPACITY_FRAMES * maxResampleRatio).coerceAtLeast(DEFAULT_CAPACITY_FRAMES)
        if (resampledBlock.channelCount != channelCount || resampledBlock.capacityFrames < resampledCap) {
            resampledBlock = AudioBlock(channelCount = channelCount, capacityFrames = resampledCap)
        } else {
            resampledBlock.reset(0)
        }

        val requiredCapacity = resampledBlock.capacityFrames * channelCount * PcmEncoding.PCM_FLOAT.bytesPerSample
        if (outputByteBuffer.capacity() < requiredCapacity) {
            outputByteBuffer = ByteBuffer.allocateDirect(requiredCapacity).order(ByteOrder.nativeOrder())
        }
        outputByteBuffer.clear()
        outputByteBuffer.flip()
    }

    private fun publishOutputExactness(source: PcmEncoding, target: PcmEncoding) {
        val isResampling = resampler.isActive
        val encodingExact = when (source) {
            PcmEncoding.PCM_16BIT -> target == PcmEncoding.PCM_16BIT || target == PcmEncoding.PCM_FLOAT
            PcmEncoding.PCM_24BIT_PACKED -> target == PcmEncoding.PCM_FLOAT
            PcmEncoding.PCM_FLOAT -> target == PcmEncoding.PCM_FLOAT
            PcmEncoding.PCM_32BIT -> false
        }
        val exact = encodingExact && !isResampling
        val sourceLabel = encodingLabel(mapFromPcmEncoding(source))
        val targetLabel = encodingLabel(mapFromPcmEncoding(target))
        val detail = when {
            exact -> "$sourceLabel → $targetLabel"
            isResampling && encodingExact ->
                "$sourceLabel → $targetLabel (${configuredSampleRate / 1000.0}k → ${configuredOutputSampleRate / 1000.0}k Sinc)"
            source == PcmEncoding.PCM_32BIT ->
                "32-bit PCM exceeds what AudioTrack can carry; 8 bits lost"
            else -> "$sourceLabel → 16-bit; this route cannot open a float track"
        }
        AudioOutputStatus.publishOutputExactness(exact = exact, detail = detail)
    }

    private fun publishTelemetry(inEncoding: PcmEncoding, outEncoding: PcmEncoding) {
        if (!isAudible()) return
        publishOutputExactness(inEncoding, outEncoding)
        AudioOutputStatus.publishResampler(
            sourceRateHz = configuredSampleRate,
            outputRateHz = configuredOutputSampleRate,
            active = resampler.isActive,
            type = if (resampler.isActive) HighPrecisionResampler.TYPE_LABEL else "None",
            cutoffHz = if (resampler.isActive) resampler.cutoffHz else null,
        )
        AudioOutputStatus.publishDsp(
            decoderOutputEncoding = encodingLabel(mapFromPcmEncoding(inEncoding)),
            dspFormat = "Float32",
            dspAvailable = true,
        )
    }

    private fun logConfig(
        precisionActive: Boolean,
        inputEncoding: String,
        sampleRate: Int,
        outputSampleRate: Int,
        channelCount: Int,
        targetOutputEncoding: String,
        enableFloatOutput: Boolean,
        delegateEncoding: Int,
    ) {
        if (BuildConfig.DEBUG) {
            try {
                Log.d(
                    TAG,
                    "configure() precisionActive=$precisionActive inputEncoding=$inputEncoding " +
                        "sr=$sampleRate outSr=$outputSampleRate ch=$channelCount " +
                        "targetOutputEncoding=$targetOutputEncoding enableFloatOutput=$enableFloatOutput " +
                        "delegateEncoding=$delegateEncoding",
                )
            } catch (_: Throwable) {
            }
        }
    }

    companion object {
        private const val TAG = "PrecisionAudioSink"
        const val DEFAULT_CAPACITY_FRAMES: Int = 4096
        const val MAX_CHANNELS: Int = 8

        fun encodingLabel(pcmEncoding: Int): String = when (pcmEncoding) {
            C.ENCODING_PCM_16BIT -> "16-bit PCM"
            C.ENCODING_PCM_24BIT -> "24-bit PCM"
            C.ENCODING_PCM_32BIT -> "32-bit PCM"
            C.ENCODING_PCM_FLOAT -> "Float32"
            else -> "Non-PCM"
        }

        fun mapToPcmEncoding(pcmEncoding: Int): PcmEncoding? = when (pcmEncoding) {
            C.ENCODING_PCM_16BIT -> PcmEncoding.PCM_16BIT
            C.ENCODING_PCM_24BIT -> PcmEncoding.PCM_24BIT_PACKED
            C.ENCODING_PCM_32BIT -> PcmEncoding.PCM_32BIT
            C.ENCODING_PCM_FLOAT -> PcmEncoding.PCM_FLOAT
            else -> null
        }

        fun mapFromPcmEncoding(pcmEncoding: PcmEncoding): Int = when (pcmEncoding) {
            PcmEncoding.PCM_16BIT -> C.ENCODING_PCM_16BIT
            PcmEncoding.PCM_24BIT_PACKED -> C.ENCODING_PCM_24BIT
            PcmEncoding.PCM_32BIT -> C.ENCODING_PCM_32BIT
            PcmEncoding.PCM_FLOAT -> C.ENCODING_PCM_FLOAT
        }

        fun PcmEncoding.toMedia3PcmEncoding(): Int = mapFromPcmEncoding(this)
    }
}
