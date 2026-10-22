package com.dynamictransport.dynamicone.feature.hrdocuments

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dynamictransport.dynamicone.core.design.theme.DriverTheme
import com.dynamictransport.dynamicone.domain.model.SignaturePoint
import com.dynamictransport.dynamicone.domain.rules.HrDocumentRules

private val PadShape = RoundedCornerShape(14.dp)

/**
 * A box to sign in with the finger. Each finished stroke goes to [onStroke] as points relative to the box (0..1); the
 * box keeps the server's width/height ratio. Touches inside the box never scroll the screen.
 */
@Composable
fun SignaturePad(
    strokes: List<List<SignaturePoint>>,
    onStroke: (List<SignaturePoint>) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val current = remember { mutableStateListOf<SignaturePoint>() }
    val latestStrokes by rememberUpdatedState(strokes)
    val latestOnStroke by rememberUpdatedState(onStroke)
    val ink = MaterialTheme.colorScheme.onSurface
    val guide = DriverTheme.colors.muted
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(HrDocumentRules.PAD_ASPECT_RATIO)
            .clip(PadShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .border(BorderStroke(2.dp, MaterialTheme.colorScheme.outline), PadShape)
            .semantics { contentDescription = description }
            .testTag("hr_signature_pad"),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        current.clear()
                        val canDraw = HrDocumentRules.canStartStroke(latestStrokes)
                        fun add(position: Offset) {
                            if (!canDraw || HrDocumentRules.pointsLeft(latestStrokes, current.size) <= 0) return
                            val point = HrDocumentRules.nextPoint(current.lastOrNull(), position.x / size.width, position.y / size.height)
                            if (point != null) current.add(point)
                        }
                        add(down.position)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            change.historical.forEach { add(it.position) }
                            add(change.position)
                            change.consume()
                            if (!change.pressed) break
                        }
                        val stroke = current.toList()
                        current.clear()
                        // A tap leaves a dot, which the server does not count as a stroke.
                        if (stroke.size >= HrDocumentRules.MIN_POINTS_PER_STROKE) latestOnStroke(stroke)
                    }
                },
        ) {
            // A signature line helps to sign at a readable size.
            val lineY = size.height * 0.78f
            drawLine(
                color = guide,
                start = Offset(size.width * 0.06f, lineY),
                end = Offset(size.width * 0.94f, lineY),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())),
            )
            strokes.forEach { drawStroke(it, ink) }
            drawStroke(current, ink)
        }
    }
}

private fun DrawScope.drawStroke(points: List<SignaturePoint>, color: Color) {
    if (points.isEmpty()) return
    val path = Path().apply {
        moveTo(points.first().x * size.width, points.first().y * size.height)
        points.drop(1).forEach { lineTo(it.x * size.width, it.y * size.height) }
    }
    drawPath(path, color, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}
