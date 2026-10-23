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
                text = "Watch button controls",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            
            HelpRow("Hold bottom-right until the second beep", "Play or pause music")
            HelpRow("Tap bottom-right button", "Toggle between quiet and loud volume")
            HelpRow("Hold bottom-left until the second beep", "Skip to the next song")
            HelpRow("Tap top-left button", "Return to ready mode")

            AppText(
                text = "Works with supported music apps on your phone. Allow notification access for media controls to work.",
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
