package com.music.echo.ui.utils

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavBackStackEntry
import com.music.echo.ui.screens.Screens

/**
 * Unified Navigation Animation Strategy for fluid, flagship-grade motion design.
 * Provides physics-based spring curves, contextual route-aware transitions (top-level cross-fades,
 * hierarchical slide & parallax scaling, modal presentations, and search expansions).
 */
object NavigationAnimationStrategy {

    // Spring specifications calibrated for ultra-smooth fluid response
    val MotionSpring = spring<IntOffset>(
        dampingRatio = 0.88f,
        stiffness = 380f
    )

    val ModalMotionSpring = spring<IntOffset>(
        dampingRatio = 0.86f,
        stiffness = 400f
    )

    val ScaleSpring = spring<Float>(
        dampingRatio = 0.88f,
        stiffness = 380f
    )

    val FadeSpring = spring<Float>(
        dampingRatio = 0.92f,
        stiffness = 420f
    )

    val TopLevelScaleSpring = spring<Float>(
        dampingRatio = 0.92f,
        stiffness = 420f
    )

    // Parallax depth ratio for receding / entering parent screens
    const val PARALLAX_FACTOR = 0.22f
    const val SCALE_IN_FACTOR = 0.94f
    const val SCALE_OUT_FACTOR = 0.94f
    const val TOP_LEVEL_SCALE_IN = 0.98f
    const val TOP_LEVEL_SCALE_OUT = 1.02f

    val DefaultTopLevelRoutes: Set<String> = setOf(
        Screens.Home.route,
        Screens.Search.route,
        Screens.Library.route,
        Screens.ListenTogether.route,
        "explore"
    )

    val ModalRoutes: Set<String> = setOf(
        "ambient_mode",
        "update",
        "login",
        "recognition",
        "settings/spotify_import",
        "settings/equalizer",
        "settings/autoeq"
    )

    fun isTopLevelTransition(
        initialRoute: String?,
        targetRoute: String?,
        topLevelRoutes: Collection<String> = DefaultTopLevelRoutes
    ): Boolean {
        return topLevelRoutes.any { it == initialRoute } && topLevelRoutes.any { it == targetRoute }
    }

    fun isModalRoute(route: String?): Boolean {
        if (route == null) return false
        return ModalRoutes.any { route.startsWith(it) }
    }
}

/**
 * Standard Enter Transition for NavHost:
 * - Top-level tabs: Cross-fade with subtle scale-in (0.98 -> 1.0)
 * - Modal destinations: Slide up from bottom with spring fade
 * - Hierarchical destinations: Slide in from right with fade and scale-in
 */
fun AnimatedContentTransitionScope<NavBackStackEntry>.appEnterTransition(
    topLevelRoutes: Collection<String> = NavigationAnimationStrategy.DefaultTopLevelRoutes
): EnterTransition {
    val initialRoute = initialState.destination.route
    val targetRoute = targetState.destination.route

    return when {
        NavigationAnimationStrategy.isTopLevelTransition(initialRoute, targetRoute, topLevelRoutes) -> {
            fadeIn(animationSpec = NavigationAnimationStrategy.FadeSpring) +
            scaleIn(
                initialScale = NavigationAnimationStrategy.TOP_LEVEL_SCALE_IN,
                animationSpec = NavigationAnimationStrategy.TopLevelScaleSpring
            )
        }
        NavigationAnimationStrategy.isModalRoute(targetRoute) -> {
            slideIntoContainer(
                towards = SlideDirection.Up,
                animationSpec = NavigationAnimationStrategy.ModalMotionSpring
            ) + fadeIn(animationSpec = NavigationAnimationStrategy.FadeSpring) +
            scaleIn(
                initialScale = NavigationAnimationStrategy.SCALE_IN_FACTOR,
                animationSpec = NavigationAnimationStrategy.ScaleSpring
            )
        }
        else -> {
            slideIntoContainer(
                towards = SlideDirection.Left,
                animationSpec = NavigationAnimationStrategy.MotionSpring
            ) + fadeIn(animationSpec = NavigationAnimationStrategy.FadeSpring)
        }
    }
}

/**
 * Standard Exit Transition for NavHost:
 * - Top-level tabs: Cross-fade with subtle scale-out (1.0 -> 1.02)
 * - Opening modal: Recede with slight scale-out and fade
 * - Hierarchical destinations: Recede left with 22% parallax offset, scale-out, and fade-out
 */
fun AnimatedContentTransitionScope<NavBackStackEntry>.appExitTransition(
    topLevelRoutes: Collection<String> = NavigationAnimationStrategy.DefaultTopLevelRoutes
): ExitTransition {
    val initialRoute = initialState.destination.route
    val targetRoute = targetState.destination.route

    return when {
        NavigationAnimationStrategy.isTopLevelTransition(initialRoute, targetRoute, topLevelRoutes) -> {
            fadeOut(animationSpec = NavigationAnimationStrategy.FadeSpring) +
            scaleOut(
                targetScale = NavigationAnimationStrategy.TOP_LEVEL_SCALE_OUT,
                animationSpec = NavigationAnimationStrategy.TopLevelScaleSpring
            )
        }
        NavigationAnimationStrategy.isModalRoute(targetRoute) -> {
            fadeOut(animationSpec = NavigationAnimationStrategy.FadeSpring) +
            scaleOut(
                targetScale = NavigationAnimationStrategy.SCALE_OUT_FACTOR,
                animationSpec = NavigationAnimationStrategy.ScaleSpring
            )
        }
        else -> {
            slideOutOfContainer(
                towards = SlideDirection.Left,
                targetOffset = { (it * NavigationAnimationStrategy.PARALLAX_FACTOR).toInt() },
                animationSpec = NavigationAnimationStrategy.MotionSpring
            ) + fadeOut(animationSpec = NavigationAnimationStrategy.FadeSpring) +
            scaleOut(
                targetScale = NavigationAnimationStrategy.SCALE_OUT_FACTOR,
                animationSpec = NavigationAnimationStrategy.ScaleSpring
            )
        }
    }
}

