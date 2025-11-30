package com.djvemo.navasistidadepth

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.LinkedList
import java.util.Locale
import java.util.Queue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MainActivity : AppCompatActivity(), GLSurfaceView.Renderer {

    private lateinit var surfaceView: GLSurfaceView
    private lateinit var tvDistancia: TextView
    private lateinit var tvLatencia: TextView
    private lateinit var ivMapaCalor: ImageView

    private var session: Session? = null
    private var installRequested = false
    private var esSoportado = false

    private val cameraRenderer = CameraRenderer()
    private val filtroEstabilizador = PromedioMovil(tamanoVentana = 5)
    private var visualizerFrameCounter = 0

    // Buffer de vértices de la pantalla (Cuadrado normalizado)
    private val localQuadVertices: FloatBuffer = ByteBuffer.allocateDirect(12 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1.0f, -1.0f, 0.0f, -1.0f, +1.0f, 0.0f, +1.0f, -1.0f, 0.0f, +1.0f, +1.0f, 0.0f))
            position(0)
        }

    // Buffer donde ARCore guardará las coordenadas corregidas
    private val transformedTexCoords: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvDistancia = findViewById(R.id.tvDistancia)
        tvLatencia = findViewById(R.id.tvLatencia)
        surfaceView = findViewById(R.id.surfaceView)
        ivMapaCalor = findViewById(R.id.ivMapaCalor)

        surfaceView.preserveEGLContextOnPause = true
        surfaceView.setEGLContextClientVersion(2)
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        surfaceView.setRenderer(this)
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        surfaceView.setWillNotDraw(false)

        actualizarUI("Cargando...", 0.0)

        if (!tienePermisoCamara()) {
            pedirPermisoCamara()
        }
    }

    override fun onResume() {
        super.onResume()
        if (tienePermisoCamara()) surfaceView.onResume()
    }

    override fun onPause() {
        super.onPause()
        surfaceView.onPause()
        session?.pause()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        cameraRenderer.createOnGlThread(this)

        try {
            if (session == null) {
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        installRequested = true
                        return
                    }
                    ArCoreApk.InstallStatus.INSTALLED -> {}
                }
                session = Session(this)
            }
            val configAr = Config(session)
            esSoportado = session!!.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
            if (esSoportado) configAr.depthMode = Config.DepthMode.AUTOMATIC
            session!!.configure(configAr)
            session!!.resume()
            session!!.setCameraTextureName(cameraRenderer.getTextureId())
        } catch (_: Exception) { }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        session?.setDisplayGeometry(0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (!esSoportado || session == null) return

        try {
            val tiempoInicio = System.nanoTime()
            val frame = session!!.update()

            // 1. Corrección Geométrica (Para que no se vea estirado ni espejo)
            try {
                frame.transformCoordinates2d(
                    Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                    localQuadVertices,
                    Coordinates2d.TEXTURE_NORMALIZED,
                    transformedTexCoords
                )
                cameraRenderer.updateTextureCoordinates(transformedTexCoords)
            } catch (_: Exception) {}

            // 2. Dibujar Cámara Real
            cameraRenderer.draw()

            if (frame.camera.trackingState != TrackingState.TRACKING) {
                actualizarUI("Mueve el celular", 0.0)
                return
            }

            try {
                // 3. Obtener Profundidad
                val depthImage = frame.acquireDepthImage16Bits()

                // Cálculo de Distancia Central (Numérico)
                val centroX = depthImage.width / 2
                val centroY = depthImage.height / 2
                val resultadoCrudo = DepthCalculator.obtenerDistancia(depthImage, centroX, centroY)
                val distanciaEstable = filtroEstabilizador.agregarValor(resultadoCrudo.distanciaMetros)

                // 4. VISUALIZACIÓN TÉRMICA (Estilo Unity)
                // Usamos la nueva función suave.
                // Quitamos el contador de frames para que sea FLUIDO (como en el video).
                val bitmapColores = DepthVisualizer.generarMapaCalorSuave(depthImage)

                if (bitmapColores != null) {
                    runOnUiThread {
                        ivMapaCalor.rotation = 0f
                        ivMapaCalor.setImageBitmap(bitmapColores)
                    }
                }

                depthImage.close()

                // Actualizar Textos
                val tiempoFin = System.nanoTime()
                val latenciaMs = (tiempoFin - tiempoInicio) / 1_000_000.0
                val mensajeEstado = if (distanciaEstable < 1.0) "¡PELIGRO!" else if (distanciaEstable < 3.0) "Cercano" else "Lejano"
                val textoDistancia = String.format(Locale.US, "%.2f m\n%s", distanciaEstable, mensajeEstado)

                actualizarUI(textoDistancia, latenciaMs)

            } catch (_: NotYetAvailableException) { }

        } catch (_: Exception) { }
    }

    private fun actualizarUI(textoDist: String, latencia: Double) {
        runOnUiThread {
            tvDistancia.text = textoDist
            if (latencia > 0) {
                tvLatencia.text = String.format(Locale.US, "Latencia: %.1f ms", latencia)
            }
        }
    }

    private fun tienePermisoCamara(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }
    private fun pedirPermisoCamara() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 0)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!tienePermisoCamara()) finish()
    }
}

class PromedioMovil(private val tamanoVentana: Int) {
    private val cola: Queue<Float> = LinkedList()
    private var suma: Float = 0f
    fun agregarValor(valor: Float): Float {
        if (valor == 0f) return if (cola.isNotEmpty()) suma / cola.size else 0f
        cola.add(valor)
        suma += valor
        if (cola.size > tamanoVentana) {
            val eliminado = cola.remove()
            suma -= eliminado
        }
        return suma / cola.size
    }
}