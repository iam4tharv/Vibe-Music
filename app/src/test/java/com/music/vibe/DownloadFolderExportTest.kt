package com.music.vibe

import com.music.vibe.data.stats.Backup
import com.music.vibe.download.DownloadStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadFolderExportTest {

    @Test
    fun normalizedStem_preservesCollisionSuffixOnPassOne_stripsOnPassTwo() {
        val exactStem = DownloadStore.normalizedStem("Artist - Song (1).m4a", stripCollisionSuffix = false)
        val strippedStem = DownloadStore.normalizedStem("Artist - Song (1).m4a", stripCollisionSuffix = true)
        val baseStem = DownloadStore.normalizedStem("Artist - Song.opus", stripCollisionSuffix = false)

        assertEquals("artist - song (1)", exactStem)
        assertEquals("artist - song", strippedStem)
        assertEquals(baseStem, strippedStem)
    }

    @Test
    fun isCustomFolderSameAsDefaultMusic_detectsPrimaryMusicVibeMusic() {
        val defaultTreeUri = "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FVibe%20Music"
        val otherTreeUri = "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FCustom"

        assertTrue(DownloadStore.isCustomFolderSameAsDefaultMusic(defaultTreeUri))
        assertFalse(DownloadStore.isCustomFolderSameAsDefaultMusic(otherTreeUri))
    }

    @Test
    fun treeOverlaps_detectsParentAndChildTrees() {
        assertTrue(DownloadStore.treeOverlaps("primary:Music", "primary:Music/Vibe Music"))
        assertTrue(DownloadStore.treeOverlaps("primary:Music/Vibe Music", "primary:Music"))
        assertTrue(DownloadStore.treeOverlaps("primary:Music/Vibe Music", "primary:Music/Vibe Music"))
        assertFalse(DownloadStore.treeOverlaps("primary:Music", "primary:MusicOther"))
    }

    @Test
    fun filterSafTreeTrackUris_removesSafTreeUrisAndKeepsMediaStoreAndFileUris() {
        val raw = """
            {
              "vid_saf": "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2Fsong.m4a",
              "vid_media": "content://media/external/audio/media/42",
              "vid_file": "file:///storage/emulated/0/Android/data/com.music.vibe/files/Music/song.m4a"
            }
        """.trimIndent()

        val filtered = Backup.filterSafTreeTrackUris(raw)
        assertFalse(filtered.contains("vid_saf"))
        assertTrue(filtered.contains("vid_media"))
        assertTrue(filtered.contains("vid_file"))
    }
}
