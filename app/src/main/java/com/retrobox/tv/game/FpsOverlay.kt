@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.retrobox.tv.game

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text

/** Small frame-rate / timing readout in the top-left corner (inside the safe area). Only shown when the user turns it on. */
@Composable
fun FpsOverlay(text: String, safeAreaPct: Int) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(Modifier.padding(start = maxWidth * safeAreaPct / 100 + 8.dp, top = maxHeight * safeAreaPct / 100 + 8.dp)) {
            Text(
                text, fontSize = 12.sp, color = Color(0xFFB5F5C8),
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xB3000000)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

/** "▶▶ 3×" in the top-right corner while fast-forward is running, so a sped-up game is never mistaken for a glitch. */
@Composable
fun FastForwardBadge(speedLabel: String, safeAreaPct: Int) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().padding(end = maxWidth * safeAreaPct / 100 + 10.dp, top = maxHeight * safeAreaPct / 100 + 8.dp), contentAlignment = Alignment.TopEnd) {
            Text(
                "▶▶ $speedLabel", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFC857),
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xB3000000)).padding(horizontal = 9.dp, vertical = 3.dp),
            )
        }
    }
}
