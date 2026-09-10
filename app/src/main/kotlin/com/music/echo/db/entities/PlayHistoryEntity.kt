package com.music.echo.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "play_history")
data class PlayHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: String,          
    val title: String,
    val artist: String,
    val album: String? = null,
    val language: String? = null,   
    val genre: String? = null,      
    val source: String? = null,     
    val playedAt: Long = System.currentTimeMillis(),
    val wasSkippedEarly: Boolean = false 
)
