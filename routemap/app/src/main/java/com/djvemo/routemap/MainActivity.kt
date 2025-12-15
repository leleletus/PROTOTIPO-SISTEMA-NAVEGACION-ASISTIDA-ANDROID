package com.djvemo.routemap

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.Locale

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var map: GoogleMap
    private lateinit var btnCalculate: Button

    private var start: String = ""
    private var end: String = ""
    private var poly: Polyline? = null

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private var locationCallback: LocationCallback? = null

    private var tts: TextToSpeech? = null

    private val ORS_API_KEY = BuildConfig.ORS_API_KEY

    companion object {
        private const val REQ_LOCATION = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        btnCalculate = findViewById(R.id.btnCalculateRoute)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("es", "ES")
            }
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            5000L
        ).setWaitForAccurateLocation(true)
            .setMinUpdateIntervalMillis(2000L)
            .build()

        btnCalculate.setOnClickListener {
            if (hasLocationPermission()) {
                startLocationUpdates {
                    Toast.makeText(this, "Origen GPS establecido", Toast.LENGTH_SHORT).show()
                    prepareDestinationSelection()
                }
            } else {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ),
                    REQ_LOCATION
                )
            }
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        this.map = googleMap
        if (hasLocationPermission()) {
            try {
                map.isMyLocationEnabled = true

                // --- INICIO DEL CAMBIO PARA ZOOM AUTOMÁTICO ---
                // Obtenemos la última ubicación conocida para centrar el mapa al iniciar
                fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                    if (location != null) {
                        val latLng = LatLng(location.latitude, location.longitude)
                        // Zoom 18f es nivel "calle/caminata".
                        // 15f es barrio, 20f es edificios.
                        val update = CameraUpdateFactory.newLatLngZoom(latLng, 200f)
                        map.moveCamera(update)
                    }
                }
                // --- FIN DEL CAMBIO ---

            } catch (se: SecurityException) {
                Log.e("RouteMap", "SecurityException al habilitar ubicación: ${se.message}")
            }
        }
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine && coarse
    }

    private fun prepareDestinationSelection() {
        end = ""
        poly?.remove()
        poly = null

        Toast.makeText(this, "Selecciona destino en el mapa", Toast.LENGTH_SHORT).show()
        if (::map.isInitialized) {
            map.setOnMapClickListener { latLng ->
                if (end.isEmpty()) {
                    end = "${latLng.longitude},${latLng.latitude}"
                    Toast.makeText(this, "Destino seleccionado", Toast.LENGTH_SHORT).show()
                    map.setOnMapClickListener(null)
                    createRoute()
                }
            }
        } else {
            Toast.makeText(this, "El mapa aún no está listo", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startLocationUpdates(onLocated: () -> Unit) {
        if (!hasLocationPermission()) return

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val location = locationResult.lastLocation
                if (location != null) {
                    start = "${location.longitude},${location.latitude}"
                    stopLocationUpdates()
                    onLocated()
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )
        } catch (se: SecurityException) {
            Log.e("RouteMap", "SecurityException al solicitar actualizaciones: ${se.message}")
            Toast.makeText(this, "Permisos de ubicación insuficientes", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopLocationUpdates() {
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        locationCallback = null
    }

    private fun createRoute() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val service = getRetrofit().create(ApiService::class.java)
                val response = service.getRoute(
                    profile = "foot-walking",
                    key = ORS_API_KEY,
                    start = start,
                    end = end,
                    geometryFormat = "geojson",
                    language = "es" // Forzamos español aquí también
                )

                if (response.isSuccessful) {
                    val body = response.body()
                    drawRoute(body)
                    showSummary(body)
                    narrateSteps(body)
                } else {
                    Log.e(
                        "RouteMap",
                        "Error HTTP: ${response.code()} - ${response.errorBody()?.string()}"
                    )
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "Error al obtener ruta: ${response.code()}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("RouteMap", "Excepción en createRoute: ${e.message}", e)
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "Excepción: ${e.localizedMessage}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun drawRoute(routeResponse: RouteResponse?) {
        val polyLineOptions = PolylineOptions()

        routeResponse
            ?.features
            ?.firstOrNull()
            ?.geometry
            ?.coordinates
            ?.forEach { coord ->
                if (coord.size >= 2) {
                    polyLineOptions.add(LatLng(coord[1], coord[0]))
                }
            }

        runOnUiThread {
            if (polyLineOptions.points.isEmpty()) {
                Toast.makeText(this, "No se pudo dibujar la ruta (sin puntos).", Toast.LENGTH_SHORT)
                    .show()
                return@runOnUiThread
            }
            poly = map.addPolyline(polyLineOptions)
        }
    }

    private fun showSummary(routeResponse: RouteResponse?) {
        val summary = routeResponse
            ?.features?.firstOrNull()
            ?.properties?.summary

        summary?.let {
            val distanciaKm = it.distance / 1000.0
            val duracionMin = it.duration / 60.0
            runOnUiThread {
                Toast.makeText(
                    this,
                    "Distancia: %.2f km, Duración: %.1f min".format(distanciaKm, duracionMin),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun narrateSteps(routeResponse: RouteResponse?) {
        val steps = routeResponse
            ?.features?.firstOrNull()
            ?.properties?.segments?.firstOrNull()
            ?.steps ?: emptyList()

        for (step in steps) {
            // AQUI ESTÁ EL TRUCO: Traducimos antes de armar la frase
            val instruccionTraducida = traducirInstruccion(step.instruction)

            val texto = "$instruccionTraducida, en ${step.distance.toInt()} metros."
            speak(texto)
        }
    }

    // Agrega esta nueva función al final de tu MainActivity para hacer la magia
    private fun traducirInstruccion(textoIngles: String): String {
        var texto = textoIngles

        // 1. LIMPIEZA INICIAL DE CONECTORES COMPUESTOS (Importante el orden)
        // "onto" debe ir antes que "on" para no romperlo
        texto = texto.replace(" onto ", " hacia ", ignoreCase = true)

        // AQUÍ ESTÁ EL ARREGLO PARA TU PROBLEMA ACTUAL:
        // Reemplazamos " on " (con espacios) por " por "
        texto = texto.replace(" on ", " por ", ignoreCase = true)

        texto = texto.replace(" then ", " y luego ", ignoreCase = true)
        texto = texto.replace(" at ", " en ", ignoreCase = true)
        texto = texto.replace(" towards ", " hacia ", ignoreCase = true)

        // 2. DIRECCIONES CARDINALES
        texto = texto.replace("North", "Norte", ignoreCase = true)
        texto = texto.replace("South", "Sur", ignoreCase = true)
        texto = texto.replace("East", "Este", ignoreCase = true)
        texto = texto.replace("West", "Oeste", ignoreCase = true)

        // Variantes compuestas
        texto = texto.replace("Northeast", "Noreste", ignoreCase = true)
        texto = texto.replace("Northwest", "Noroeste", ignoreCase = true)
        texto = texto.replace("Southeast", "Sureste", ignoreCase = true)
        texto = texto.replace("Southwest", "Suroeste", ignoreCase = true)

        // 3. INSTRUCCIONES DE GIRO
        if (texto.contains("Turn right", ignoreCase = true)) {
            texto = texto.replace("Turn right", "Gira a la derecha", ignoreCase = true)
        }
        if (texto.contains("Turn left", ignoreCase = true)) {
            texto = texto.replace("Turn left", "Gira a la izquierda", ignoreCase = true)
        }

        // Giros suaves/cerrados
        texto = texto.replace("slight right", "ligeramente a la derecha", ignoreCase = true)
        texto = texto.replace("slight left", "ligeramente a la izquierda", ignoreCase = true)
        texto = texto.replace("sharp right", "cerradamente a la derecha", ignoreCase = true)
        texto = texto.replace("sharp left", "cerradamente a la izquierda", ignoreCase = true)

        // MANTENERSE
        if (texto.contains("Keep right", ignoreCase = true)) {
            texto = texto.replace("Keep right", "Mantente a la derecha", ignoreCase = true)
        }
        if (texto.contains("Keep left", ignoreCase = true)) {
            texto = texto.replace("Keep left", "Mantente a la izquierda", ignoreCase = true)
        }
        if (texto.contains("Keep straight", ignoreCase = true)) {
            texto = texto.replace("Keep straight", "Sigue derecho", ignoreCase = true)
        }

        // 4. COMANDOS DE INICIO/FIN
        // "Head" a veces queda mejor como "Ve" o "Dirígete"
        if (texto.contains("Head", ignoreCase = true)) {
            texto = texto.replace("Head", "Dirígete", ignoreCase = true)
        }
        if (texto.contains("Arrive", ignoreCase = true)) {
            texto = "Has llegado a tu destino"
        }
        if (texto.contains("You have reached", ignoreCase = true)) {
            texto = "Has llegado a"
        }

        return texto
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "step-${System.currentTimeMillis()}")
    }

    private fun getRetrofit(): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://api.openrouteservice.org/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQ_LOCATION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLocationUpdates {
                    Toast.makeText(this, "Origen GPS establecido", Toast.LENGTH_SHORT).show()
                    prepareDestinationSelection()
                }
            } else {
                Toast.makeText(this, "Permiso de ubicación denegado", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLocationUpdates()
    }
}
