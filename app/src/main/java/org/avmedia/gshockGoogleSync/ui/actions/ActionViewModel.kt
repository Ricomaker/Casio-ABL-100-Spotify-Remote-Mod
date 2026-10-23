package org.avmedia.gshockGoogleSync.ui.actions

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.view.KeyEvent
import androidx.camera.core.CameraSelector
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.DateFormat
import java.time.Clock
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.avmedia.gshockGoogleSync.R
import org.avmedia.gshockGoogleSync.data.repository.GShockRepository
import org.avmedia.gshockGoogleSync.scratchpad.ActionsStorage
import org.avmedia.gshockGoogleSync.services.NotificationMonitorService
import org.avmedia.gshockGoogleSync.services.NotificationProvider
import org.avmedia.gshockGoogleSync.ui.common.AppSnackbar
import org.avmedia.gshockGoogleSync.ui.events.CalendarEvents
import org.avmedia.gshockGoogleSync.ui.events.EventsModel
import org.avmedia.gshockGoogleSync.utils.LocalDataStorage
import org.avmedia.gshockGoogleSync.ui.common.IWatchFeatureManager
import org.avmedia.gshockGoogleSync.utils.CyrillicToLatin
import org.avmedia.gshockapi.model.Event
import org.avmedia.gshockapi.EventAction
import org.avmedia.gshockapi.ProgressEvents
import timber.log.Timber

/**
 * Application-scoped so that watch button presses are handled even when no UI is composed.
 *
 * This used to be a @HiltViewModel whose event subscription was registered by the
 * ActionRunner composable. That meant a process woken in the background by
 * GShockCompanionDeviceService (no Activity, no composition) had nobody listening for
 * "ButtonPressedInfoReceived", so presses were silently dropped until the user opened
 * the app. As a @Singleton created in GShockApplication.onCreate(), the subscription
 * exists for the whole process lifetime.
 */
