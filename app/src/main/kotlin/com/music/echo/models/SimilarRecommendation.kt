

package com.music.echo.models

import com.music.innertube.models.YTItem
import com.music.echo.db.entities.LocalItem

data class SimilarRecommendation(
    val title: LocalItem,
    val items: List<YTItem>,
)
