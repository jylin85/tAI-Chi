package com.example.tai_chi

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val PREFS = "recording"
private const val KEY_PERFORMER = "performer"

fun formatDuration(ns: Long): String {
    val total = (ns / 1_000_000_000L).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

/** Record / Stop button for the side rail, with the elapsed time and the last saved session. */
@Composable
fun RecordControls(
    recording: Boolean,
    elapsedNs: Long,
    hasVideo: Boolean,
    message: String?,
    onRecord: () -> Unit,
    onStop: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        if (recording) {
            Row(
                Modifier.fillMaxWidth().background(Color(0x33FF5A4E), RoundedCornerShape(8.dp)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "● REC  ${formatDuration(elapsedNs)}",
                    color = Color(0xFFFF9188),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 12.dp)
                )
                TextButton(onClick = onStop) { Text("■ Stop", color = Color.White, fontSize = 16.sp) }
            }
            if (!hasVideo) {
                Text("Recording data only: this tablet cannot record video at the same time.",
                    color = Color(0xFFFFD27A), fontSize = 12.sp)
            }
        } else {
            TextButton(onClick = onRecord, modifier = Modifier.fillMaxWidth()) {
                Text("● Record session", color = Color(0xFFFF9188), fontSize = 16.sp)
            }
            if (message != null) Text(message, color = Color(0xFF78D6A1), fontSize = 12.sp)
        }
    }
}

/** Asks who is performing before a recording starts. The name is remembered for next time. */
@Composable
fun RecordDialog(onStart: (performer: String, notes: String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var performer by remember { mutableStateOf(prefs.getString(KEY_PERFORMER, "") ?: "") }
    var notes by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Record a session") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Saves the video, the detected pose for every frame and both bands' readings to this tablet.")
                OutlinedTextField(
                    value = performer, onValueChange = { performer = it },
                    label = { Text("Performer (required)") }, singleLine = true,
                )
                OutlinedTextField(
                    value = notes, onValueChange = { notes = it },
                    label = { Text("Notes, e.g. \"normal speed\", \"mistakes on purpose\"") },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = performer.isNotBlank(),
                onClick = {
                    prefs.edit().putString(KEY_PERFORMER, performer.trim()).apply()
                    onStart(performer, notes)
                },
            ) { Text("Start recording") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
