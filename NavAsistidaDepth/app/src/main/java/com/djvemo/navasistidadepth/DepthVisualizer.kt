package com.djvemo.navasistidadepth

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.media.Image
import java.nio.ShortBuffer

object DepthVisualizer {

    private var bitmapCache: Bitmap? = null
    private var pixelsCache: IntArray? = null

    // Matriz para rotar la imagen (porque el sensor viene echado)
    private val matrixRotacion = Matrix().apply { postRotate(90f) }

    fun generarMapaCalorSuave(depthImage: Image): Bitmap? {
        try {
            val width = depthImage.width
            val height = depthImage.height
            val plane = depthImage.planes[0]
            val buffer: ShortBuffer = plane.buffer.asShortBuffer()

            // Preparamos caches
            if (bitmapCache == null || bitmapCache!!.width != width || bitmapCache!!.height != height) {
                bitmapCache = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                pixelsCache = IntArray(width * height)
            }

            val pixels = pixelsCache!!

            // Recorremos PÍXEL POR PÍXEL (Sin bloques, máxima resolución)
            val totalPixels = width * height

            for (i in 0 until totalPixels) {
                if (i >= buffer.capacity()) break

                // Obtenemos profundidad en milímetros
                val rawDepth = buffer.get(i).toInt() and 0x1FFF
                val metros = rawDepth / 1000.0f

                // --- LÓGICA DE COLOR TÉRMICO (Estilo Unity / Predator) ---
                pixels[i] = if (metros > 0 && metros < 4.0f) {
                    // Normalizamos: 0m = 1.0 (Cerca), 4m = 0.0 (Lejos)
                    val intensidad = (1.0f - (metros / 4.0f)).coerceIn(0f, 1f)
                    obtenerColorTermico(intensidad)
                } else {
                    0x00000000 // Transparente si está muy lejos o error
                }
            }

            // Guardamos los píxeles en el bitmap pequeño
            bitmapCache!!.setPixels(pixels, 0, width, 0, 0, width, height)

            // TRUCO DE CALIDAD:
            // Creamos un bitmap rotado y ACTIVAMOS EL FILTRO (el 'true' del final).
            // Esto hace que Android suavice los píxeles automáticamente.
            return Bitmap.createBitmap(
                bitmapCache!!,
                0, 0,
                width, height,
                matrixRotacion,
                true
            )

        } catch (_: Exception) {
            return null
        }
    }

    /**
     * Función que convierte un valor 0..1 en un color Térmico
     * 1.0 (Cerca) -> Rojo Fuego
     * 0.5 (Medio) -> Verde/Amarillo
     * 0.0 (Lejos) -> Azul Oscuro
     */
    private fun obtenerColorTermico(valor: Float): Int {
        val r: Int
        val g: Int
        val b: Int

        // Algoritmo simple de "Heatmap"
        if (valor < 0.25f) { // Azul a Cian
            r = 0
            g = (4 * 255 * valor).toInt()
            b = 255
        } else if (valor < 0.5f) { // Cian a Verde
            r = 0
            g = 255
            b = (255 - 4 * 255 * (valor - 0.25f)).toInt()
        } else if (valor < 0.75f) { // Verde a Amarillo
            r = (4 * 255 * (valor - 0.5f)).toInt()
            g = 255
            b = 0
        } else { // Amarillo a Rojo
            r = 255
            g = (255 - 4 * 255 * (valor - 0.75f)).toInt()
            b = 0
        }

        // Alpha 0xD0 (bastante visible pero deja ver un poco el fondo)
        return Color.argb(0xD0, r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
    }
}