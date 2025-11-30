package com.djvemo.routemap

import com.google.gson.annotations.SerializedName

data class RouteResponse(
    @SerializedName("features") val features: List<Feature>
)

data class Feature(
    @SerializedName("properties") val properties: Properties,
    @SerializedName("geometry") val geometry: Geometry
)

data class Properties(
    @SerializedName("summary") val summary: Summary,
    @SerializedName("segments") val segments: List<Segment>
)
//me dice distancia y duracio
data class Summary(
    @SerializedName("distance") val distance: Double,  // metros
    @SerializedName("duration") val duration: Double   // segundos
)
//me dice instruciones gira a ..
data class Segment(
    @SerializedName("steps") val steps: List<Step>
)

data class Step(
    @SerializedName("instruction") val instruction: String,
    @SerializedName("distance") val distance: Double,  // hasta el próximo giro
    @SerializedName("duration") val duration: Double
)

data class Geometry(
    @SerializedName("coordinates") val coordinates: List<List<Double>>
)