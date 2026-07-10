package com.bydmate.app.ui.settings

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bydmate.app.R
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.AccentOrange
import com.bydmate.app.ui.theme.CardBorder
import com.bydmate.app.ui.theme.CardSurface
import com.bydmate.app.ui.theme.CardSurfaceElevated
import com.bydmate.app.ui.theme.NavyDark
import com.bydmate.app.ui.theme.TextMuted
import com.bydmate.app.ui.theme.TextPrimary
import com.bydmate.app.ui.theme.TextSecondary

/** One 0x address that receives USDT on every supported EVM chain. Kept in code rather
 *  than a string resource because it is identical in every locale and must never be
 *  accidentally translated, re-formatted, or line-wrapped by a localization pass. */
const val DONATION_ADDRESS = "0x24919d8a46357D83fe935F02BEbE55b1b7c360B8"

/** AUTO = the once-per-version prompt on app entry (two dismiss options); SETTINGS = the
 *  same content opened deliberately from the settings card (single "Close" button). */
enum class DonateEntry { AUTO, SETTINGS }

/** Prefs mirror of [com.bydmate.app.service.UpdateChecker]'s companion helpers: a "seen
 *  version" so the auto prompt appears at most once per app version, plus a permanent
 *  opt-out set when the user taps "I already supported". */
object DonationReminder {
    private const val PREFS = "donation_prefs"
    private const val KEY_SEEN_VERSION = "donation_seen_version"
    private const val KEY_OPTOUT = "donation_optout"

    /** True when the auto prompt should show for [currentVersion]: the user has not opted
     *  out and has not already seen it on this version. */
    fun shouldShow(context: Context, currentVersion: String): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getBoolean(KEY_OPTOUT, false)) return false
        return p.getString(KEY_SEEN_VERSION, null) != currentVersion
    }

    fun markSeen(context: Context, currentVersion: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SEEN_VERSION, currentVersion).apply()
    }

    fun setOptedOut(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_OPTOUT, true).apply()
    }
}

/**
 * Full-screen donation prompt sized for the DiLink car display: a large QR on the left
 * (the head unit can't paste a copied address, so scanning with a phone is the primary
 * path) with the address, networks and an EVM-only warning on the right.
 *
 * [onAlreadySupported] is used only by [DonateEntry.AUTO]; [onDismiss] backs both the
 * "Later"/"Close" button and a tap outside the card.
 */
@Composable
fun DonateDialog(
    entry: DonateEntry,
    onDismiss: () -> Unit,
    onAlreadySupported: () -> Unit = {},
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val copiedToast = stringResource(R.string.donate_copied_toast)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
                modifier = Modifier
                    .fillMaxWidth(0.62f)
                    // Absorb taps so clicking the card never dismisses via the scrim behind it.
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {},
            ) {
                Column(modifier = Modifier.padding(28.dp)) {
                    Text(
                        stringResource(
                            if (entry == DonateEntry.AUTO) R.string.donate_auto_title else R.string.donate_settings_title
                        ),
                        color = TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 31.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(
                            if (entry == DonateEntry.AUTO) R.string.donate_auto_body else R.string.donate_settings_body
                        ),
                        color = TextSecondary, fontSize = 16.sp, lineHeight = 24.sp,
                    )
                    Spacer(Modifier.height(22.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(300.dp)
                                    .background(Color.White, RoundedCornerShape(14.dp))
                                    .padding(12.dp),
                            ) {
                                Image(
                                    painter = painterResource(R.drawable.donate_qr),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(
                                stringResource(R.string.donate_qr_hint),
                                color = AccentGreen, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(stringResource(R.string.donate_address_label), color = TextMuted, fontSize = 12.sp)
                                Text(
                                    DONATION_ADDRESS,
                                    color = TextPrimary,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 17.sp,
                                    lineHeight = 24.sp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(CardSurface, RoundedCornerShape(12.dp))
                                        .border(1.dp, CardBorder, RoundedCornerShape(12.dp))
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    clipboard.setText(AnnotatedString(DONATION_ADDRESS))
                                    Toast.makeText(context, copiedToast, Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, AccentGreen),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentGreen),
                            ) {
                                Text(
                                    stringResource(R.string.donate_copy_button),
                                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Text(
                                stringResource(R.string.donate_networks),
                                color = TextSecondary, fontSize = 13.sp, lineHeight = 19.sp,
                            )
                            Text(
                                stringResource(R.string.donate_warning),
                                color = AccentOrange, fontSize = 13.sp, lineHeight = 19.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(AccentOrange.copy(alpha = 0.09f), RoundedCornerShape(12.dp))
                                    .border(1.dp, AccentOrange.copy(alpha = 0.28f), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                    ) {
                        if (entry == DonateEntry.AUTO) {
                            TextButton(onClick = onDismiss) {
                                Text(stringResource(R.string.donate_later_button), color = TextSecondary, fontSize = 16.sp)
                            }
                            Button(
                                onClick = onAlreadySupported,
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = NavyDark),
                            ) {
                                Text(
                                    stringResource(R.string.donate_already_button),
                                    fontSize = 16.sp, fontWeight = FontWeight.Bold,
                                )
                            }
                        } else {
                            Button(
                                onClick = onDismiss,
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = NavyDark),
                            ) {
                                Text(
                                    stringResource(R.string.donate_close_button),
                                    fontSize = 16.sp, fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
