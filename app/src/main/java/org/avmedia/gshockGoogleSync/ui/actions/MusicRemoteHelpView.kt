package org.avmedia.gshockGoogleSync.ui.actions

import AppText
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.avmedia.gshockGoogleSync.ui.common.AppCard

@Composable
fun MusicRemoteHelpView() {
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            AppText(
                text = "ABL-100 Media Control Guide (Smart Prioritization)",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            
            HelpRow("Long D (Find Phone)", "Spotify Play / Pause (Reliable)")
            HelpRow("Short D (Bottom-Right)", "Volume Toggle (Low ↔ High)")
            HelpRow("Long C (Bottom-Left)", "Spotify Next Track")
            HelpRow("A1 (Top-Left)", "Clear / Return to Ready State")

            AppText(
                text = "Intelligent Control: Targets active players (like YouTube) first. If nothing is playing, it targets Spotify. Ensure 'Notification Access' is granted.",
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}

@Composable
private fun HelpRow(button: String, action: String) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        AppText(text = button, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        AppText(text = action, fontSize = 16.sp)
    }
}
