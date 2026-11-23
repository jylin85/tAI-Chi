package com.example.tai_chi

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Green = Color(0xFF78D6A1)
private val Yellow = Color(0xFFFFD27A)
private val Red = Color(0xFFFF9188)
private val Muted = Color(0xFFB8D9C3)

/** Colour for a charge level: green above half, yellow down to 20%, red below. */
fun batteryColor(percent: Int): Color = when {
    percent >= 50 -> Green
    percent >= 20 -> Yellow
    else -> Red
}

/**
 * Battery gauge for one band: a battery outline filled to the charge level, the percentage, and a
 * lightning bolt while charging. Shows "battery n/a" for bands whose firmware does not report it.
 */
@Composable
fun BatteryIndicator(battery: Battery?, supported: Boolean, modifier: Modifier = Modifier) {
    if (!supported) {
        Text("battery n/a", color = Muted, fontSize = 12.sp, modifier = modifier)
        return
    }
    if (battery == null) {
        Text("battery …", color = Muted, fontSize = 12.sp, modifier = modifier)
        return
    }
    val pct = battery.percent
    val color = if (battery.charging) Green else batteryColor(pct)
    val description = "Battery $pct percent" + if (battery.charging) ", charging" else ""
    Row(
        modifier.semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Body: an outline with a fill proportional to the charge (at least a sliver, so 1% is visible).
        Box(
            Modifier
                .width(30.dp)
                .height(15.dp)
                .border(1.5.dp, color, RoundedCornerShape(3.dp))
                .padding(2.5.dp)
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth((pct / 100f).coerceIn(0.06f, 1f))
                    .background(color, RoundedCornerShape(1.dp))
            )
        }
        // Positive terminal nub.
        Box(
            Modifier
                .width(3.dp)
                .height(7.dp)
                .background(color, RoundedCornerShape(topEnd = 1.5.dp, bottomEnd = 1.5.dp))
        )
        Text(
            (if (battery.charging) " ⚡" else " ") + "$pct%",
            color = color,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
