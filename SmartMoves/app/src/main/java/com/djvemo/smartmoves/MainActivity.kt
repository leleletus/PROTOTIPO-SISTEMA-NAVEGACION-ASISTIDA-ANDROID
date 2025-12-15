package com.djvemo.smartmoves

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// Definición de Colores de tu diseño
val SmartPurpleHeader = Color(0xFFB368F5)
val SmartBlueSearch = Color(0xFF15469F)
val SmartBgMain = Color(0xFF5C6BC0)

class MainActivity : ComponentActivity() {
    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()

        setContent {
            // Aquí inicia la UI de Compose
            SmartMovesApp(cameraExecutor)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}

@Composable
fun SmartMovesApp(cameraExecutor: ExecutorService) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            hasCameraPermission = granted
            if (!granted) Toast.makeText(context, "Permiso requerido", Toast.LENGTH_SHORT).show()
        }
    )

    // Pedir permiso al iniciar si no lo tiene
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    // Estructura Principal (Columna Vertical)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SmartBgMain)
    ) {
        // 1. HEADER
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(SmartPurpleHeader)
                .padding(vertical = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "SmartMoves",
                color = Color.White,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                fontStyle = FontStyle.Italic,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Serif
            )
        }

        // 2. BARRA DE BÚSQUEDA
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SmartBlueSearch)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Ingresar ruta a seguir:",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                fontStyle = FontStyle.Italic,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Buscar",
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
        }

        // 3. CONTROLES
        Column(modifier = Modifier.padding(20.dp)) {
            // Switch Vibración
            var vibState by remember { mutableStateOf(false) }
            ControlSwitch(text = "Vibración", checked = vibState) { vibState = it }

            Spacer(modifier = Modifier.height(10.dp))

            // Switch Alertas
            var ttsState by remember { mutableStateOf(false) }
            ControlSwitch(text = "Solo leer alertas importantes", checked = ttsState) { ttsState = it }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Indicaciones",
                color = Color.White,
                fontSize = 22.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }

        // 4. CÁMARA + CAPA YOLO (Box permite poner cosas encima de otras)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f) // Ocupa todo el espacio restante abajo
        ) {
            if (hasCameraPermission) {
                CameraPreview()
            } else {
                Text("Se requiere permiso de cámara", color = Color.White, modifier = Modifier.align(Alignment.Center))
            }

            // --- AQUÍ IRÁ TU CAPA YOLO (Overlay) ---
            // Por ahora es una caja transparente lista para pintar
            // Cuando tengas tu clase "Overlay", la pondrás aquí.
            // AndroidView(factory = { context -> Overlay(context) ... })
        }
    }
}

// Composable auxiliar para los Switches
@Composable
fun ControlSwitch(text: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 18.sp
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = SmartPurpleHeader,
                uncheckedThumbColor = Color.LightGray,
                uncheckedTrackColor = Color.Gray
            )
        )
    }
}

// Composable de la Cámara
@Composable
fun CameraPreview() {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val executor = ContextCompat.getMainExecutor(ctx)

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview
                        // Aquí añadirás luego tu ImageAnalysis para YOLO
                    )
                } catch (e: Exception) {
                    // Manejar error
                }
            }, executor)

            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}