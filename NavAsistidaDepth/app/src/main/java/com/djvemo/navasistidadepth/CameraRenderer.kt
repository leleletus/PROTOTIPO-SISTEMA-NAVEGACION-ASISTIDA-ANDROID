package com.djvemo.navasistidadepth

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class CameraRenderer {
    private var textureId: Int = -1
    private var quadProgram: Int = 0
    private var quadPositionParam: Int = 0
    private var quadTexCoordParam: Int = 0

    // Buffers: Ahora AMBOS son de 2 componentes (X, Y)
    private val quadVertices: FloatBuffer
    private val quadTexCoord: FloatBuffer

    init {
        // Coordenadas 2D (X, Y) - 8 valores en total (4 puntos x 2)
        val quadCoords = floatArrayOf(
            -1.0f, -1.0f,  // Abajo-Izq
            -1.0f, +1.0f,  // Arriba-Izq
            +1.0f, -1.0f,  // Abajo-Der
            +1.0f, +1.0f   // Arriba-Der
        )
        // 8 floats * 4 bytes = 32 bytes
        quadVertices = ByteBuffer.allocateDirect(quadCoords.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        quadVertices.put(quadCoords).position(0)

        // Coordenadas de textura (U, V) - 8 valores
        val texCoords = floatArrayOf(0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f, 0.0f)
        quadTexCoord = ByteBuffer.allocateDirect(texCoords.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        quadTexCoord.put(texCoords).position(0)
    }

    fun getTextureId(): Int = textureId

    fun updateTextureCoordinates(newCoords: FloatBuffer) {
        newCoords.position(0)
        quadTexCoord.position(0)
        quadTexCoord.put(newCoords)
        quadTexCoord.position(0)
    }

    fun createOnGlThread(context: Context) {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)

        quadProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(quadProgram, vertexShader)
        GLES20.glAttachShader(quadProgram, fragmentShader)
        GLES20.glLinkProgram(quadProgram)

        quadPositionParam = GLES20.glGetAttribLocation(quadProgram, "a_Position")
        quadTexCoordParam = GLES20.glGetAttribLocation(quadProgram, "a_TexCoord")
    }

    fun draw() {
        if (textureId == -1) return
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glUseProgram(quadProgram)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        // IMPORTANTE: El '2' aquí indica que son 2 coordenadas por vértice (X, Y)
        GLES20.glVertexAttribPointer(quadPositionParam, 2, GLES20.GL_FLOAT, false, 0, quadVertices)
        GLES20.glVertexAttribPointer(quadTexCoordParam, 2, GLES20.GL_FLOAT, false, 0, quadTexCoord)

        GLES20.glEnableVertexAttribArray(quadPositionParam)
        GLES20.glEnableVertexAttribArray(quadTexCoordParam)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(quadPositionParam)
        GLES20.glDisableVertexAttribArray(quadTexCoordParam)
        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        return shader
    }

    companion object {
        // En el Shader, a_Position ahora espera vec2 o vec4 (se adapta), pero nosotros enviamos 2.
        private const val VERTEX_SHADER =
            "attribute vec4 a_Position;\n" +
                    "attribute vec2 a_TexCoord;\n" +
                    "varying vec2 v_TexCoord;\n" +
                    "void main() {\n" +
                    "   gl_Position = a_Position;\n" +
                    "   v_TexCoord = a_TexCoord;\n" +
                    "}"

        private const val FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
                    "precision mediump float;\n" +
                    "varying vec2 v_TexCoord;\n" +
                    "uniform samplerExternalOES s_Texture;\n" +
                    "void main() {\n" +
                    "    gl_FragColor = texture2D(s_Texture, v_TexCoord);\n" +
                    "}"
    }
}