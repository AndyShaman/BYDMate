package com.bydmate.app.data.native

import com.bydmate.app.data.remote.DiParsData

interface ParsReader {
    suspend fun fetch(): DiParsData?
}
