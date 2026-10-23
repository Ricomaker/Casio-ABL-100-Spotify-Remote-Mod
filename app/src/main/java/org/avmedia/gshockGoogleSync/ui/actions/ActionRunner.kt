package org.avmedia.gshockGoogleSync.ui.actions

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import org.avmedia.gshockGoogleSync.data.repository.GShockRepository
import org.avmedia.gshockGoogleSync.utils.Utils
import org.avmedia.gshockapi.EventAction
import org.avmedia.gshockapi.ProgressEvents
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ActionRunner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: GShockRepository,
    val actionsViewModel: ActionsViewModel
) {
    fun setupActionSubscriptions() {
        val buttonActions = arrayOf(
            EventAction("ButtonPressedInfoReceived") {
                // Diagnostic logging
                Timber.d("Diagnostic: ButtonPressedInfoReceived triggered")
                
                // Use internal viewModelScope from ActionsViewModel
                actionsViewModel.viewModelScope.launch {
                    try {
                        val pressedButton = repository.getPressedButton()
                        Timber.d("Diagnostic: getPressedButton() = $pressedButton")
                        Timber.d("Diagnostic: isActionButtonPressed (Short D) = ${repository.isActionButtonPressed()}")
                        Timber.d("Diagnostic: isFindPhoneButtonPressed (Long D) = ${repository.isFindPhoneButtonPressed()}")
                        Timber.d("Diagnostic: isNormalButtonPressed (Long C) = ${repository.isNormalButtonPressed()}")
                        Timber.d("Diagnostic: isAutoTimeStarted = ${repository.isAutoTimeStarted()}")
                        Timber.d("Diagnostic: isAlwaysConnectedConnectionPressed = ${repository.isAlwaysConnectedConnectionPressed()}")

                        when {
                            repository.isActionButtonPressed() -> {
                                // Short D - Volume Toggle
                                actionsViewModel.cycleVolume()
                            }

                            repository.isFindPhoneButtonPressed() -> {
                                // Long D - Spotify Play/Pause (Reliable)
                                actionsViewModel.executeSpotifyPlayPause()
                            }

                            repository.isAutoTimeStarted() -> {
                                actionsViewModel.runActionsForAutoTimeSetting(context)
                            }

                            repository.isNormalButtonPressed() -> {
                                // Long C - Spotify Next Track
                                actionsViewModel.executeSpotifyNext()
                                Timber.i("Long C pressed - Spotify Next Track triggered")
                            }
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Error processing button press")
                    }
                }
            }
        )
        ProgressEvents.runEventActions(Utils.AppHashCode(), buttonActions)

        // Triggered by messages rather than button presses, e.g. "FindPhone" on always-connected watches
        val otherActions = arrayOf(
            EventAction("RunActions") {
                actionsViewModel.runActionsForActionButton(context)
            }
        )
        ProgressEvents.runEventActions(Utils.AppHashCode(), otherActions)
        Timber.i("Action subscriptions registered at process scope (no UI required)")
    }
}
