package com.djvemo.routemap

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {
    @GET("v2/directions/{profile}")
    suspend fun getRoute(
        @Path("profile") profile: String,
        @Query("api_key") key: String,
        @Query("start", encoded = true) start: String,
        @Query("end", encoded = true) end: String,
        @Query("geometry_format") geometryFormat: String = "geojson",
        // AGREGA ESTA LÍNEA PARA PEDIR ESPAÑOL:
        @Query("language") language: String = "es"
    ): Response<RouteResponse>
}