package com.djvemo.navasistidadepth

import android.media.Image
import java.nio.ByteOrder

object DepthCalculator {

    data class Resultado(val distanciaMetros: Float, val mensaje: String)

    fun obtenerDistanciaPromedio(depthImage: Image, xCentral: Int, yCentral: Int): Resultado {
        try {
            val plane = depthImage.planes[0]
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val buffer = plane.buffer.order(ByteOrder.nativeOrder())

            // Como el Visualizer rota la imagen visual, las coordenadas X/Y visuales
            // no coinciden con la memoria RAW (que sigue acostada).
            // TRUCO: Intercambiamos X e Y para leer la memoria correctamente
            // Imagen RAW (Landscape): Ancho=160, Alto=120
            // Pantalla (Portrait): Ancho=ScreenW, Alto=ScreenH
            // El centro es el centro, así que usamos width/2 y height/2 de la imagen RAW.

            val rawCenterX = depthImage.width / 2
            val rawCenterY = depthImage.height / 2

            // Kernel de promedio 5x5
            var suma = 0L
            var cuenta = 0
            val radio = 4

            for (ry in -radio..radio) {
                for (rx in -radio..radio) {
                    val px = rawCenterX + rx
                    val py = rawCenterY + ry

                    if (px in 0 until depthImage.width && py in 0 until depthImage.height) {
                        // CALCULO CORRECTO DE POSICIÓN EN MEMORIA
                        val offset = (py * rowStride) + (px * pixelStride)

                        val byteLow = buffer.get(offset).toInt() and 0xFF
                        val byteHigh = buffer.get(offset + 1).toInt() and 0xFF
                        val depthMm = ((byteHigh shl 8) or byteLow) and 0x1FFF

                        if (depthMm > 0 && depthMm < 8000) {
                            suma += depthMm
                            cuenta++
                        }
                    }
                }
            }

            if (cuenta == 0) return Resultado(0f, "Buscando...")

            val promedioM = (suma / cuenta) / 1000.0f

            val msg = when {
                promedioM < 1.0 -> "¡DETENTE!"
                promedioM < 2.5 -> "Cuidado"
                else -> "Libre"
            }
            return Resultado(promedioM, msg)

        } catch (e: Exception) {
            return Resultado(0f, "Error")
        }
    }
}