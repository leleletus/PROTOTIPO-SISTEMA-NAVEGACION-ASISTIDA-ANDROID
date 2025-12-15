package com.djvemo.navasistidadepth

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.WindowManager
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MainActivity : AppCompatActivity(), GLSurfaceView.Renderer {

    private lateinit var surfaceView: GLSurfaceView
    private lateinit var tvDistancia: TextView
    private lateinit var tvLog: TextView
    private lateinit var ivMapaCalor: ImageView

    private var session: Session? = null
    private var installRequested = false
    private val cameraRenderer = CameraRenderer()

    // Definimos los vértices del cuadrado (2D: X, Y)
    // 4 vértices * 2 coordenadas * 4 bytes = 32 bytes
    private val localQuadVertices: FloatBuffer = ByteBuffer.allocateDirect(32)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(
                -1.0f, -1.0f, // Abajo-Izq
                -1.0f, +1.0f, // Arriba-Izq
                +1.0f, -1.0f, // Abajo-Der
                +1.0f, +1.0f  // Arriba-Der
            ))
            position(0)
        }

    // Buffer para recibir las coordenadas de textura corregidas
    private val transformedTexCoords: FloatBuffer = ByteBuffer.allocateDirect(32)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvDistancia = findViewById(R.id.tvDistancia)
        tvLog = findViewById(R.id.tvLog)
        surfaceView = findViewById(R.id.surfaceView)
        ivMapaCalor = findViewById(R.id.ivMapaCalor)

        log("onCreate: Iniciando (Fix Rebobinado)...")

        surfaceView.preserveEGLContextOnPause = true
        surfaceView.setEGLContextClientVersion(2)
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        surfaceView.setRenderer(this)
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        if (!tienePermisoCamara()) pedirPermisoCamara()
    }

    override fun onResume() {
        super.onResume()
        if (session == null) {
            try {
                if (ArCoreApk.getInstance().requestInstall(this, !installRequested) == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                    installRequested = true
                    return
                }
                session = Session(this)
                val config = Config(session)
                // Verificamos soporte de Depth
                if (session!!.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                    config.depthMode = Config.DepthMode.AUTOMATIC
                    log("Depth: ACTIVADO (Auto)")
                } else {
                    log("Depth: NO SOPORTADO")
                }
                session!!.configure(config)
            } catch (e: Exception) {
                log("Error Session: ${e.message}")
                return
            }
        }
        try { session!!.resume() } catch (e: Exception) { log("Error Resume: ${e.message}") }
        surfaceView.onResume()
    }

    override fun onPause() {
        super.onPause()
        surfaceView.onPause()
        session?.pause()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
        try {
            cameraRenderer.createOnGlThread(this)
            session?.setCameraTextureName(cameraRenderer.getTextureId())
        } catch (e: Exception) { log("Error Renderer: ${e.message}") }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val rotation = getSystemService(WindowManager::class.java).defaultDisplay.rotation
        session?.setDisplayGeometry(rotation, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (session == null) return

        try {
            session!!.setCameraTextureName(cameraRenderer.getTextureId())
            val frame = session!!.update()

            // ---------------------------------------------------------
            // <--- CORRECCIÓN CRÍTICA: REBOBINAR BUFFERS --->
            // Si no hacemos esto, position() está al final y ARCore lee 0 bytes.
            localQuadVertices.position(0)
            transformedTexCoords.position(0)
            // ---------------------------------------------------------

            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                localQuadVertices,
                Coordinates2d.TEXTURE_NORMALIZED,
                transformedTexCoords
            )

            cameraRenderer.updateTextureCoordinates(transformedTexCoords)
            cameraRenderer.draw()

            // 2. Procesar Profundidad
            if (frame.camera.trackingState == TrackingState.TRACKING) {
                try {
                    val depthImage = frame.acquireDepthImage16Bits()

                    // Calculo
                    val cx = depthImage.width / 2
                    val cy = depthImage.height / 2
                    val resultado = DepthCalculator.obtenerDistanciaPromedio(depthImage, cx, cy)

                    // Visualización
                    val bitmap = DepthVisualizer.generarMapaCalor(depthImage)

                    depthImage.close()

                    runOnUiThread {
                        if (bitmap != null) {
                            // YA NO ROTAMOS EL IMAGEVIEW, porque el bitmap ya viene derecho
                            ivMapaCalor.setImageBitmap(bitmap)

                            // Solo aseguramos que llene la pantalla
                            ivMapaCalor.rotation = 0f
                            ivMapaCalor.scaleType = ImageView.ScaleType.FIT_XY
                            ivMapaCalor.scaleX = 1f
                            ivMapaCalor.scaleY = 1f
                        }

                        tvDistancia.text = "%.2f m\n%s".format(resultado.distanciaMetros, resultado.mensaje)

                        if(resultado.distanciaMetros > 0 && resultado.distanciaMetros < 1.2) {
                            tvDistancia.setTextColor(0xFFFF0000.toInt()) // Rojo
                            // Opcional: Vibrar aquí si quisieras
                        } else {
                            tvDistancia.setTextColor(0xFFFFFFFF.toInt()) // Blanco
                        }
                    }
                } catch (e: Exception) {
                    // Ignoramos NotYetAvailable, logueamos el resto
                    if (e !is com.google.ar.core.exceptions.NotYetAvailableException) {
                        log("Depth Error: ${e.message}")
                    }
                }
            }
        } catch (t: Throwable) {
            log("CRASH: ${t.message}")
            t.printStackTrace()
        }
    }

    private fun log(msg: String) {
        runOnUiThread { tvLog.append("\n$msg") }
    }

    private fun tienePermisoCamara() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun pedirPermisoCamara() = ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 0)
}