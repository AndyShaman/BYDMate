package com.bydmate.app.ui.tech

import android.content.Context
import com.bydmate.app.R
import com.bydmate.app.data.dtc.DtcScanSummary

/** The line under the card or dialog when the scan holds no rows. Stub: the real text is added with the fix. */
@Suppress("UnusedParameter")
internal fun dtcNoRowsText(context: Context, summary: DtcScanSummary): String =
    context.getString(R.string.tech_dtc_none_found)
