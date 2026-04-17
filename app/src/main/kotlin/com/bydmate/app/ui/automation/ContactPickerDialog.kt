package com.bydmate.app.ui.automation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.bydmate.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ContactItem(val name: String, val phone: String)

@Composable
fun ContactPickerDialog(
    onDismiss: () -> Unit,
    onSelect: (name: String, phone: String) -> Unit
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
    }

    var contacts by remember { mutableStateOf<List<ContactItem>>(emptyList()) }
    LaunchedEffect(hasPermission) {
        contacts = if (hasPermission) {
            withContext(Dispatchers.IO) { queryContacts(context) }
        } else emptyList()
    }
    var search by remember { mutableStateOf("") }

    val filtered = remember(search, contacts) {
        val q = search.trim().lowercase()
        if (q.isEmpty()) contacts
        else contacts.filter {
            it.name.lowercase().contains(q) || it.phone.lowercase().contains(q)
        }
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = TextPrimary,
        unfocusedTextColor = TextPrimary,
        focusedBorderColor = AccentGreen,
        unfocusedBorderColor = CardBorder,
        focusedLabelColor = AccentGreen,
        unfocusedLabelColor = TextSecondary,
        cursorColor = AccentGreen
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardSurface,
        title = { Text("Выбрать контакт", color = TextPrimary, fontSize = 16.sp) },
        text = {
            if (!hasPermission) {
                Column {
                    Text(
                        "Нет разрешения на чтение контактов.",
                        fontSize = 13.sp,
                        color = TextMuted
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = {
                        permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                    }) {
                        Text("Дать разрешение", color = AccentGreen, fontSize = 13.sp)
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        label = { Text("Поиск") },
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp)
                    ) {
                        items(filtered, key = { it.phone + "|" + it.name }) { contact ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(contact.name, contact.phone) }
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(contact.name, fontSize = 14.sp, color = TextPrimary, maxLines = 1)
                                    Text(contact.phone, fontSize = 11.sp, color = TextMuted, maxLines = 1)
                                }
                            }
                        }
                        if (filtered.isEmpty()) {
                            item {
                                Text(
                                    "Ничего не найдено",
                                    color = TextMuted,
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть", color = TextSecondary) }
        }
    )
}

private fun queryContacts(context: Context): List<ContactItem> {
    val result = mutableListOf<ContactItem>()
    val projection = arrayOf(
        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
        ContactsContract.CommonDataKinds.Phone.NUMBER
    )
    try {
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIdx) ?: continue
                val number = cursor.getString(numberIdx) ?: continue
                result.add(ContactItem(name, number))
            }
        }
    } catch (e: Exception) {
        // Permission revoked or provider missing
    }
    return result.distinctBy { it.name + "|" + it.phone }
}
