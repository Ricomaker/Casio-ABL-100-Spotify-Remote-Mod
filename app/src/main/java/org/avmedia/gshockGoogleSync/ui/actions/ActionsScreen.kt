package org.avmedia.gshockGoogleSync.ui.actions

import AppText
import AppTextLarge
import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.Dimension
import org.avmedia.gshockGoogleSync.R
import org.avmedia.gshockGoogleSync.theme.GShockSmartSyncTheme
import org.avmedia.gshockGoogleSync.ui.common.AppButton
import org.avmedia.gshockGoogleSync.ui.common.AppCard
import org.avmedia.gshockGoogleSync.ui.common.AppSnackbar
import org.avmedia.gshockGoogleSync.ui.common.ScreenTitle
import timber.log.Timber

@Composable
fun ActionsScreen(
    modifier: Modifier = Modifier,
    actionsViewModel: ActionsViewModel =
        rememberActionsViewModel(),
) {
    LaunchedEffect(Unit) {
        actionsViewModel.uiEvents.collect { event ->
            when (event) {
                is ActionsViewModel.UiEvent.ShowSnackbar -> {
                    AppSnackbar(event.message)
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            Timber.i("Screen is now out of view. Saving state...")
            actionsViewModel.save()
        }
    }

    GShockSmartSyncTheme {
        Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            ConstraintLayout(modifier = Modifier.fillMaxSize()) {
                val (title, actions) = createRefs()

                ScreenTitle(
                    text = "Music Remote",
                    modifier =
                        Modifier.constrainAs(title) {
                            top.linkTo(parent.top)
                            bottom.linkTo(actions.top)
                        }
                )

                ActionsContent(
                    modifier =
                        Modifier.constrainAs(actions) {
                            top.linkTo(title.bottom)
                            bottom.linkTo(parent.bottom)
                            height = Dimension.fillToConstraints
                        },
                    actionsViewModel = actionsViewModel
                )
            }
        }
    }
}

@Composable
private fun ActionsContent(
    modifier: Modifier = Modifier,
    actionsViewModel: ActionsViewModel
) {
    val volLow by actionsViewModel.volumeLow.collectAsState()
    val volHigh by actionsViewModel.volumeHigh.collectAsState()
    val context = LocalContext.current

    Column(
        modifier =
            modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .fillMaxWidth()
                .fillMaxSize()
    ) { 
        VolumeSettings(
            volLow = volLow,
            volHigh = volHigh,
            onLowChange = { actionsViewModel.updateVolumeLow(it) },
            onHighChange = { actionsViewModel.updateVolumeHigh(it) }
        )
        MusicRemoteHelpView()

        Spacer(modifier = Modifier.height(24.dp))

        AppButton(
            text = "Exit App",
            onClick = { (context as? Activity)?.finish() },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun VolumeSettings(
    volLow: Float,
    volHigh: Float,
    onLowChange: (Float) -> Unit,
    onHighChange: (Float) -> Unit
) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            AppTextLarge(text = "Volume settings")
            
            Spacer(modifier = Modifier.height(8.dp))
            
            AppText(text = "Quiet volume: ${(volLow * 100).toInt()}%")
            Slider(
                value = volLow,
                onValueChange = onLowChange,
                valueRange = 0f..1f
            )

            Spacer(modifier = Modifier.height(8.dp))

            AppText(text = "Loud volume: ${(volHigh * 100).toInt()}%")
            Slider(
                value = volHigh,
                onValueChange = onHighChange,
                valueRange = 0f..1f
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ActionsScreenPreview() {
    ActionsScreen()
}
