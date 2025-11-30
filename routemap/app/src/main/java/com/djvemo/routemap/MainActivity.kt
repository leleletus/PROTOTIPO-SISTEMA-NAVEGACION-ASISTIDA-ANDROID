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
                    geometryFormat = "geojson"
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
            val texto = "${step.instruction}, en ${step.distance.toInt()} metros."
            speak(texto)
        }
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
