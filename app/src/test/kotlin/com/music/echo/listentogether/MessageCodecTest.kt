package com.music.echo.listentogether

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MessageCodecTest {
    @Test
    fun testCodecFormatDetectionJson() {
        val codec = MessageCodec()
        val jsonPayload = """{"type":"test","payload":{"userId":"123"}}"""
        val format = MessageCodec.detectMessageFormat(jsonPayload.toByteArray(Charsets.UTF_8))
        assertEquals(MessageFormat.JSON, format)
    }

    @Test
    fun testCodecDecodeJson() {
        val codec = MessageCodec()
        val jsonPayload = """{"type":"room_created","payload":{"roomCode":"ABC","userId":"123","sessionToken":"token"}}"""
        val (type, payloadBytes) = codec.decode(jsonPayload.toByteArray(Charsets.UTF_8))
        assertEquals(MessageTypes.ROOM_CREATED, type)
        assertNotNull(payloadBytes)
    }
}
