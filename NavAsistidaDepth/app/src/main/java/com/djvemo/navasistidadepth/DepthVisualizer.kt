package com.djvemo.navasistidadepth

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.media.Image
import java.nio.ByteBuffer
import java.nio.ByteOrder

object DepthVisualizer {

    private var bitmapCache: Bitmap? = null
    private var pixelsCache: IntArray? = null

    // Tabla de colores para velocidad
    private val colorLookupTable = IntArray(1501)

    // Matriz para rotar la imagen 90 grados (Portrait)
    private val matrixRotacion = Matrix().apply { postRotate(90f) }

    init {
        // Pre-cargamos colores: Rojo (Cerca) -> Azul (Lejos)
        for (i in 0..1500) {
            val metros = i / 1000f // mm a metros
            // Normalizamos hasta 4 metros
            val intensidad = (1.0f - (metros / 4.0f)).coerceIn(0f, 1f)
            colorLookupTable[i] = obtenerColorTermico(intensidad)
        }
    }

    fun generarMapaCalor(depthImage: Image): Bitmap? {
        try {
            val plane = depthImage.planes[0]
            val width = depthImage.width
            val height = depthImage.height

            // --- CORRECCIÓN DEL RUIDO (STRIDE) ---
            val rowStride = plane.rowStride // El ancho real en memoria (con huecos)
            val pixelStride = plane.pixelStride // Cuántos bytes por pixel (usualmente 2)
            val buffer = plane.buffer.order(ByteOrder.nativeOrder())

            // Preparamos cache
            if (bitmapCache == null || bitmapCache!!.width != width || bitmapCache!!.height != height) {
                bitmapCache = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                pixelsCache = IntArray(width * height)
            }
            val pixels = pixelsCache!!

            var outputIndex = 0

            // Recorremos FILA por FILA usando el rowStride correcto
            for (y in 0 until height) {
                // Calculamos dónde empieza esta fila en la memoria
                var rowOffset = y * rowStride

                for (x in 0 until width) {
                    // Leemos los 2 bytes de profundidad
                    // Nota: depth16 es Little Endian
                    val byteLow = buffer.get(rowOffset).toInt() and 0xFF
                    val byteHigh = buffer.get(rowOffset + 1).toInt() and 0xFF
                    // Combinamos bytes
                    val rawDepth = (byteHigh shl 8) or byteLow

                    // Mascara ARCore (13 bits útiles)
                    val depthMm = rawDepth and 0x1FFF

                    // Colorear
                    if (depthMm > 0 && depthMm < 1500) { // Solo hasta 1.5 metros (lookup table size)
                        pixels[outputIndex] = colorLookupTable[depthMm]
                    } else if (depthMm >= 1500 && depthMm < 4500) {
                        // Calcular al vuelo si está lejos
                        val m = depthMm / 1000f
                        val i = (1.0f - (m / 4.5f)).coerceIn(0f, 1f)
                        pixels[outputIndex] = obtenerColorTermico(i)
                    } else {
                        pixels[outputIndex] = 0x00000000 // Transparente
                    }

                    outputIndex++
                    rowOffset += pixelStride // Avanzamos al siguiente pixel
                }
            }

            // Guardamos píxeles en el bitmap crudo
            bitmapCache!!.setPixels(pixels, 0, width, 0, 0, width, height)

            // --- CORRECCIÓN DE "COSTADO" ---
            // Devolvemos un NUEVO bitmap rotado 90 grados para que se vea derecho
            // El 'true' activa el filtrado bilineal (suaviza los cuadraditos)
            return Bitmap.createBitmap(
                bitmapCache!!,
                0, 0,
                width, height,
                matrixRotacion,
                true
            )

        } catch (e: Exception) {
            return null
        }
    }

    private fun obtenerColorTermico(valor: Float): Int {
        val r: Int; val g: Int; val b: Int
        // Espectro visible simple
        if (valor < 0.33f) { // Azul a Verde
            r = 0; g = (255 * (valor * 3)).toInt(); b = 255 - g
        } else if (valor < 0.66f) { // Verde a Amarillo
            r = (255 * ((valor - 0.33f) * 3)).toInt(); g = 255; b = 0
        } else { // Amarillo a Rojo
            r = 255; g = 255 - (255 * ((valor - 0.66f) * 3)).toInt(); b = 0
        }
        // Alpha 0xB0 (Semi transparente)
        return Color.argb(0xB0, r.coerceIn(0,255), g.coerceIn(0,255), b.coerceIn(0,255))
    }
}