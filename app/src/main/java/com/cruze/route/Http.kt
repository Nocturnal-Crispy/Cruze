package com.cruze.route

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * All backing services are free community endpoints (FOSSGIS Valhalla, OSM tiles, Nominatim).
 * Their usage policies require a real identifying User-Agent — do not make this generic.
 */
const val USER_AGENT = "Cruze/0.1 (Android motorcycle route planner; github.com/cruze)"

val http: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()
}

class ServiceException(message: String) : Exception(message)