/**
 * Standard Pop Enter Transition for NavHost:
 * - Returning from modal: Fade in with subtle zoom back to normal scale
 * - Standard back navigation: Slide in from left with parallax entry offset (22%), scale-in, and fade-in
 */
fun AnimatedContentTransitionScope<NavBackStackEntry>.appPopEnterTransition(
    topLevelRoutes: Collection<String> = NavigationAnimationStrategy.DefaultTopLevelRoutes
): EnterTransition {
    val initialRoute = initialState.destination.route
    val targetRoute = targetState.destination.route

    return when {
        NavigationAnimationStrategy.isTopLevelTransition(initialRoute, targetRoute, topLevelRoutes) -> {
            fadeIn(animationSpec = NavigationAnimationStrategy.FadeSpring) +
            scaleIn(
                initialScale = NavigationAnimationStrategy.TOP_LEVEL_SCALE_IN,
                animationSpec = NavigationAnimationStrategy.TopLevelScaleSpring
            )
        }
        NavigationAnimationStrategy.isModalRoute(initialRoute) -> {
            fadeIn(animationSpec = NavigationAnimationStrategy.FadeSpring) +
            scaleIn(
                initialScale = NavigationAnimationStrategy.SCALE_IN_FACTOR,
                animationSpec = NavigationAnimationStrategy.ScaleSpring
            )
        }
        else -> {
            slideIntoContainer(
                towards = SlideDirection.Right,
                initialOffset = { (it * NavigationAnimationStrategy.PARALLAX_FACTOR).toInt() },
                animationSpec = NavigationAnimationStrategy.MotionSpring
            ) + fadeIn(animationSpec = NavigationAnimationStrategy.FadeSpring) +
            scaleIn(
                initialScale = NavigationAnimationStrategy.SCALE_IN_FACTOR,
                animationSpec = NavigationAnimationStrategy.ScaleSpring
            )
        }
    }
}

/**
 * Standard Pop Exit Transition for NavHost:
 * - Closing modal: Slide down to bottom with spring fade & scale
 * - Standard back navigation: Slide right out of container with spring fade
 */
fun AnimatedContentTransitionScope<NavBackStackEntry>.appPopExitTransition(
    topLevelRoutes: Collection<String> = NavigationAnimationStrategy.DefaultTopLevelRoutes
): ExitTransition {
    val initialRoute = initialState.destination.route

    return when {
        NavigationAnimationStrategy.isModalRoute(initialRoute) -> {
            slideOutOfContainer(
                towards = SlideDirection.Down,
                animationSpec = NavigationAnimationStrategy.ModalMotionSpring
            ) + fadeOut(animationSpec = NavigationAnimationStrategy.FadeSpring) +
            scaleOut(
                targetScale = NavigationAnimationStrategy.SCALE_OUT_FACTOR,
                animationSpec = NavigationAnimationStrategy.ScaleSpring
            )
        }
        else -> {
            slideOutOfContainer(
                towards = SlideDirection.Right,
                animationSpec = NavigationAnimationStrategy.MotionSpring
            ) + fadeOut(animationSpec = NavigationAnimationStrategy.FadeSpring)
        }
    }
}

/**
 * Search Results Transitions (smooth shared axis and fade expansion)
 */
fun AnimatedContentTransitionScope<NavBackStackEntry>.searchResultEnter(): EnterTransition {
    return fadeIn(animationSpec = NavigationAnimationStrategy.FadeSpring) +
           scaleIn(
               initialScale = 0.96f,
               animationSpec = NavigationAnimationStrategy.ScaleSpring
           )
}

fun AnimatedContentTransitionScope<NavBackStackEntry>.searchResultExit(): ExitTransition {
    val targetIsSearch = targetState.destination.route?.startsWith("search") == true
    return if (targetIsSearch) {
        fadeOut(animationSpec = NavigationAnimationStrategy.FadeSpring)
    } else {
        slideOutOfContainer(
            towards = SlideDirection.Left,
            targetOffset = { (it * NavigationAnimationStrategy.PARALLAX_FACTOR).toInt() },
            animationSpec = NavigationAnimationStrategy.MotionSpring
        ) + fadeOut(animationSpec = NavigationAnimationStrategy.FadeSpring)
    }
}

fun AnimatedContentTransitionScope<NavBackStackEntry>.searchResultPopEnter(): EnterTransition {
    val fromSearch = initialState.destination.route?.startsWith("search") == true
    return if (fromSearch) {
        fadeIn(animationSpec = NavigationAnimationStrategy.FadeSpring)
    } else {
        slideIntoContainer(
            towards = SlideDirection.Right,
            initialOffset = { (it * NavigationAnimationStrategy.PARALLAX_FACTOR).toInt() },
            animationSpec = NavigationAnimationStrategy.MotionSpring
        ) + fadeIn(animationSpec = NavigationAnimationStrategy.FadeSpring)
    }
}

fun AnimatedContentTransitionScope<NavBackStackEntry>.searchResultPopExit(): ExitTransition {
    return fadeOut(animationSpec = NavigationAnimationStrategy.FadeSpring) +
           scaleOut(
               targetScale = 0.96f,
               animationSpec = NavigationAnimationStrategy.ScaleSpring
           )
}
