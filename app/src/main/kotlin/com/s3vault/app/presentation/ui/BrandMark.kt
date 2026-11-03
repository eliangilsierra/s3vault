package com.s3vault.app.presentation.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp

/** The lower half of the mark. The upper half is the theme's primary colour (teal in light, aqua in dark). */
private val BrandSea = Color(0xFF2B9CA2)

// The mark is an S made of two halves that meet on the vertical axis: the upper one is "this device", the lower one is "your S3".
// Drawn in a 224 x 392 box whose origin is (144, 60) in the 512-unit master the icon is built from, so the in-app mark and the
// launcher icon share one geometry.
private const val UPPER = "M 341.8,100.01 A 112,112 0 1 0 256,284 L 256,228 A 56,56 0 1 1 298.9,136 Z"
private const val LOWER = "M 170.2,411.99 A 112,112 0 1 0 256,228 L 256,284 A 56,56 0 1 1 213.1,376 Z"
private const val BOX_W = 224f
private const val BOX_H = 392f
private const val ORIGIN_X = 144f
private const val ORIGIN_Y = 60f

/**
 * The S3 Vault mark. Decorative: it carries no semantics, so the title or name next to it is what a screen reader announces.
 * [height] is the height of the mark; the width follows (224 : 392).
 */
@Composable
fun BrandMark(
    height: Dp,
    modifier: Modifier = Modifier,
    upper: Color = MaterialTheme.colorScheme.primary,
    lower: Color = BrandSea,
) {
    val upperPath = remember { PathParser().parsePathString(UPPER).toPath() }
    val lowerPath = remember { PathParser().parsePathString(LOWER).toPath() }
    Canvas(modifier.size(width = height * (BOX_W / BOX_H), height = height)) {
        val scale = size.height / BOX_H
        withTransform({
            scale(scale, scale, pivot = Offset.Zero)
            translate(-ORIGIN_X, -ORIGIN_Y)
        }) {
            drawPath(upperPath, upper)
            drawPath(lowerPath, lower)
        }
    }
}
