package com.music.vibe

import com.music.vibe.data.innertube.InnertubeParser
import com.music.vibe.data.lyrics.LyricsSource
import com.music.vibe.playback.SleepTimer
import com.music.vibe.ui.MainViewModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerTest {

    @Test
    fun constants_matchFiveMinuteStepsUpToTwelveHours() {
        assertEquals(5, SleepTimer.STEP_MINUTES)
        assertEquals(5, SleepTimer.MIN_MINUTES)
        assertEquals(720, SleepTimer.MAX_MINUTES)
    }

    @Test
    fun minutesAt_andFractionOf_coverEndpointsAndTargetMinutes() {
        assertEquals(5, SleepTimer.minutesAt(0f))
        assertEquals(720, SleepTimer.minutesAt(1f))
        assertEquals(0f, SleepTimer.fractionOf(5), 1e-6f)
        assertEquals(1f, SleepTimer.fractionOf(720), 1e-6f)

        // Acceptance criteria targets: 5, 35, 125, 720 minutes
        for (target in listOf(5, 35, 125, 720)) {
            val fraction = SleepTimer.fractionOf(target)
            assertTrue("Fraction $fraction out of [0, 1]", fraction in 0f..1f)
            assertEquals(target, SleepTimer.minutesAt(fraction))
        }
    }

    @Test
    fun minutesAt_snapsToFiveMinuteStepsAndRoundTripsWithFractionOf() {
        // Test every 5-minute step from 5 to 720
        for (minutes in SleepTimer.MIN_MINUTES..SleepTimer.MAX_MINUTES step SleepTimer.STEP_MINUTES) {
            val fraction = SleepTimer.fractionOf(minutes)
            val roundTrippedMinutes = SleepTimer.minutesAt(fraction)
            assertEquals(minutes, roundTrippedMinutes)
            assertEquals(0, roundTrippedMinutes % SleepTimer.STEP_MINUTES)
            assertEquals(fraction, SleepTimer.fractionOf(roundTrippedMinutes), 1e-5f)
        }

        // Test arbitrary fractions across [0, 1] snap to 5-minute multiples and round-trip
        for (i in 0..200) {
            val f = i / 200f
            val snappedMinutes = SleepTimer.minutesAt(f)
            assertTrue(snappedMinutes in SleepTimer.MIN_MINUTES..SleepTimer.MAX_MINUTES)
            assertEquals(0, snappedMinutes % SleepTimer.STEP_MINUTES)
            val snappedFraction = SleepTimer.fractionOf(snappedMinutes)
            assertEquals(snappedMinutes, SleepTimer.minutesAt(snappedFraction))
        }
    }

    @Test
    fun quickCustomMinutes_andFormattingHelpers_produceExpectedLabels() {
        assertEquals(listOf(45, 60, 90, 120, 240, 480), SleepTimer.QUICK_CUSTOM_MINUTES)
        assertEquals("45m", SleepTimer.formatQuickChipLabel(45))
        assertEquals("1h", SleepTimer.formatQuickChipLabel(60))
        assertEquals("1h 30m", SleepTimer.formatQuickChipLabel(90))
        assertEquals("2h", SleepTimer.formatQuickChipLabel(120))
        assertEquals("4h", SleepTimer.formatQuickChipLabel(240))
        assertEquals("8h", SleepTimer.formatQuickChipLabel(480))

        assertEquals("0:00", SleepTimer.formatRemaining(0L))
        assertEquals("14:32", SleepTimer.formatRemaining((14 * 60 + 32) * 1000L))
        assertEquals("1:29:54", SleepTimer.formatRemaining((1 * 3600 + 29 * 60 + 54) * 1000L))
    }

    @Test
    fun lyricsLookupKey_invalidatesWhenOrderOrSyllableOrTitleOrApiKeyChanges() {
        val baseOrder = LyricsSource.entries.toList()
        val baseSources = LyricsSource.entries.toSet()
        val base = MainViewModel.LyricsLookupKey(
            videoId = "vid_123",
            title = "Original Song Title",
            artist = "Artist Name",
            sources = baseSources,
            order = baseOrder,
            prioritizeSyllableSync = true,
            paxSenixKeyHash = "key_a".hashCode(),
        )
        assertEquals(base, base.copy())
        assertNotEquals(base, base.copy(order = baseOrder.reversed()))
        assertNotEquals(base, base.copy(prioritizeSyllableSync = false))
        assertNotEquals(base, base.copy(title = "Romaji Song Title"))
        assertNotEquals(base, base.copy(paxSenixKeyHash = "key_b".hashCode()))
    }

    @Test
    fun parseHomeChips_extractsAndDeduplicatesMoodChips() {
        val sampleJson = Json.parseToJsonElement(
            """
            {
              "contents": {
                "singleColumnBrowseResultsRenderer": {
                  "tabs": [
                    {
                      "tabRenderer": {
                        "content": {
                          "sectionListRenderer": {
                            "header": {
                              "chipCloudRenderer": {
                                "chips": [
                                  {
                                    "chipCloudChipRenderer": {
                                      "text": { "runs": [{ "text": "Podcasts" }] },
                                      "navigationEndpoint": {
                                        "browseEndpoint": {
                                          "browseId": "FEmusic_non_music_audio_list",
                                          "params": "ggM8CgPGsA_podcasts"
                                        }
                                      }
                                    }
                                  },
                                  {
                                    "chipCloudChipRenderer": {
                                      "text": { "runs": [{ "text": "Focus" }] },
                                      "navigationEndpoint": {
                                        "browseEndpoint": {
                                          "browseId": "FEmusic_home",
                                          "params": "ggM8CgPGsA"
                                        }
                                      }
                                    }
                                  },
                                  {
                                    "chipCloudChipRenderer": {
                                      "text": { "runs": [{ "text": "Workout" }] },
                                      "navigationEndpoint": {
                                        "browseEndpoint": {
                                          "browseId": "FEmusic_moods_and_genres_category",
                                          "params": "ggMPOg1uX1p4"
                                        }
                                      }
                                    }
                                  },
                                  {
                                    "chipCloudChipRenderer": {
                                      "text": { "runs": [{ "text": "focus" }] },
                                      "navigationEndpoint": {
                                        "browseEndpoint": {
                                          "browseId": "FEmusic_home",
                                          "params": "duplicate"
                                        }
                                      }
                                    }
                                  }
                                ]
                              }
                            }
                          }
                        }
                      }
                    }
                  ]
                }
              }
            }
            """.trimIndent(),
        ).jsonObject

        val chips = InnertubeParser.parseHomeChips(sampleJson)
        assertEquals(2, chips.size)
        assertEquals("Focus", chips[0].title)
        assertEquals("FEmusic_home", chips[0].browseId)
        assertEquals("ggM8CgPGsA", chips[0].params)
        assertEquals("Workout", chips[1].title)
        assertEquals("FEmusic_moods_and_genres_category", chips[1].browseId)
        assertEquals("ggMPOg1uX1p4", chips[1].params)
    }

    @Test
    fun parseHome_supportsGridRendererAndSkipsPodcastShelves() {
        val sampleJson = Json.parseToJsonElement(
            """
            {
              "contents": {
                "singleColumnBrowseResultsRenderer": {
                  "tabs": [
                    {
                      "tabRenderer": {
                        "content": {
                          "sectionListRenderer": {
                            "contents": [
                              {
                                "gridRenderer": {
                                  "header": {
                                    "gridHeaderRenderer": {
                                      "title": { "runs": [{ "text": "Focus playlists" }] }
                                    }
                                  },
                                  "items": [
                                    {
                                      "musicTwoRowItemRenderer": {
                                        "title": { "runs": [{ "text": "Deep Focus" }] },
                                        "subtitle": { "runs": [{ "text": "Playlist • YouTube Music" }] },
                                        "navigationEndpoint": {
                                          "browseEndpoint": {
                                            "browseId": "VLPL_focus_1"
                                          }
                                        }
                                      }
                                    }
                                  ]
                                }
                              },
                              {
                                "gridRenderer": {
                                  "header": {
                                    "gridHeaderRenderer": {
                                      "title": { "runs": [{ "text": "Top Podcasts" }] }
                                    }
                                  },
                                  "items": [
                                    {
                                      "musicTwoRowItemRenderer": {
                                        "title": { "runs": [{ "text": "Daily Podcast" }] },
                                        "subtitle": { "runs": [{ "text": "Podcast" }] },
                                        "navigationEndpoint": {
                                          "browseEndpoint": {
                                            "browseId": "MPSP_pod_1"
                                          }
                                        }
                                      }
                                    }
                                  ]
                                }
                              }
                            ]
                          }
                        }
                      }
                    }
                  ]
                }
              }
            }
            """.trimIndent(),
        ).jsonObject

        val shelves = InnertubeParser.parseHome(sampleJson)
        assertEquals(1, shelves.size)
        assertEquals("Focus playlists", shelves[0].title)
        assertEquals(1, shelves[0].items.size)
        assertEquals("Deep Focus", shelves[0].items[0].title)
        assertEquals("VLPL_focus_1", shelves[0].items[0].browseId)
    }
}
