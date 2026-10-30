package com.music.vibe.data

import com.music.vibe.data.model.ArtistRef
import com.music.vibe.data.model.PlaybackSourceType
import com.music.vibe.data.model.QueueTier
import com.music.vibe.data.model.Song
import com.music.vibe.data.model.SongJsonCodec
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RunCatchingLoggedTest {

    @Test
    fun `runCatchingLogged rethrows CancellationException and does not swallow coroutine cancellation`() {
        DebugLog.clearEntries()
        assertFailsWith<CancellationException> {
            runCatchingLogged("TestTag", "cancel-test") {
                throw CancellationException("job cancelled")
            }
        }
        assertTrue(DebugLog.recentEntries().none { it.tag == "TestTag" })
    }

    @Test
    fun `runCatchingLogged captures regular exceptions into DebugLog ring buffer`() {
        DebugLog.clearEntries()
        val result = runCatchingLogged("TestTag", "network-parse") {
            throw IllegalStateException("unexpected token")
        }
        assertTrue(result.isFailure)
        val entry = DebugLog.recentEntries().lastOrNull { it.tag == "TestTag" }
        assertNotNull(entry)
        assertTrue(entry.message.contains("network-parse"))
        assertTrue(entry.errorSummary?.contains("IllegalStateException") == true)
    }

    @Test
    fun `SongJsonCodec round-trips all Song fields losslessly`() {
        val original = Song(
            videoId = "vid_123",
            title = "Midnight City",
            artist = "M83",
            thumbnailUrl = "https://example.com/art.jpg",
            durationText = "4:03",
            artistId = "UC_m83",
            artists = listOf(ArtistRef("M83", "UC_m83"), ArtistRef("Featured", "UC_feat")),
            albumId = "MPREb_album",
            albumName = "Hurry Up, We're Dreaming",
            isVideo = false,
            isVideoOrigin = false,
            setVideoId = "set_99",
            queueTier = QueueTier.USER_QUEUE,
            queueEntryId = "entry_4242",
            radioName = "Midnight Radio",
            localUri = "content://media/external/audio/media/777",
            downloadFormat = "FLAC · 96 kHz",
            localPath = "/storage/emulated/0/Music/midnight.flac",
            localDateAddedSeconds = 1_700_000_000L,
            localDateModifiedSeconds = 1_710_000_000L,
            sourceQuality = "Hi-Res Lossless",
            isExplicit = true,
            playbackSource = "Album",
            playbackSourceType = PlaybackSourceType.BROWSE,
            playbackSourceId = "MPREb_album",
            trackNumber = 1,
        )

        val encoded = SongJsonCodec.encode(original)
        val decoded = SongJsonCodec.decode(encoded)
        assertEquals(original, decoded)

        val listEncoded = SongJsonCodec.encodeList(listOf(original))
        val listDecoded = SongJsonCodec.decodeList(listEncoded)
        assertEquals(listOf(original), listDecoded)
    }
}
