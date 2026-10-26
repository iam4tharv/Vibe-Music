package com.music.vibe.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import java.util.Calendar

/**
 * Returns a conversational, time-of-day greeting for the home screen across 15 diurnal sessions,
 * incorporating the user's first name (or "Listener" when signed out / unnamed).
 */
@Composable
fun rememberHomeGreeting(accountName: String?): String {
    val firstName = remember(accountName) {
        accountName?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.takeIf { it.isNotBlank() } ?: "Listener"
    }

    var greeting by remember(firstName) {
        mutableStateOf(resolveHomeGreeting(firstName))
    }

    LaunchedEffect(firstName) {
        while (true) {
            greeting = resolveHomeGreeting(firstName)
            // Re-check every minute to smoothly cross session boundaries
            delay(60_000L)
        }
    }

    return greeting
}

/**
 * Maps the minute of the day (0..1439) to one of 15 contiguous time-session greetings.
 */
fun resolveHomeGreeting(
    name: String,
    minuteOfDay: Int = run {
        val cal = Calendar.getInstance()
        cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    }
): String {
    return when (minuteOfDay) {
        in 0..89 -> "Night owl huh, $name?"           // 00:00 - 01:29 (Midnight / Late Night)
        in 90..209 -> "Up pretty late, $name."        // 01:30 - 03:29 (Deep Night / Witching Hour)
        in 210..299 -> "Can't sleep, $name?"          // 03:30 - 04:59 (Pre-Dawn Quiet)
        in 300..389 -> "Early riser, $name!"          // 05:00 - 06:29 (Dawn / First Light)
        in 390..479 -> "Rise and shine, $name!"       // 06:30 - 07:59 (Early Morning)
        in 480..569 -> "Good morning, $name."         // 08:00 - 09:29 (Morning Commute / Breakfast)
        in 570..689 -> "Locking in, $name?"           // 09:30 - 11:29 (Mid-Morning Focus)
        in 690..779 -> "Midday tunes, $name."         // 11:30 - 12:59 (Midday / Lunch)
        in 780..869 -> "Afternoon slump, $name?"      // 13:00 - 14:29 (Post-Lunch Slump)
        in 870..989 -> "Powering through, $name!"     // 14:30 - 16:29 (Afternoon Push)
        in 990..1079 -> "Unwinding time, $name."      // 16:30 - 17:59 (Golden Hour / Sunset)
        in 1080..1169 -> "Good evening, $name."       // 18:00 - 19:29 (Early Evening)
        in 1170..1259 -> "Evening soundtrack, $name." // 19:30 - 20:59 (Prime Evening / Dinner)
        in 1260..1349 -> "Winding down, $name?"       // 21:00 - 22:29 (Late Evening Chill)
        in 1350..1439 -> "Late vibes, $name."         // 22:30 - 23:59 (Nightfall)
        else -> "Night owl huh, $name?"
    }
}