@Singleton
class ActionsViewModel
@Inject
constructor(
        private val api: GShockRepository,
        private val prayerAlarmsHelper: PrayerAlarmsHelper,
        @param:ApplicationContext val appContext: Context,
        private val calendarEvents: CalendarEvents,
        private val actionsStorage: ActionsStorage,
        private val notificationProvider: NotificationProvider,
        private val watchTimeUpdater: WatchTimeUpdater,
        private val watchFeatureManager: IWatchFeatureManager
) {
    /** Replaces viewModelScope. Lives as long as the process. */
    internal val viewModelScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _actions = MutableStateFlow<List<Action>>(emptyList())
    val actions: StateFlow<List<Action>> = _actions

    private val _uiEvents = MutableSharedFlow<UiEvent>()
    val uiEvents: SharedFlow<UiEvent> = _uiEvents.asSharedFlow()

    private val actionMap = mutableMapOf<Class<out Action>, Action>()

    private val _volumeDirection = MutableStateFlow(LocalDataStorage.get(appContext, "volumeDirection", "DOWN") ?: "DOWN")
    val volumeDirection: StateFlow<String> = _volumeDirection

    private val _volumeLow = MutableStateFlow(LocalDataStorage.get(appContext, "volumeLow", "0.35")?.toFloat() ?: 0.35f)
    val volumeLow: StateFlow<Float> = _volumeLow

    private val _volumeHigh = MutableStateFlow(LocalDataStorage.get(appContext, "volumeHigh", "0.75")?.toFloat() ?: 0.75f)
    val volumeHigh: StateFlow<Float> = _volumeHigh

    private var volumeCycleIndex = 0

    fun updateVolumeLow(value: Float) {
        val capped = value.coerceAtMost(_volumeHigh.value)
        _volumeLow.value = capped
        LocalDataStorage.put(appContext, "volumeLow", capped.toString())
    }

    fun updateVolumeHigh(value: Float) {
        val capped = value.coerceAtLeast(_volumeLow.value)
        _volumeHigh.value = capped
        LocalDataStorage.put(appContext, "volumeHigh", capped.toString())
    }

    fun setVolumeDirection(direction: String) {
        _volumeDirection.value = direction
        LocalDataStorage.put(appContext, "volumeDirection", direction)
    }

    private var saveJob: Job? = null

    enum class RunMode {
        SYNC,
        ASYNC,
    }

    /**
     * Represents one-time UI events that should be handled by the UI layer (Fragment/Activity).
     *
     * These events are transient and are not part of the persistent state of the screen. Examples
     * include showing a Snackbar, navigation events, or showing a Toast.
     *
     * Usage:
     * - The ViewModel emits these events via a [SharedFlow].
     * - The UI observes the flow and performs the corresponding action (e.g., showing a message).
     */
    sealed class UiEvent {
        /**
         * Event to show a Snackbar with a specific message.
         * @property message The text message to display in the Snackbar.
         */
        data class ShowSnackbar(val message: String) : UiEvent()
    }

    private fun updateActionsAndMap(newActions: List<Action>) {
        val updatedActions = ArrayList<Action>()
        actionMap.clear()

        newActions.forEach { action ->
            updatedActions.add(action)
            actionMap[action::class.java] = action
        }
        _actions.value = updatedActions
    }

    fun <T : Action> updateAction(updatedAction: T) {
        _actions.update { currentList ->
            val newList = ArrayList(currentList)
            val index = newList.indexOfFirst { it::class == updatedAction::class }
            if (index != -1) {
                newList[index] = updatedAction
                actionMap[updatedAction::class.java] = updatedAction
            }
            newList
        }

        viewModelScope.launch {
            updatedAction.save(appContext, actionsStorage)

            saveJob?.cancel()
            saveJob =
                    viewModelScope.launch {
                        delay(0)
                        actionsStorage.save()
                        _uiEvents.emit(
                                UiEvent.ShowSnackbar(appContext.getString(R.string.actions_saved))
                        )
                        saveJob = null
                    }
        }
    }

    /**
     * No longer a ViewModel lifecycle callback. Kept so a pending debounced save can be
     * flushed explicitly if a caller ever needs to; the process-scoped instance is not
     * torn down on Activity destruction.
     */
    fun onCleared() {
        saveJob?.let {
            saveJob?.cancel()
            viewModelScope.launch { actionsStorage.save() }
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Action> getAction(type: Class<T>): T {
        return actionMap[type] as? T
                ?: throw IllegalStateException(
                        "Action of type ${type.simpleName} not found in actionMap."
                )
    }

    init {
        loadInitialActions()
        setupEventSubscription()
    }

    // Subscribe to watch initialization event to load data after connection
    private fun setupEventSubscription() {
        ProgressEvents.runEventActions(
                "ActionViewModel",
                arrayOf(
                        EventAction("WatchInitializationCompleted") {
                            viewModelScope.launch { updateActionsAndMap(loadData(appContext)) }
                        }
                )
        )
    }

    // Method to load the initial list of actions
    private fun loadInitialActions() {
        val initialActions = buildList {
            add(SetTimeAction(appContext.getString(R.string.set_time), true, watchTimeUpdater))
            add(NextTrack(appContext.getString(R.string.next_track), true))
            add(PreviousTrack("Previous Track", true))
            add(TogglePlayPauseAction(appContext.getString(R.string.play_pause), true))
        }

        // Populate both _actions and actionMap immediately
        updateActionsAndMap(initialActions)
    }

    enum class RunEnvironment {
        NORMAL_CONNECTION, // Connected by long-pressing the LOWER-LEFT button
        ACTION_BUTTON_PRESSED, // Connected by short-pressing the LOWER-RIGHT button
        AUTO_TIME_ADJUSTMENT, // Connected automatically during auto time update
        FIND_PHONE_PRESSED, // The user has activated the "Find Phone" function
        ALWAYS_CONNECTED, // Some watches are always connected, but the watch keeps connecting and
        // disconnecting periodically.
    }

    abstract inner class Action(
            open var title: String,
            open var enabled: Boolean,
            var runMode: RunMode = RunMode.SYNC,
    ) {
        val ENABLED: String = ".enabled"

        open fun shouldRun(runEnvironment: RunEnvironment): Boolean {
            return when (runEnvironment) {
                RunEnvironment.ACTION_BUTTON_PRESSED -> enabled
                RunEnvironment.NORMAL_CONNECTION -> false
                RunEnvironment.AUTO_TIME_ADJUSTMENT -> false
                RunEnvironment.FIND_PHONE_PRESSED -> false
                RunEnvironment.ALWAYS_CONNECTED -> false
            }
        }

        abstract fun run(context: Context)

        open suspend fun save(context: Context, actionsStorage: ActionsStorage) {
            val key = this.javaClass.simpleName + ENABLED
            val value = enabled

            val actionEnum =
                    when (this) {
                        is SetTimeAction -> ActionsStorage.Action.SET_TIME
                        is SetEventsAction -> ActionsStorage.Action.REMINDERS
                        is FindPhoneAction -> ActionsStorage.Action.PHONE_FINDER
                        is PhotoAction -> ActionsStorage.Action.TAKE_PHOTO
                        is ToggleFlashlightAction -> ActionsStorage.Action.FLASHLIGHT
                        is StartVoiceAssistAction -> ActionsStorage.Action.VOICE_ASSIST
                        is NextTrack -> ActionsStorage.Action.SKIP_TO_NEXT_TRACK
                        is PreviousTrack -> ActionsStorage.Action.SKIP_TO_PREVIOUS_TRACK
                        is TogglePlayPauseAction -> ActionsStorage.Action.TOGGLE_PLAY_PAUSE
                        is PrayerAlarmsAction -> ActionsStorage.Action.PRAYER_ALARMS
                        is PhoneDialAction -> ActionsStorage.Action.PHONE_CALL
                        else -> null
                    }

            actionEnum?.let { actionsStorage.update(it, enabled) }
        }

        open suspend fun load(context: Context, actionsStorage: ActionsStorage) {
            val actionEnum =
                    when (this) {
                        is SetTimeAction -> ActionsStorage.Action.SET_TIME
                        is SetEventsAction -> ActionsStorage.Action.REMINDERS
                        is FindPhoneAction -> ActionsStorage.Action.PHONE_FINDER
                        is PhotoAction -> ActionsStorage.Action.TAKE_PHOTO
                        is ToggleFlashlightAction -> ActionsStorage.Action.FLASHLIGHT
                        is StartVoiceAssistAction -> ActionsStorage.Action.VOICE_ASSIST
                        is NextTrack -> ActionsStorage.Action.SKIP_TO_NEXT_TRACK
                        is PreviousTrack -> ActionsStorage.Action.SKIP_TO_PREVIOUS_TRACK
                        is TogglePlayPauseAction -> ActionsStorage.Action.TOGGLE_PLAY_PAUSE
                        is PrayerAlarmsAction -> ActionsStorage.Action.PRAYER_ALARMS
                        is PhoneDialAction -> ActionsStorage.Action.PHONE_CALL
                        else -> null
                    }

            actionEnum?.let { enabled = actionsStorage.getAction(it) }
            Timber.d("Load value: ${this.javaClass.simpleName}, $enabled")
        }

        open fun validate(context: Context): Boolean {
            return true
        }
    }

    inner class SetEventsAction(
            override var title: String,
            override var enabled: Boolean,
            val api: GShockRepository,
            val calendarEvents: CalendarEvents
    ) : Action(title, enabled, RunMode.ASYNC) {

        override fun shouldRun(runEnvironment: RunEnvironment): Boolean {
            return when (runEnvironment) {
                RunEnvironment.NORMAL_CONNECTION -> enabled && watchFeatureManager.isFeatureSupported("actions.reminders")
                RunEnvironment.ACTION_BUTTON_PRESSED -> enabled && watchFeatureManager.isFeatureSupported("actions.reminders")
                RunEnvironment.AUTO_TIME_ADJUSTMENT -> enabled && watchFeatureManager.isFeatureSupported("actions.reminders")
                RunEnvironment.FIND_PHONE_PRESSED -> false
                RunEnvironment.ALWAYS_CONNECTED -> false
            }
        }

        override fun run(context: Context) {
            Timber.d("running ${this.javaClass.simpleName}")
            EventsModel.refresh(calendarEvents.getEventsFromCalendar())

            val eventTransformers: List<(List<Event>) -> List<Event>> = listOf(
                { events ->
                    events.map { event ->
                        event.copy(title = CyrillicToLatin.transliterate(event.title))
                    }
                }
            )

            val processedEvents = eventTransformers.fold(EventsModel.events) { currentEvents, transformer ->
                ArrayList(transformer(currentEvents))
            }

            api.setEvents(processedEvents)
        }

        override suspend fun load(context: Context, actionsStorage: ActionsStorage) {
            enabled = actionsStorage.getAction(ActionsStorage.Action.REMINDERS)
        }
    }

    inner class ToggleFlashlightAction(override var title: String, override var enabled: Boolean) :
            Action(title, enabled) {

        override fun run(context: Context) {
            Timber.d("running ${this.javaClass.simpleName}")
            FlashlightHelper.toggle(context)
        }

        override suspend fun load(context: Context, actionsStorage: ActionsStorage) {
            enabled = actionsStorage.getAction(ActionsStorage.Action.FLASHLIGHT)
        }
    }

    inner class FindPhoneAction(override var title: String, override var enabled: Boolean) :
            Action(title, enabled) {

        override fun shouldRun(runEnvironment: RunEnvironment): Boolean {
            return when (runEnvironment) {
                RunEnvironment.NORMAL_CONNECTION -> false
                RunEnvironment.ACTION_BUTTON_PRESSED -> enabled && watchFeatureManager.isFeatureSupported("actions.find_phone")
                RunEnvironment.AUTO_TIME_ADJUSTMENT -> false
                RunEnvironment.FIND_PHONE_PRESSED -> true
                RunEnvironment.ALWAYS_CONNECTED -> false
            }
        }

        override fun run(context: Context) {
            Timber.d("running ${this.javaClass.simpleName}")
            PhoneFinder.ring(context)
        }

        override suspend fun load(context: Context, actionsStorage: ActionsStorage) {
            enabled = actionsStorage.getAction(ActionsStorage.Action.PHONE_FINDER)
        }
    }

    inner class SetTimeAction(
            override var title: String,
            override var enabled: Boolean,
            val watchTimeUpdater: WatchTimeUpdater
    ) :
            Action(
                    title,
                    enabled,
                    RunMode.ASYNC,
            ) {

        private var lastSet: Long? = null

        override fun shouldRun(runEnvironment: RunEnvironment): Boolean {
            // update every hour
            val setTimeConditionAlwaysConnected =
                    (watchFeatureManager.isFeatureSupported("time_adjustment.always_connected") &&
                            (lastSet == null ||
                                    System.currentTimeMillis() - lastSet!! > 1000 * 60 * 60))

            return when (runEnvironment) {
                RunEnvironment.NORMAL_CONNECTION -> false
                RunEnvironment.ACTION_BUTTON_PRESSED -> enabled
                RunEnvironment.AUTO_TIME_ADJUSTMENT -> true
                RunEnvironment.FIND_PHONE_PRESSED -> false
                RunEnvironment.ALWAYS_CONNECTED -> setTimeConditionAlwaysConnected
            }
        }

        override fun run(context: Context) {
            Timber.d("running ${this.javaClass.simpleName}")

            // actions are sun on the main lifecycle scope, because the Actions Fragment never gets
            // created.
            CoroutineScope(Dispatchers.Main).launch {
                watchTimeUpdater.updateTime()
                lastSet = System.currentTimeMillis()
            }
        }

        override suspend fun load(context: Context, actionsStorage: ActionsStorage) {
            enabled = actionsStorage.getAction(ActionsStorage.Action.SET_TIME)
        }
    }

    inner class SetLocationAction(override var title: String, override var enabled: Boolean) :
            Action(title, enabled) {
        override fun run(context: Context) {
            Timber.d("running ${this.javaClass.simpleName}")
        }
    }

    inner class StartVoiceAssistAction(
            override var title: String,
            override var enabled: Boolean,
    ) : Action(title, enabled, RunMode.SYNC) {
        override fun run(context: Context) {
            Timber.d("running ${this.javaClass.simpleName}")
            runCatching {
                val intent =
                        Intent(Intent.ACTION_VOICE_COMMAND).apply {
                            flags =
                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                            Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        }
                context.startActivity(intent)
            }
                    .onFailure {
                        if (it is ActivityNotFoundException) {
                            AppSnackbar(
                                    context.getString(
                                            R.string.voice_assistant_not_available_on_this_device
                                    )
                            )
                        }
                    }
        }
    }

    inner class NextTrack(
            override var title: String,
            override var enabled: Boolean,
    ) : Action(title, enabled, RunMode.ASYNC) {
        override fun run(context: Context) {
            Timber.d("running ${this.javaClass.simpleName}")
            runCatching {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val eventTime = SystemClock.uptimeMillis()

                val downEvent =
                        KeyEvent(
                                eventTime,
                                eventTime,
                                KeyEvent.ACTION_DOWN,
                                KeyEvent.KEYCODE_MEDIA_NEXT,
                                0
                        )
                audioManager.dispatchMediaKeyEvent(downEvent)

                val upEvent =
                        KeyEvent(
                                eventTime,
                                eventTime,
                                KeyEvent.ACTION_UP,
                                KeyEvent.KEYCODE_MEDIA_NEXT,
                                0
                        )
                audioManager.dispatchMediaKeyEvent(upEvent)
            }
                    .onFailure {
                        if (it is ActivityNotFoundException) {
                            AppSnackbar(context.getString(R.string.cannot_go_to_next_track))
                        }
                    }
        }
    }

    inner class PreviousTrack(
            override var title: String,
            override var enabled: Boolean,
    ) : Action(title, enabled, RunMode.ASYNC) {
        override fun run(context: Context) {
            Timber.d("running ${this.javaClass.simpleName}")
            runCatching {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val eventTime = SystemClock.uptimeMillis()

                val downEvent =
                        KeyEvent(
                                eventTime,
                                eventTime,
                                KeyEvent.ACTION_DOWN,
                                KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                                0
                        )
                audioManager.dispatchMediaKeyEvent(downEvent)

                val upEvent =
                        KeyEvent(
                                eventTime,
                                eventTime,
                                KeyEvent.ACTION_UP,
                                KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                                0
                        )
                audioManager.dispatchMediaKeyEvent(upEvent)
            }
                    .onFailure {
                        if (it is ActivityNotFoundException) {
                            AppSnackbar("Cannot go to previous track")
                        }
                    }
        }

        override suspend fun load(context: Context, actionsStorage: ActionsStorage) {
            enabled = actionsStorage.getAction(ActionsStorage.Action.SKIP_TO_PREVIOUS_TRACK)
        }
    }

    inner class TogglePlayPauseAction(
            override var title: String,
            override var enabled: Boolean,
    ) : Action(title, enabled, RunMode.ASYNC) {
        override fun run(context: Context) {
            Timber.d("running ${this.javaClass.simpleName}")
            runCatching {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val eventTime = SystemClock.uptimeMillis()

                val downEvent =
                        KeyEvent(
                                eventTime,
                                eventTime,
                                KeyEvent.ACTION_DOWN,
                                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                                0
                        )
                audioManager.dispatchMediaKeyEvent(downEvent)

                val upEvent =
                        KeyEvent(
                                eventTime,
                                eventTime,
                                KeyEvent.ACTION_UP,
                                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                                0
                        )
                audioManager.dispatchMediaKeyEvent(upEvent)
            }
                    .onFailure {
                        Timber.e(it, "Could not toggle play/pause")
                    }
        }

        override suspend fun load(context: Context, actionsStorage: ActionsStorage) {
            enabled = actionsStorage.getAction(ActionsStorage.Action.TOGGLE_PLAY_PAUSE)
        }
    }

    inner class PrayerAlarmsAction(
            override var title: String,
            override var enabled: Boolean,
            val api: GShockRepository,
            val prayerAlarmsHelper: PrayerAlarmsHelper
    ) : Action(title, enabled, RunMode.ASYNC) {

        private var lastSet: Long? = null

        override fun shouldRun(runEnvironment: RunEnvironment): Boolean {
            // update every 6 hours
            val setTimeConditionAlwaysConnected =
                    (enabled &&
                            watchFeatureManager.isFeatureSupported("time_adjustment.always_connected") &&
                            (lastSet == null ||
                                    System.currentTimeMillis() - lastSet!! > 6000 * 60 * 60))

            return when (runEnvironment) {
                RunEnvironment.NORMAL_CONNECTION -> enabled
                RunEnvironment.ACTION_BUTTON_PRESSED -> enabled
                RunEnvironment.AUTO_TIME_ADJUSTMENT -> enabled
                RunEnvironment.FIND_PHONE_PRESSED -> false
                RunEnvironment.ALWAYS_CONNECTED -> setTimeConditionAlwaysConnected
            }
        }

        override fun run(context: Context) {
            Timber.d("running ${this.javaClass.simpleName}")
            // vvv 4. CALL THE METHOD ON THE INSTANCE vvv
            CoroutineScope(Dispatchers.Main).launch {
                prayerAlarmsHelper
                        .createNextPrayerAlarms(watchFeatureManager.getAlarmCount())
                        .onSuccess { alarms ->
                            // getAlarms need to be run first, otherwise setAlarms() will not work
                            api.getAlarms()
                            api.setAlarms(ArrayList(alarms))
                            lastSet = System.currentTimeMillis()
                        }
                        .onFailure { error ->
                            Timber.e("Could not set prayer alarms: ${error.message}")
                            AppSnackbar("Failed to set prayer alarms: ${error.message}")
                        }
            }
        }
    }

    inner class Separator(override var title: String, override var enabled: Boolean) :
            Action(title, enabled) {
        override fun run(context: Context) {
            Timber.d("running ${this.javaClass.simpleName}")
        }

        override suspend fun load(context: Context, actionsStorage: ActionsStorage) {
            // Do nothing.
        }
    }

    inner class MapAction(override var title: String, override var enabled: Boolean) :
            Action(title, enabled) {
        override fun run(context: Context) {
            Timber.d("running ${this.javaClass.simpleName}")
        }
    }

    inner class PhoneDialAction(
            override var title: String,
            override var enabled: Boolean,
            var phoneNumber: String
    ) : Action(title, enabled) {
        init {
            Timber.d("PhoneDialAction")
        }

        override fun run(context: Context) {
            Timber.d("running ${this.javaClass.simpleName}")

            val dialIntent =
                    Intent(Intent.ACTION_CALL)
                            .setFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                            Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                            )
            dialIntent.data = "tel:$phoneNumber".toUri()
            context.startActivity(dialIntent)
        }

        override suspend fun save(context: Context, actionsStorage: ActionsStorage) {
            super.save(context, actionsStorage)
            val key = this.javaClass.simpleName + ".phoneNumber"
            LocalDataStorage.put(context, key, phoneNumber)
        }

        override suspend fun load(context: Context, actionsStorage: ActionsStorage) {
            super.load(context, actionsStorage)
            val key = this.javaClass.simpleName + ".phoneNumber"
            phoneNumber = LocalDataStorage.get(context, key, "").toString()
        }

        override fun validate(context: Context): Boolean {
            if (phoneNumber.isEmpty()) {
                Timber.e("Phone number cannot be empty!")
                return false
            }

            return true
        }
    }

    enum class CameraOrientation {
        FRONT,
        BACK
    }

    inner class PhotoAction(
            override var title: String,
            override var enabled: Boolean,
            var cameraOrientation: CameraOrientation,
    ) : Action(title, enabled, RunMode.ASYNC) {
        init {
            Timber.d("PhotoAction: orientation: $cameraOrientation")
        }

        override fun run(context: Context) {
            Timber.d("running ${this.javaClass.simpleName}")

            val cameraSelector =
                    when (cameraOrientation) {
                        CameraOrientation.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
                        CameraOrientation.BACK -> CameraSelector.DEFAULT_BACK_CAMERA
                    }
            val cameraHelper = CameraCaptureHelper(context, cameraSelector)

            // Launch a coroutine to take the picture
            CoroutineScope(Dispatchers.Main).launch {
                cameraHelper
                        .takePicture()
                        .onSuccess { result ->
                            AppSnackbar(context.getString(R.string.image_captured, result))
                        }
                        .onFailure { error ->
                            AppSnackbar(
                                    context.getString(
                                            R.string.camera_capture_error,
                                            error.message ?: "Unknown error"
                                    )
                            )
                        }
            }
        }

        override suspend fun save(context: Context, actionsStorage: ActionsStorage) {
            super.save(context, actionsStorage)
            val key = this.javaClass.simpleName + ".cameraOrientation"
            LocalDataStorage.put(context, key, cameraOrientation.toString())
        }

        override suspend fun load(context: Context, actionsStorage: ActionsStorage) {
            super.load(context, actionsStorage)
            val key = this.javaClass.simpleName + ".cameraOrientation"
            cameraOrientation =
                    if (LocalDataStorage.get(context, key, "BACK").toString() == "BACK")
                            CameraOrientation.BACK
                    else CameraOrientation.FRONT
        }
    }

    private fun runIt(action: Action, context: Context) {
        runCatching { action.run(context) }.onFailure {
            Timber.e(it, "Action failed: '${action.title}' (${action.javaClass.simpleName})")
            when (it) {
                is SecurityException ->
                        AppSnackbar(
                                context.getString(
                                        R.string.you_have_not_given_permission_to_to_run_action,
                                        action.title
                                )
                        )
                else -> AppSnackbar("Could not run action ${action.title}. Reason: $it")
            }
        }
    }

    fun runActionsForActionButton(context: Context) {
        viewModelScope.launch {
            val actions =
                    _actions.value.filter { it.shouldRun(RunEnvironment.ACTION_BUTTON_PRESSED) }
            ProgressEvents.onNext("ActionNames", actions.map { it.title })
            runFilteredActions(context, actions)
        }
    }

    fun runActionForConnection(context: Context) {
        viewModelScope.launch {
            runFilteredActions(
                    context,
                    _actions.value.filter { it.shouldRun(RunEnvironment.NORMAL_CONNECTION) }
            )
        }
    }

    fun runActionForAlwaysConnected(context: Context) {
        viewModelScope.launch {
            runFilteredActions(
                    context,
                    _actions.value.filter { it.shouldRun(RunEnvironment.ALWAYS_CONNECTED) }
            )
        }
    }

    fun runActionsForAutoTimeSetting(context: Context) {
        viewModelScope.launch {
            val actions =
                    _actions.value.filter { it.shouldRun(RunEnvironment.AUTO_TIME_ADJUSTMENT) }
            ProgressEvents.onNext("ActionNames", actions.map { it.title })

            runFilteredActions(
                    context,
                    _actions.value.filter { it.shouldRun(RunEnvironment.AUTO_TIME_ADJUSTMENT) }
            )

            // show notification if configured
            if (LocalDataStorage.getTimeAdjustmentNotification(context) &&
                            !watchFeatureManager.isFeatureSupported("time_adjustment.always_connected")
            ) { // only create notification for not-always connected watches.
                showTimeSyncNotification()
            }
        }
    }
    fun runNextTrack(context: Context) {
        val nextTrack = getAction(NextTrack::class.java)
        runIt(nextTrack, context)
    }

    fun runPreviousTrack(context: Context) {
        val previousTrack = getAction(PreviousTrack::class.java)
        runIt(previousTrack, context)
    }

    fun volumeDownTwoSteps(context: Context) {
        Timber.i("!!! EXECUTING: Volume Down (2 steps)")
        val audioManager =
            context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        repeat(2) {
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_LOWER,
                0
            )
        }
    }

    fun cycleVolume() {
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        
        val targetPercent = if (volumeCycleIndex == 0) _volumeLow.value else _volumeHigh.value
        val targetVolume = (maxVolume * targetPercent).toInt()
        
        Timber.i("Cycling Volume: Step $volumeCycleIndex, Target ${targetPercent * 100}%, Value $targetVolume/$maxVolume")
        
        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            targetVolume,
            0
        )
        
        volumeCycleIndex = (volumeCycleIndex + 1) % 2
    }

    fun executeVolumeUp() {
        volumeUpTwoSteps(appContext)
    }

    fun executeVolumeDown() {
        volumeDownTwoSteps(appContext)
    }

    private fun getActiveController(): MediaController? {
        val sessionManager = appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val componentName = ComponentName(appContext, NotificationMonitorService::class.java)
        return try {
            val sessions = sessionManager.getActiveSessions(componentName)
            // 1. Priority: Any session that is currently PLAYING
            val playingSession = sessions.find { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            if (playingSession != null) {
                Timber.d("Active playing session found: ${playingSession.packageName}")
                return playingSession
            }
            
            // 2. Secondary: Spotify (even if paused) to give it "bite"
            val spotifySession = sessions.find { it.packageName == "com.spotify.music" }
            if (spotifySession != null) {
                Timber.d("Spotify background session found")
                return spotifySession
            }

            // 3. Fallback: The most recent active session
            sessions.firstOrNull()
        } catch (e: SecurityException) {
            Timber.e(e, "Notification Access not granted - cannot get active sessions")
            null
        }
    }

    fun executeSpotifyPlayPause() {
        val controller = getActiveController()
        if (controller != null) {
            val state = controller.playbackState?.state
            Timber.d("Active controller found: ${controller.packageName}. State: $state")
            if (state == PlaybackState.STATE_PLAYING) {
                Timber.d("Action: Pause")
                controller.transportControls.pause()
            } else {
                Timber.d("Action: Play")
                controller.transportControls.play()
            }
        } else {
            Timber.d("No media session found — using generic fallback")
            executePlayPause()
        }
    }

    fun executeSpotifyNext() {
        val controller = getActiveController()
        if (controller != null) {
            Timber.d("Active controller found: ${controller.packageName}. Action: Next")
            controller.transportControls.skipToNext()
        } else {
            Timber.d("No media session found — using generic fallback")
            executeNextTrack()
        }
    }

    fun executePlayPause() {
        Timber.d("Direct execution of Play/Pause")
        runCatching {
            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val eventTime = SystemClock.uptimeMillis()

            val downEvent = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0)
            audioManager.dispatchMediaKeyEvent(downEvent)

            val upEvent = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0)
            audioManager.dispatchMediaKeyEvent(upEvent)
        }.onFailure {
            Timber.e(it, "Could not toggle play/pause")
        }
    }

    fun executeNextTrack() {
        Timber.d("Direct execution of Next Track")
        runCatching {
            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val eventTime = SystemClock.uptimeMillis()

            val downEvent = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT, 0)
            audioManager.dispatchMediaKeyEvent(downEvent)

            val upEvent = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT, 0)
            audioManager.dispatchMediaKeyEvent(upEvent)
        }.onFailure {
            Timber.e(it, "Could not skip track")
        }
    }

    fun volumeUpTwoSteps(context: Context) {
        Timber.i("!!! EXECUTING: Volume Up (2 steps)")
        val audioManager =
            context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        repeat(2) {
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_RAISE,
                0
            )
        }
    }

    fun runActionFindPhone(context: Context) {
        val actionsToRun = _actions.value.filter { it.shouldRun(RunEnvironment.FIND_PHONE_PRESSED) }
        runFilteredActions(context, actionsToRun)
    }

    private fun showTimeSyncNotification() {
        val dateStr =
                DateFormat.getDateTimeInstance().format(Date(Clock.systemDefaultZone().millis()))
        val watchName = watchFeatureManager.getWatchName()
        val text = "Time set at $dateStr for $watchName watch"

        notificationProvider.createNotification(
                NotificationProvider.NotificationContent(title = "ABL-100 Media Control", text = text)
        )
    }

    private fun runFilteredActions(context: Context, filteredActions: List<Action>) {

        filteredActions.sortedWith(compareBy { it.runMode.ordinal }) // run SYNC actions first
                .forEach {
                    if (it.runMode == RunMode.ASYNC) {
                        Timber.d("------------> running ${it.javaClass.simpleName}")
                        // actions are run on the main lifecycle scope, because the Actions Fragment
                        // never gets created.
                        // Using GlobalScope or a custom scope here to ensure it runs even if VM is
                        // cleared,
                        // but ideally this should be tied to a service or work manager.
                        // For now, mirroring previous behavior but avoiding the custom static
                        // scope.
                        CoroutineScope(Dispatchers.Main).launch {
                            println("Running action ASYNC: ${it.title}")
                            runIt(it, context)
                        }
                    } else {
                        println("Running action SYNC: ${it.title}")
                        runIt(it, context)
                    }
                }
    }

    private suspend fun loadData(context: Context): List<Action> {
        // Load data from watch
        actionsStorage.load()

        if (api.isScratchpadReset()) {
            _actions.value.forEach { it.save(context, actionsStorage) }
            actionsStorage.save()
        } else {
            _actions.value.forEach { it.load(context, actionsStorage) }
        }

        LocalDataStorage.put(context, "ActionsInitialized", "true")

        return _actions.value
    }

    fun save() {
        viewModelScope.launch { actionsStorage.save() }
    }

    fun saveWithMessage(message: String) {
        viewModelScope.launch {
            actionsStorage.save()
            AppSnackbar(message)
        }
    }
}
