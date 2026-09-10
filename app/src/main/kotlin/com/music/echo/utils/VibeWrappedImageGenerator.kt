/**
 * Vibe Music Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.echo.utils

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

enum class VibeCardTheme(val displayName: String) {
    AURORA("Aurora Neon"),
    SUNSET("Sunset Gold"),
    EMERALD("Electric Mint")
}

data class TopSongItem(
    val title: String,
    val artistName: String,
    val playCount: Int,
    val timePlayedMs: Long?,
    val thumbnailUrl: String?
)

data class TopArtistItem(
    val name: String,
    val playCount: Int,
    val timePlayedMs: Long?,
    val thumbnailUrl: String?
)

data class TopAlbumItem(
    val title: String,
    val playCount: Int,
    val timePlayedMs: Long?,
    val thumbnailUrl: String?
)

data class VibeWrappedData(
    val periodLabel: String,
    val totalPlayTimeMs: Long,
    val allTimePlayTimeMs: Long,
    val uniqueSongs: Int,
    val uniqueArtists: Int,
    val uniqueAlbums: Int,
    val topSongs: List<TopSongItem>,
    val topArtists: List<TopArtistItem>,
    val topAlbums: List<TopAlbumItem>
)

object VibeWrappedImageGenerator {

    private const val CANVAS_WIDTH = 1080
    private const val CANVAS_HEIGHT = 1920

    suspend fun generateWrappedBitmap(
        context: Context,
        data: VibeWrappedData,
        theme: VibeCardTheme = VibeCardTheme.AURORA
    ): Bitmap = withContext(Dispatchers.Default) {
        val bitmap = createBitmap(CANVAS_WIDTH, CANVAS_HEIGHT)
        val canvas = Canvas(bitmap)

        // 1. Fetch images (Top 1 song, Top 3 artists)
        val topSongCover = data.topSongs.firstOrNull()?.thumbnailUrl?.let { url ->
            fetchBitmap(context, url, 400)
        }
        val topArtistAvatars = data.topArtists.take(3).map { artist ->
            artist.thumbnailUrl?.let { url -> fetchBitmap(context, url, 300) }
        }

        // 2. Draw Theme Background & Glowing Ambient Orbs
        drawBackground(canvas, theme)

        // 3. Draw Header Brand & Period
        drawHeader(canvas, data.periodLabel, theme)

        // 4. Draw Hero Listening Time & Metrics Card
        drawHeroStats(canvas, data, theme)

        // 5. Draw Top Songs Section
        drawTopSongs(canvas, data.topSongs, topSongCover, theme)

        // 6. Draw Top Artists Section
        drawTopArtists(canvas, data.topArtists, topArtistAvatars, theme)

        // 7. Draw Footer & App Badge
        drawFooter(canvas, theme)

        bitmap
    }

    private suspend fun fetchBitmap(context: Context, url: String, size: Int): Bitmap? {
        return try {
            val imageLoader = ImageLoader(context)
            val request = ImageRequest.Builder(context)
                .data(url)
                .size(size)
                .allowHardware(false)
                .build()
            val result = imageLoader.execute(request)
            result.image?.toBitmap()
        } catch (_: Exception) {
            null
        }
    }

    private fun drawBackground(canvas: Canvas, theme: VibeCardTheme) {
        val w = CANVAS_WIDTH.toFloat()
        val h = CANVAS_HEIGHT.toFloat()

        // Base gradient
        val (topBg, midBg, botBg) = when (theme) {
            VibeCardTheme.AURORA -> Triple(0xFF0C0B18.toInt(), 0xFF140E28.toInt(), 0xFF080D1A.toInt())
            VibeCardTheme.SUNSET -> Triple(0xFF140A0A.toInt(), 0xFF220E12.toInt(), 0xFF100814.toInt())
            VibeCardTheme.EMERALD -> Triple(0xFF061412.toInt(), 0xFF091F1C.toInt(), 0xFF061118.toInt())
        }

        val basePaint = Paint().apply {
            isAntiAlias = true
            shader = LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(topBg, midBg, botBg),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w, h, basePaint)

        // Glowing Ambient Orbs
        val (glowColor1, glowColor2, glowColor3) = when (theme) {
            VibeCardTheme.AURORA -> Triple(0x669333EA.toInt(), 0x5506B6D4.toInt(), 0x44EC4899.toInt())
            VibeCardTheme.SUNSET -> Triple(0x66EA580C.toInt(), 0x55E11D48.toInt(), 0x44FBBF24.toInt())
            VibeCardTheme.EMERALD -> Triple(0x66059669.toInt(), 0x550D9488.toInt(), 0x4410B981.toInt())
        }

        val orbPaint = Paint().apply { isAntiAlias = true }

        // Top-Right Orb
        orbPaint.shader = RadialGradient(
            w * 0.85f, h * 0.15f, 480f,
            glowColor1, Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(w * 0.85f, h * 0.15f, 480f, orbPaint)

        // Center-Left Orb
        orbPaint.shader = RadialGradient(
            w * 0.1f, h * 0.45f, 420f,
            glowColor2, Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(w * 0.1f, h * 0.45f, 420f, orbPaint)

        // Bottom-Right Orb
        orbPaint.shader = RadialGradient(
            w * 0.85f, h * 0.78f, 460f,
            glowColor3, Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(w * 0.85f, h * 0.78f, 460f, orbPaint)
    }

    private fun drawHeader(canvas: Canvas, periodLabel: String, theme: VibeCardTheme) {
        val accentColor = when (theme) {
            VibeCardTheme.AURORA -> 0xFF818CF8.toInt()
            VibeCardTheme.SUNSET -> 0xFFFB923C.toInt()
            VibeCardTheme.EMERALD -> 0xFF34D399.toInt()
        }

        // Pill badge
        val badgeRect = RectF(60f, 80f, 310f, 134f)
        val badgeBgPaint = Paint().apply {
            isAntiAlias = true
            color = 0x26FFFFFF.toInt()
            style = Paint.Style.FILL
        }
        val badgeBorderPaint = Paint().apply {
            isAntiAlias = true
            color = 0x40FFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        canvas.drawRoundRect(badgeRect, 27f, 27f, badgeBgPaint)
        canvas.drawRoundRect(badgeRect, 27f, 27f, badgeBorderPaint)

        val badgeTextPaint = Paint().apply {
            isAntiAlias = true
            color = Color.WHITE
            textSize = 22f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.08f
        }
        canvas.drawText("✦ VIBE MUSIC", 90f, 115f, badgeTextPaint)

        // WRAPPED Title
        val titlePaint = Paint().apply {
            isAntiAlias = true
            color = Color.WHITE
            textSize = 68f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = -0.02f
        }
        canvas.drawText("WRAPPED", 60f, 212f, titlePaint)

        // Period Label (right-aligned or next to title)
        val periodText = periodLabel.ifEmpty { "ALL TIME" }.uppercase()
        val periodPillPaint = Paint().apply {
            isAntiAlias = true
            color = 0x22FFFFFF.toInt()
        }
        val periodTextPaint = Paint().apply {
            isAntiAlias = true
            color = accentColor
            textSize = 24f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.04f
        }
        val textWidth = periodTextPaint.measureText(periodText)
        val pillRight = CANVAS_WIDTH - 60f
        val pillLeft = pillRight - textWidth - 36f
        val periodPillRect = RectF(pillLeft, 160f, pillRight, 214f)
        canvas.drawRoundRect(periodPillRect, 27f, 27f, periodPillPaint)
        canvas.drawText(periodText, pillLeft + 18f, 196f, periodTextPaint)
    }

    private fun drawHeroStats(canvas: Canvas, data: VibeWrappedData, theme: VibeCardTheme) {
        val heroRect = RectF(60f, 250f, CANVAS_WIDTH - 60f, 570f)

        // Card glass background
        val heroBgPaint = Paint().apply {
            isAntiAlias = true
            color = 0x20FFFFFF.toInt()
            style = Paint.Style.FILL
        }
        val heroBorderPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            shader = LinearGradient(
                60f, 250f, CANVAS_WIDTH - 60f, 570f,
                intArrayOf(0x60FFFFFF.toInt(), 0x15FFFFFF.toInt()),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(heroRect, 36f, 36f, heroBgPaint)
        canvas.drawRoundRect(heroRect, 36f, 36f, heroBorderPaint)

        // Total listening time header label
        val labelPaint = Paint().apply {
            isAntiAlias = true
            color = when (theme) {
                VibeCardTheme.AURORA -> 0xFFA5B4FC.toInt()
                VibeCardTheme.SUNSET -> 0xFFFED7AA.toInt()
                VibeCardTheme.EMERALD -> 0xFFA7F3D0.toInt()
            }
            textSize = 22f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.1f
        }
        canvas.drawText("TOTAL LISTENING TIME", 96f, 305f, labelPaint)

        // Big Time text
        val timeStr = formatDuration(data.totalPlayTimeMs)
        val timePaint = Paint().apply {
            isAntiAlias = true
            color = Color.WHITE
            textSize = 80f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = -0.03f
        }
        canvas.drawText(timeStr, 96f, 395f, timePaint)

        // Subtitle (e.g. minutes listened)
        val totalMinutes = data.totalPlayTimeMs / 60_000
        val subPaint = Paint().apply {
            isAntiAlias = true
            color = 0x99FFFFFF.toInt()
            textSize = 24f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        canvas.drawText("Over ${"%,d".format(totalMinutes)} minutes of pure sound", 96f, 435f, subPaint)

        // 3 mini metric pills inside the hero card
        val pillY = 465f
        val pillHeight = 72f
        val pillSpacing = 16f
        val pillWidth = (heroRect.width() - 72f - (pillSpacing * 2)) / 3f

        drawMetricPill(canvas, heroRect.left + 36f, pillY, pillWidth, pillHeight, "🎵 Songs", "${data.uniqueSongs}")
        drawMetricPill(canvas, heroRect.left + 36f + pillWidth + pillSpacing, pillY, pillWidth, pillHeight, "👤 Artists", "${data.uniqueArtists}")
        drawMetricPill(canvas, heroRect.left + 36f + (pillWidth + pillSpacing) * 2, pillY, pillWidth, pillHeight, "💿 Albums", "${data.uniqueAlbums}")
    }

    private fun drawMetricPill(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, label: String, value: String) {
        val rect = RectF(x, y, x + w, y + h)
        val bgPaint = Paint().apply {
            isAntiAlias = true
            color = 0x1AFFFFFF.toInt()
        }
        canvas.drawRoundRect(rect, 20f, 20f, bgPaint)

        val valPaint = Paint().apply {
            isAntiAlias = true
            color = Color.WHITE
            textSize = 28f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val lblPaint = Paint().apply {
            isAntiAlias = true
            color = 0xB3FFFFFF.toInt()
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }

        canvas.drawText(value, x + w / 2f, y + 32f, valPaint)
        canvas.drawText(label, x + w / 2f, y + 56f, lblPaint)
    }

    private fun drawTopSongs(
        canvas: Canvas,
        songs: List<TopSongItem>,
        topSongCover: Bitmap?,
        theme: VibeCardTheme
    ) {
        val startY = 605f
        val sectionTitlePaint = Paint().apply {
            isAntiAlias = true
            color = Color.WHITE
            textSize = 34f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = -0.01f
        }
        canvas.drawText("Top Tracks", 60f, startY + 30f, sectionTitlePaint)

        val accentColor = when (theme) {
            VibeCardTheme.AURORA -> 0xFF818CF8.toInt()
            VibeCardTheme.SUNSET -> 0xFFFB923C.toInt()
            VibeCardTheme.EMERALD -> 0xFF34D399.toInt()
        }

        val topSong = songs.firstOrNull()
        if (topSong != null) {
            // Featured #1 Card (y: 660..860)
            val cardRect = RectF(60f, startY + 50f, CANVAS_WIDTH - 60f, startY + 240f)
            val cardBgPaint = Paint().apply {
                isAntiAlias = true
                color = 0x1CFFFFFF.toInt()
            }
            val cardBorderPaint = Paint().apply {
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeWidth = 2f
                color = 0x33FFFFFF.toInt()
            }
            canvas.drawRoundRect(cardRect, 28f, 28f, cardBgPaint)
            canvas.drawRoundRect(cardRect, 28f, 28f, cardBorderPaint)

            // Cover Art (140x140)
            val coverSize = 140f
            val coverLeft = cardRect.left + 24f
            val coverTop = cardRect.top + 25f
            val coverRect = RectF(coverLeft, coverTop, coverLeft + coverSize, coverTop + coverSize)

            if (topSongCover != null) {
                drawRoundedBitmap(canvas, topSongCover, coverRect, 20f)
            } else {
                val placeholderPaint = Paint().apply {
                    isAntiAlias = true
                    shader = LinearGradient(
                        coverLeft, coverTop, coverLeft + coverSize, coverTop + coverSize,
                        intArrayOf(accentColor, 0xFF4338CA.toInt()),
                        null,
                        Shader.TileMode.CLAMP
                    )
                }
                canvas.drawRoundRect(coverRect, 20f, 20f, placeholderPaint)
                val notePaint = Paint().apply {
                    isAntiAlias = true
                    color = Color.WHITE
                    textSize = 54f
                    textAlign = Paint.Align.CENTER
                }
                canvas.drawText("♪", coverRect.centerX(), coverRect.centerY() + 18f, notePaint)
            }

            // #1 Badge on Cover
            val rankBadgeRect = RectF(coverLeft - 6f, coverTop - 6f, coverLeft + 42f, coverTop + 42f)
            val rankBadgePaint = Paint().apply {
                isAntiAlias = true
                color = accentColor
            }
            canvas.drawRoundRect(rankBadgeRect, 12f, 12f, rankBadgePaint)
            val rankTextPaint = Paint().apply {
                isAntiAlias = true
                color = Color.BLACK
                textSize = 24f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("#1", rankBadgeRect.centerX(), rankBadgeRect.centerY() + 8f, rankTextPaint)

            // Song Title & Artist text
            val textLeft = coverRect.right + 24f
            val maxTextWidth = cardRect.right - textLeft - 24f

            val songTitlePaint = TextPaint().apply {
                isAntiAlias = true
                color = Color.WHITE
                textSize = 34f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val titleLayout = createStaticLayout(topSong.title, songTitlePaint, maxTextWidth.toInt(), 1)
            canvas.save()
            canvas.translate(textLeft, coverTop + 12f)
            titleLayout.draw(canvas)
            canvas.restore()

            val artistPaint = TextPaint().apply {
                isAntiAlias = true
                color = 0xCCFFFFFF.toInt()
                textSize = 26f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            }
            val artistLayout = createStaticLayout(topSong.artistName, artistPaint, maxTextWidth.toInt(), 1)
            canvas.save()
            canvas.translate(textLeft, coverTop + 58f)
            artistLayout.draw(canvas)
            canvas.restore()

            // Play count tag
            val countText = "★ ${topSong.playCount} plays" + if (topSong.timePlayedMs != null) " • ${formatDuration(topSong.timePlayedMs)}" else ""
            val countPaint = Paint().apply {
                isAntiAlias = true
                color = accentColor
                textSize = 22f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            canvas.drawText(countText, textLeft, coverTop + 124f, countPaint)
        }

        // Song #2, #3, #4, #5
        var currentY = startY + 265f
        val otherSongs = songs.drop(1).take(4)
        for ((idx, song) in otherSongs.withIndex()) {
            val rank = idx + 2
            val rowRect = RectF(60f, currentY, CANVAS_WIDTH - 60f, currentY + 68f)

            // Rank Circle
            val circleX = rowRect.left + 24f
            val circleY = rowRect.centerY()
            val circlePaint = Paint().apply {
                isAntiAlias = true
                color = 0x22FFFFFF.toInt()
            }
            canvas.drawCircle(circleX, circleY, 20f, circlePaint)
            val rankNumPaint = Paint().apply {
                isAntiAlias = true
                color = 0xE6FFFFFF.toInt()
                textSize = 20f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("$rank", circleX, circleY + 7f, rankNumPaint)

            // Song title & artist
            val songTextLeft = circleX + 36f
            val maxRowTextWidth = (rowRect.width() - 220f).toInt()

            val titlePaint = TextPaint().apply {
                isAntiAlias = true
                color = Color.WHITE
                textSize = 26f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val titleLayout = createStaticLayout(song.title, titlePaint, maxRowTextWidth, 1)
            canvas.save()
            canvas.translate(songTextLeft, rowRect.top + 6f)
            titleLayout.draw(canvas)
            canvas.restore()

            val subPaint = TextPaint().apply {
                isAntiAlias = true
                color = 0x8AFFFFFF.toInt()
                textSize = 20f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            }
            val subLayout = createStaticLayout(song.artistName, subPaint, maxRowTextWidth, 1)
            canvas.save()
            canvas.translate(songTextLeft, rowRect.top + 38f)
            subLayout.draw(canvas)
            canvas.restore()

            // Plays on right side
            val playsPaint = Paint().apply {
                isAntiAlias = true
                color = 0xB3FFFFFF.toInt()
                textSize = 22f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                textAlign = Paint.Align.RIGHT
            }
            canvas.drawText("${song.playCount} plays", rowRect.right - 24f, rowRect.centerY() + 8f, playsPaint)

            currentY += 76f
        }
    }

    private fun drawTopArtists(
        canvas: Canvas,
        artists: List<TopArtistItem>,
        avatars: List<Bitmap?>,
        theme: VibeCardTheme
    ) {
        val startY = 1220f
        val sectionTitlePaint = Paint().apply {
            isAntiAlias = true
            color = Color.WHITE
            textSize = 34f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = -0.01f
        }
        canvas.drawText("Top Artists", 60f, startY + 30f, sectionTitlePaint)

        val accentColor = when (theme) {
            VibeCardTheme.AURORA -> 0xFF818CF8.toInt()
            VibeCardTheme.SUNSET -> 0xFFFB923C.toInt()
            VibeCardTheme.EMERALD -> 0xFF34D399.toInt()
        }

        val top3Artists = artists.take(3)
        val cardWidth = (CANVAS_WIDTH - 120f - 32f) / 3f
        val cardHeight = 270f
        val cardY = startY + 60f

        for (i in 0 until 3) {
            val cardLeft = 60f + i * (cardWidth + 16f)
            val cardRect = RectF(cardLeft, cardY, cardLeft + cardWidth, cardY + cardHeight)

            val artist = top3Artists.getOrNull(i)
            val avatarBitmap = avatars.getOrNull(i)

            // Card background
            val cardBgPaint = Paint().apply {
                isAntiAlias = true
                color = if (i == 0) 0x2AFFFFFF.toInt() else 0x1AFFFFFF.toInt()
            }
            val cardBorderPaint = Paint().apply {
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeWidth = if (i == 0) 2.5f else 1.5f
                color = if (i == 0) accentColor else 0x26FFFFFF.toInt()
            }
            canvas.drawRoundRect(cardRect, 28f, 28f, cardBgPaint)
            canvas.drawRoundRect(cardRect, 28f, 28f, cardBorderPaint)

            // Avatar circle
            val avatarSize = if (i == 0) 100f else 88f
            val avatarX = cardRect.centerX()
            val avatarY = cardRect.top + (if (i == 0) 70f else 64f)
            val avatarRect = RectF(
                avatarX - avatarSize / 2f,
                avatarY - avatarSize / 2f,
                avatarX + avatarSize / 2f,
                avatarY + avatarSize / 2f
            )

            if (avatarBitmap != null) {
                drawCircularBitmap(canvas, avatarBitmap, avatarRect)
            } else {
                val circlePlaceholder = Paint().apply {
                    isAntiAlias = true
                    color = if (i == 0) accentColor else 0x40FFFFFF.toInt()
                }
                canvas.drawCircle(avatarX, avatarY, avatarSize / 2f, circlePlaceholder)
                val initialPaint = Paint().apply {
                    isAntiAlias = true
                    color = Color.WHITE
                    textSize = 36f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    textAlign = Paint.Align.CENTER
                }
                val initial = artist?.name?.take(1)?.uppercase() ?: "?"
                canvas.drawText(initial, avatarX, avatarY + 13f, initialPaint)
            }

            // Rank Badge
            val rankX = avatarRect.right - 10f
            val rankY = avatarRect.bottom - 10f
            val rankBg = Paint().apply {
                isAntiAlias = true
                color = if (i == 0) accentColor else 0xFF334155.toInt()
            }
            canvas.drawCircle(rankX, rankY, 18f, rankBg)
            val rankText = Paint().apply {
                isAntiAlias = true
                color = if (i == 0) Color.BLACK else Color.WHITE
                textSize = 18f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("#${i + 1}", rankX, rankY + 6f, rankText)

            // Artist Name & Play Count
            val namePaint = TextPaint().apply {
                isAntiAlias = true
                color = Color.WHITE
                textSize = if (i == 0) 24f else 22f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            val artistName = artist?.name ?: "—"
            val textLayout = createStaticLayout(artistName, namePaint, (cardWidth - 20f).toInt(), 2, Layout.Alignment.ALIGN_CENTER)
            canvas.save()
            canvas.translate(cardRect.centerX(), cardRect.top + 134f)
            textLayout.draw(canvas)
            canvas.restore()

            if (artist != null) {
                val playsPaint = Paint().apply {
                    isAntiAlias = true
                    color = 0x99FFFFFF.toInt()
                    textSize = 18f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                    textAlign = Paint.Align.CENTER
                }
                canvas.drawText("${artist.playCount} plays", cardRect.centerX(), cardRect.bottom - 20f, playsPaint)
            }
        }
    }

    private fun drawFooter(canvas: Canvas, theme: VibeCardTheme) {
        val y = 1730f

        // Separator line
        val linePaint = Paint().apply {
            isAntiAlias = true
            strokeWidth = 1.5f
            shader = LinearGradient(
                60f, y, CANVAS_WIDTH - 60f, y,
                intArrayOf(0x00FFFFFF, 0x33FFFFFF.toInt(), 0x00FFFFFF),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawLine(60f, y, CANVAS_WIDTH - 60f, y, linePaint)

        // Branding
        val brandPaint = Paint().apply {
            isAntiAlias = true
            color = Color.WHITE
            textSize = 28f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("🎵 VIBE MUSIC", CANVAS_WIDTH / 2f, y + 60f, brandPaint)

        val tagPaint = Paint().apply {
            isAntiAlias = true
            color = 0x80FFFFFF.toInt()
            textSize = 20f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("Every beat. Every memory. Crafted for your ears.", CANVAS_WIDTH / 2f, y + 96f, tagPaint)
    }

    private fun drawRoundedBitmap(canvas: Canvas, bitmap: Bitmap, dstRect: RectF, radius: Float) {
        val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
        }
        val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val matrix = Matrix()
        val scale = maxOf(dstRect.width() / bitmap.width, dstRect.height() / bitmap.height)
        val dx = dstRect.left + (dstRect.width() - bitmap.width * scale) / 2f
        val dy = dstRect.top + (dstRect.height() - bitmap.height * scale) / 2f
        matrix.setScale(scale, scale)
        matrix.postTranslate(dx, dy)
        shader.setLocalMatrix(matrix)
        paint.shader = shader
        canvas.drawRoundRect(dstRect, radius, radius, paint)
    }

    private fun drawCircularBitmap(canvas: Canvas, bitmap: Bitmap, dstRect: RectF) {
        val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
        }
        val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val matrix = Matrix()
        val scale = maxOf(dstRect.width() / bitmap.width, dstRect.height() / bitmap.height)
        val dx = dstRect.left + (dstRect.width() - bitmap.width * scale) / 2f
        val dy = dstRect.top + (dstRect.height() - bitmap.height * scale) / 2f
        matrix.setScale(scale, scale)
        matrix.postTranslate(dx, dy)
        shader.setLocalMatrix(matrix)
        paint.shader = shader
        canvas.drawCircle(dstRect.centerX(), dstRect.centerY(), dstRect.width() / 2f, paint)
    }

    private fun createStaticLayout(
        text: CharSequence,
        paint: TextPaint,
        width: Int,
        maxLines: Int,
        alignment: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL
    ): StaticLayout {
        val safeWidth = width.coerceAtLeast(1)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(text, 0, text.length, paint, safeWidth)
                .setAlignment(alignment)
                .setLineSpacing(0f, 1f)
                .setIncludePad(false)
                .setMaxLines(maxLines)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(text, paint, safeWidth, alignment, 1f, 0f, false)
        }
    }

    fun saveBitmapToCache(context: Context, bitmap: Bitmap): Uri {
        val cacheDir = File(context.cacheDir, "images")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val imageFile = File(cacheDir, "vibe_wrapped_${System.currentTimeMillis()}.png")
        FileOutputStream(imageFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.FileProvider",
            imageFile
        )
    }

    fun saveBitmapToPictures(context: Context, bitmap: Bitmap, fileName: String = "Vibe_Wrapped_${System.currentTimeMillis()}"): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "$fileName.png")
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/VibeMusic")
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    contentValues
                ) ?: return null
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                uri
            } else {
                val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val vibeDir = File(picturesDir, "VibeMusic")
                if (!vibeDir.exists()) vibeDir.mkdirs()
                val imageFile = File(vibeDir, "$fileName.png")
                FileOutputStream(imageFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                Uri.fromFile(imageFile)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun shareWrappedImage(context: Context, uri: Uri, periodLabel: String) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(
                Intent.EXTRA_TEXT,
                "Check out my Vibe Wrapped summary for $periodLabel! 🎵 #VibeMusic #Wrapped"
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share Vibe Wrapped"))
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0L) return "0m"
        val totalMinutes = ms / 60_000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
            hours > 0 -> "${hours}h"
            else -> "${minutes}m"
        }
    }
}
