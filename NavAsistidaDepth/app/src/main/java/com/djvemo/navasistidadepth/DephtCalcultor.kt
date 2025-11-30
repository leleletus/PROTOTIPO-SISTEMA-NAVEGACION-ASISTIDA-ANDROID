package com.djvemo.navasistidadepth

import android.media.Image

/**
 * Módulo de Matemáticas para la Tesis.
 * Versión Final - Limpia
 */
object DepthCalculator {

    private const val RANGO_PELIGRO = 1.0f
    private const val RANGO_CERCANO = 3.0f

    data class Resultado(
        val distanciaMetros: Float,
        val mensaje: String
    )

    fun obtenerDistancia(depthImage: Image, x: Int, y: Int): Resultado {
        try {
            if (x < 0 || x >= depthImage.width || y < 0 || y >= depthImage.height) {
                return Resultado(0f, "Fuera de rango")
            }

            val plane = depthImage.planes[0]
            val byteBuffer = plane.buffer.asShortBuffer()
            val index = (y * depthImage.width) + x
            val rawDepth = byteBuffer.get(index).toInt()

            // Mascara 0x1FFF para obtener solo los 13 bits de distancia
            val depthMillimeters = rawDepth and 0x1FFF
            val distanciaMetros = depthMillimeters / 1000.0f

            if (distanciaMetros == 0f) {
                return Resultado(0f, "Calculando...")
            }

            val texto = when {
                distanciaMetros < RANGO_PELIGRO -> "¡PELIGRO! MUY CERCA"
                distanciaMetros < RANGO_CERCANO -> "Atención: Objeto Cercano"
                else -> "Despejado (Lejano)"
            }

            return Resultado(distanciaMetros, texto)

        } catch (_: Exception) {
            // El guion bajo silencia el warning correctamente
            return Resultado(0f, "Error de lectura")
        }
    }
}