@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.retrobox.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

/** The back arrow used at the top-left of every page: a focusable button (D-pad up from the content reaches it) that works by touch too. */
@Composable
fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(width = 44.dp, height = 36.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xCC131A36), contentColor = RB.Text, focusedContainerColor = RB.PanelHi, focusedContentColor = RB.Text, pressedContainerColor = RB.PanelHi, pressedContentColor = RB.Text),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, Color(0x33FFFFFF)), shape = RoundedCornerShape(12.dp)),
            focusedBorder = Border(BorderStroke(2.dp, RB.Pink), shape = RoundedCornerShape(12.dp)),
        ),
    ) { Box(Modifier.size(width = 44.dp, height = 36.dp), contentAlignment = Alignment.Center) { UiIconView(UiIcon.BACK, RB.Text, 20.dp) } }
}

/** Standard page header: back button, logo, title and a one-line subtitle. */
@Composable
fun ScreenHeader(title: String, subtitle: String?, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 40.dp).padding(top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        BackButton(onBack)
        Spacer(Modifier.width(14.dp))
        LogoMark(Modifier.size(width = 58.dp, height = 40.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = RB.Muted, maxLines = 1)
        }
    }
}
