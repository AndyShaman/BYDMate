package com.bydmate.app.data.autoservice

import javax.inject.Inject

data class Pm25(val inside: Int?, val outside: Int?, val levelIn: Int?, val levelOut: Int?)

@Suppress("UnusedPrivateProperty") // stub: the reads land with the implementation
class Pm25Reader @Inject constructor(private val autoservice: AutoserviceClient) {
    /** null = no sensor (neither level fid answers 1..6). */
    suspend fun read(): Pm25? = TODO()
}
