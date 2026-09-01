package com.rkd.audiobasics.ui

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
 
const val NAV_ANIMATION_DURATION_MS = 190

fun defaultPushTransform(): ContentTransform =
    (scaleIn(
        animationSpec = tween(NAV_ANIMATION_DURATION_MS),
        initialScale = 0.85f
    ) + fadeIn(animationSpec = tween(NAV_ANIMATION_DURATION_MS))) togetherWith
            (scaleOut(
                animationSpec = tween(NAV_ANIMATION_DURATION_MS),
                targetScale = 1.1f
            ) + fadeOut(animationSpec = tween(NAV_ANIMATION_DURATION_MS)))

fun defaultPopTransform(): ContentTransform =
    (scaleIn(
        animationSpec = tween(NAV_ANIMATION_DURATION_MS),
        initialScale = 1.1f
    ) + fadeIn(animationSpec = tween(NAV_ANIMATION_DURATION_MS))) togetherWith
            (scaleOut(
                animationSpec = tween(NAV_ANIMATION_DURATION_MS),
                targetScale = 0.85f
            ) + fadeOut(animationSpec = tween(NAV_ANIMATION_DURATION_MS)))
