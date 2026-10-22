package com.dynamictransport.dynamicone.feature.hrdocuments

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.core.pdf.PdfPageSource

/** One page of the document at the screen's width, on paper white in both themes. Tapping it opens the zoom view. */
@Composable
fun PdfPage(pages: PdfPageSource, index: Int, onZoom: () -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.hr_page_of, index + 1, pages.pageCount)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(pages.aspectRatio(index))
            .background(Color.White)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant))
            .clickable(onClickLabel = stringResource(R.string.hr_page_zoom), onClick = onZoom)
            .testTag("hr_page_$index"),
    ) {
        val widthPx = constraints.maxWidth
        val bitmap by produceState<ImageBitmap?>(null, pages, index, widthPx) { value = pages.render(index, widthPx) }
        val image = bitmap
        if (image == null) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else {
            Image(bitmap = image, contentDescription = description, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxSize())
        }
    }
}

/** A page drawn sharper and zoomable with two fingers or a double tap, so small print can be read. */
@Composable
fun PdfZoomDialog(pages: PdfPageSource, index: Int, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .testTag("hr_zoom"),
        ) {
            val widthPx = constraints.maxWidth * ZOOM_RENDER_FACTOR
            val bitmap by produceState<ImageBitmap?>(null, pages, index, widthPx) { value = pages.render(index, widthPx) }
            var scale by remember { mutableFloatStateOf(1f) }
            var offset by remember { mutableStateOf(Offset.Zero) }
            val image = bitmap
            if (image == null) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                Image(
                    bitmap = image,
                    contentDescription = stringResource(R.string.hr_page_of, index + 1, pages.pageCount),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                                offset = if (scale == 1f) Offset.Zero else offset + pan
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = {
                                    scale = if (scale > 1f) 1f else DOUBLE_TAP_ZOOM
                                    offset = Offset.Zero
                                },
                            )
                        }
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                )
            }
            Text(
                text = stringResource(R.string.hr_page_of, index + 1, pages.pageCount),
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(16.dp),
            )
            FilledIconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(8.dp)
                    .size(56.dp)
                    .testTag("hr_zoom_close"),
            ) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.hr_zoom_close))
            }
        }
    }
}

private const val ZOOM_RENDER_FACTOR = 2
private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f
