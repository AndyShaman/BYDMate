package com.bydmate.app.ui.tech

import android.content.Context
import com.bydmate.app.R
import com.bydmate.app.data.dtc.DtcScanSummary

/** The line under the card or dialog when the scan holds no rows: never «clean» while a list went unread. */
internal fun dtcNoRowsText(context: Context, summary: DtcScanSummary): String =
    if (summary.unreadBlocks > 0) {
        context.resources.getQuantityString(R.plurals.tech_dtc_unread, summary.unreadBlocks, summary.unreadBlocks)
    } else {
        context.getString(R.string.tech_dtc_none_found)
    }
