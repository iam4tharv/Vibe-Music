package com.music.echo.ui.component

import androidx.compose.ui.unit.Dp

enum class BottomSheetTargetState {
    EXPANDED, COLLAPSED, DISMISSED
}

object BottomSheetMath {
    fun calculateTargetState(
        velocity: Float,
        currentValue: Dp,
        collapsedBound: Dp,
        expandedBound: Dp,
        hasDismiss: Boolean
    ): BottomSheetTargetState {
        return if (velocity > 250) {
            BottomSheetTargetState.EXPANDED
        } else if (velocity < -250) {
            if (currentValue < collapsedBound && hasDismiss) {
                BottomSheetTargetState.DISMISSED
            } else {
                BottomSheetTargetState.COLLAPSED
            }
        } else {
            val expandPoint = (expandedBound - collapsedBound) / 2 + collapsedBound
            if (currentValue > expandPoint) {
                BottomSheetTargetState.EXPANDED
            } else {
                BottomSheetTargetState.COLLAPSED
            }
        }
    }
}
