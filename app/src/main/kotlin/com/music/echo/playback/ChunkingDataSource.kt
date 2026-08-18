package com.music.echo.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

class ChunkingDataSource(
    private val upstream: DataSource,
    private val chunkSize: Long = 5 * 1024 * 1024L
) : DataSource {

    private var dataSpec: DataSpec? = null
    private var bytesRemaining: Long = 0
    private var currentChunkBytesRemaining: Long = 0
    private var isOpen = false

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        val resolvedLength = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            val length = upstream.open(dataSpec)
            if (length != C.LENGTH_UNSET.toLong()) {
                bytesRemaining = length
                currentChunkBytesRemaining = minOf(chunkSize, bytesRemaining)
                upstream.close()
                openNextChunk()
                length
            } else {
                // If upstream length is still unset, we just use the upstream as is.
                this.bytesRemaining = C.LENGTH_UNSET.toLong()
                this.currentChunkBytesRemaining = C.LENGTH_UNSET.toLong()
                isOpen = true
                C.LENGTH_UNSET.toLong()
            }
        } else {
            bytesRemaining = dataSpec.length
            openNextChunk()
            dataSpec.length
        }
        isOpen = true
        return resolvedLength
    }

    private fun openNextChunk() {
        val chunkLength = if (bytesRemaining == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else minOf(chunkSize, bytesRemaining)
        currentChunkBytesRemaining = chunkLength
        val position = dataSpec!!.position + if (dataSpec!!.length == C.LENGTH_UNSET.toLong()) 0L else (dataSpec!!.length - bytesRemaining)
        val chunkDataSpec = dataSpec!!.buildUpon()
            .setPosition(position)
            .setLength(chunkLength)
            .build()
        upstream.open(chunkDataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int {
        if (!isOpen) return C.RESULT_END_OF_INPUT

        if (currentChunkBytesRemaining == 0L) {
            upstream.close()
            if (bytesRemaining > 0) {
                openNextChunk()
            } else {
                return C.RESULT_END_OF_INPUT
            }
        }
        
        val maxReadLength = if (currentChunkBytesRemaining == C.LENGTH_UNSET.toLong()) readLength else minOf(readLength.toLong(), currentChunkBytesRemaining).toInt()
        val bytesRead = upstream.read(buffer, offset, maxReadLength)
        
        if (bytesRead != C.RESULT_END_OF_INPUT) {
            if (currentChunkBytesRemaining != C.LENGTH_UNSET.toLong()) {
                currentChunkBytesRemaining -= bytesRead
            }
            if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
                bytesRemaining -= bytesRead
            }
        } else if (currentChunkBytesRemaining != C.LENGTH_UNSET.toLong() && currentChunkBytesRemaining > 0) {
            // Unexpected end of input?
            bytesRemaining = 0
            currentChunkBytesRemaining = 0
        }
        return bytesRead
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        if (isOpen) {
            upstream.close()
            isOpen = false
        }
    }

    class Factory(
        private val upstreamFactory: DataSource.Factory,
        private val chunkSize: Long = 5 * 1024 * 1024L
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            return ChunkingDataSource(upstreamFactory.createDataSource(), chunkSize)
        }
    }
}
