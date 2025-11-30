package com.djvemo.smartmoves.ml

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Canvas
import androidx.compose.ui.platform.LocalDensity

@Composable
fun Overlay(
    detections: List<DetectionResult>,
    inferenceMs: Long
) {
    val density = LocalDensity.current

    Box(Modifier.fillMaxSize()) {

        // 1) Dibujar máscaras primero (quedarán debajo de cajas/textos)
        detections.forEach { det ->
            det.maskBitmap?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // 2) Cajas en Canvas (solo Compose APIs)
        Canvas(Modifier.fillMaxSize()) {
            detections.forEach { det ->
                drawRect(
                    color = Color.White,
                    topLeft = Offset(det.rect.left, det.rect.top),
                    size = Size(det.rect.width(), det.rect.height()),
                    style = Stroke(width = 3f)
                )
            }
        }

        // 3) Etiquetas (chips) por encima de cada caja
        detections.forEach { det ->
            val label = "${det.clsName} ${(det.score * 100f).toInt()}%"

            // Posición por píxel -> dp
            val xDp = with(density) { det.rect.left.toDp() }
            // Un poco por encima de la caja, pero no menos de 0
            val yPx = (det.rect.top - 26f).coerceAtLeast(0f)
            val yDp = with(density) { yPx.toDp() }

            // Un chip simple con fondo semitransparente
            Box(
                modifier = Modifier
                    .offset(x = xDp, y = yDp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0x80000000))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(text = label, color = Color.White, fontSize = 12.sp)
            }
        }

        // 4) Latencia total (ms) arriba a la izquierda
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0x80000000))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = "Latency: ${inferenceMs} ms",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
