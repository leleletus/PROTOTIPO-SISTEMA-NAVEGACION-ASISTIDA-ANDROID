package com.djvemo.smartmoves

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.djvemo.smartmoves.ml.DetectionResult
import com.djvemo.smartmoves.ml.Overlay
import com.djvemo.smartmoves.ml.YoloSegHelper
import org.tensorflow.lite.Interpreter
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private val requestPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (!ok) Toast.makeText(this, "Se necesita permiso de cámara", Toast.LENGTH_LONG).show()
        }

    private lateinit var interpreter: Interpreter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestCameraPermission()

        // Cargar labels y modelo (EN assets/)
        YoloSegHelper.loadLabels(this, "labels.txt")
        // Si te crashea con GPU en tu equipo, pon useGpu=false
        interpreter = YoloSegHelper.createInterpreter(this, "yolo11n_seg_best_float32.tflite", useGpu = true)

        // (Opcional) Ajusta umbrales por defecto
        // YoloSegHelper.setThresholds(conf = 0.35f, iou = 0.50f, mask = 0.50f)

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen(interpreter)
                }
            }
        }
    }

    private fun requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            requestPerm.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { interpreter.close() } catch (_: Throwable) {}
        YoloSegHelper.close() // cierra el delegate GPU si existe
    }
}

@Composable
fun MainScreen(interpreter: Interpreter) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var detections by remember { mutableStateOf(emptyList<DetectionResult>()) }
    var latency by remember { mutableStateOf(0L) }

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(ctx) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { context ->
                val pv = PreviewView(context).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }

                val preview = Preview.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .build()
                    .also { it.setSurfaceProvider(pv.surfaceProvider) }

                val analyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also { ia ->
                        ia.setAnalyzer(analysisExecutor) { imageProxy ->
                            // Inferencia + postproceso
                            YoloSegHelper.analyzeImageProxy(
                                image = imageProxy,
                                interpreter = interpreter
                            ) { results, totalMs, _, _ ->
                                detections = results
                                latency = totalMs // latencia total (pre+infer+post)
                            }
                        }
                    }

                cameraProviderFuture.addListener({
                    val provider = cameraProviderFuture.get()
                    try {
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analyzer
                        )
                    } catch (e: Exception) {
                        Log.e("CameraX", "bind error", e)
                    }
                }, ContextCompat.getMainExecutor(context))

                pv
            },
            modifier = Modifier.fillMaxSize()
        )

        // Dibuja cajas, máscaras, etiquetas y latencia
        Overlay(detections = detections, inferenceMs = latency)
    }
}
