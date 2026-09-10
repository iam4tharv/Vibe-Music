package com.music.echo.ui.component

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class BottomSheetMathTest {

    @Test
    fun testCalculateTargetState_velocityUp_expands() {
        val target = BottomSheetMath.calculateTargetState(
            velocity = 300f,
            currentValue = 100.dp,
            collapsedBound = 50.dp,
            expandedBound = 200.dp,
            hasDismiss = true
        )
        assertEquals(BottomSheetTargetState.EXPANDED, target)
    }

    @Test
    fun testCalculateTargetState_velocityDown_currentBelowCollapsed_withDismiss_dismisses() {
        val target = BottomSheetMath.calculateTargetState(
            velocity = -300f,
            currentValue = 40.dp,
            collapsedBound = 50.dp,
            expandedBound = 200.dp,
            hasDismiss = true
        )
        assertEquals(BottomSheetTargetState.DISMISSED, target)
    }

    @Test
    fun testCalculateTargetState_velocityDown_currentAboveCollapsed_collapses() {
        val target = BottomSheetMath.calculateTargetState(
            velocity = -300f,
            currentValue = 60.dp,
            collapsedBound = 50.dp,
            expandedBound = 200.dp,
            hasDismiss = true
        )
        assertEquals(BottomSheetTargetState.COLLAPSED, target)
    }

    @Test
    fun testCalculateTargetState_lowVelocity_aboveMidpoint_expands() {
        val target = BottomSheetMath.calculateTargetState(
            velocity = 10f,
            currentValue = 150.dp,
            collapsedBound = 50.dp,
            expandedBound = 200.dp,
            hasDismiss = true
        )
        assertEquals(BottomSheetTargetState.EXPANDED, target)
    }
    
    @Test
    fun testCalculateTargetState_lowVelocity_belowMidpoint_collapses() {
        val target = BottomSheetMath.calculateTargetState(
            velocity = -10f,
            currentValue = 100.dp,
            collapsedBound = 50.dp,
            expandedBound = 200.dp,
            hasDismiss = true
        )
        assertEquals(BottomSheetTargetState.COLLAPSED, target)
    }
}
