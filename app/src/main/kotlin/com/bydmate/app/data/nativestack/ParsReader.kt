package com.bydmate.app.data.nativestack

import com.bydmate.app.data.remote.DiParsData

interface ParsReader {
    suspend fun fetch(): DiParsData?
}
