package com.bydmate.app.ui.tech

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bydmate.app.R
import com.bydmate.app.ui.theme.CardBorder
import com.bydmate.app.ui.theme.TextMuted
import com.bydmate.app.ui.theme.TextSecondary

/** The last item of the DTC list, always shown: why support matters and a framed «Поддержать». */
@Composable
internal fun DtcSupportLine(onSupport: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp, start = 2.dp)) {
        Text(
            stringResource(R.string.tech_dtc_support_text),
            color = TextMuted,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(
            onClick = onSupport,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, CardBorder),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
            modifier = Modifier.padding(start = 12.dp, end = 16.dp).heightIn(min = 48.dp),
        ) {
            Icon(Icons.Outlined.FavoriteBorder, contentDescription = null, modifier = Modifier.size(24.dp))
            Box(modifier = Modifier.padding(start = 8.dp)) {
                Text(stringResource(R.string.tech_dtc_support_button), fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}
