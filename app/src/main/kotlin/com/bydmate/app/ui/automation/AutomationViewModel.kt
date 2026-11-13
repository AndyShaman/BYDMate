package com.bydmate.app.ui.automation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteException
import android.content.Intent
import android.os.Environment
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bydmate.app.BuildConfig
import com.bydmate.app.R
import com.bydmate.app.util.AppStrings
import com.bydmate.app.util.appLocalizedContext
import com.bydmate.app.data.automation.AutomationEngine
import com.bydmate.app.data.automation.PowerStateRuleMigration
import com.bydmate.app.data.automation.RuleDraftValidator
import com.bydmate.app.data.automation.RuleInserts
import com.bydmate.app.data.automation.RuleJournal
import com.bydmate.app.data.automation.RuleParseResult
import com.bydmate.app.data.automation.RuleShare
import com.bydmate.app.data.automation.RuleShareFiles
import com.bydmate.app.data.automation.RuleShareUrl
import com.bydmate.app.data.automation.SharedRule
import com.bydmate.app.data.automation.TriggerValidationError
import com.bydmate.app.voice.VoiceUserPhrases
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.dao.RuleLogDao
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.PlaceEntity
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.RuleLogEntity
import com.bydmate.app.data.local.entity.TriggerDef
import com.bydmate.app.data.repository.PlaceRepository
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.loop.TimedSnapshot
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.telegram.withReportRuleName
import com.bydmate.app.data.vehicle.RearSeat
import com.bydmate.app.data.vehicle.VehicleApi
import com.bydmate.app.service.TrackingService
import com.bydmate.app.ui.overlay.OverlayNotificationManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import javax.inject.Inject

enum class RuleFilter { ALL, ENABLED, DISABLED }

/** How the rule list is drawn: one compact row per rule, or a grid of cards. */
enum class RuleViewMode {
    LIST, GRID;

    companion object {
        /** Key in the «automation» prefs; the choice survives app restarts. */
        internal const val KEY = "rule_view_mode"

        fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences(AutomationEngine.PREFS_NAME, Context.MODE_PRIVATE)

        /** A missing, unknown or wrongly typed value falls back to [LIST]. */
        fun read(prefs: SharedPreferences): RuleViewMode {
            val stored = try {
                prefs.getString(KEY, null)
            } catch (_: ClassCastException) {
                null
            }
            return entries.firstOrNull { it.name == stored } ?: LIST
        }
    }
}

// --- ViewModel ---

data class EditingRule(
    val id: Long = 0,
    val name: String = "",
    val triggerLogic: String = "AND",
    val triggers: List<TriggerDef> = emptyList(),
    val actions: List<ActionDef> = emptyList(),
    val cooldownSeconds: Int = 60,
    val requirePark: Boolean = false,
    val confirmBeforeExecute: Boolean = false,
    val fireOncePerTrip: Boolean = false,
    val playSound: Boolean = false,
    val isNew: Boolean = true,
    /** The rule's switch when the editor opened: «Сохранить как новое» keeps it. */
    val enabled: Boolean = true,
    /** A save is in flight: the draft is frozen, [AutomationViewModel.updateEditing] ignores edits. */
    val saving: Boolean = false,
) {
    fun toShared() = SharedRule(
        name = name.trim(),
        triggerLogic = triggerLogic,
        triggers = triggers,
        actions = actions,
        cooldownSeconds = cooldownSeconds,
        requirePark = requirePark,
        confirmBeforeExecute = confirmBeforeExecute,
        fireOncePerTrip = fireOncePerTrip,
        playSound = playSound,
    )

    /** [base] with what the editor edits; enabled, counters and creation time stay as in [base]. */
    fun applyTo(base: RuleEntity) = base.copy(
        name = name.trim(),
        triggerLogic = triggerLogic,
        triggers = TriggerDef.listToJson(triggers),
        actions = ActionDef.listToJson(actions),
        cooldownSeconds = cooldownSeconds.coerceAtLeast(1),
        requirePark = requirePark,
        confirmBeforeExecute = confirmBeforeExecute,
        fireOncePerTrip = fireOncePerTrip,
        playSound = playSound,
    )
}

/**
 * Step 2 of the import dialog: the parsed rule as it will be added, its [preview] lines, plus
 * the user's choices. [token] tells one parsed file from the next: a place or contact picked
 * for an older draft is dropped instead of landing on the same index of a new one. While
 * [saving] the insert runs and the draft is frozen: no edits, no «Отмена».
 */
data class RuleImportDraft(
    val rule: SharedRule,
    val preview: RuleImportPreview,
    val enableNow: Boolean = false,
    val error: String? = null,
    val token: Long = 0,
    val saving: Boolean = false,
)

data class AutomationUiState(
    val rules: List<RuleEntity> = emptyList(),
    val filter: RuleFilter = RuleFilter.ALL,
    val viewMode: RuleViewMode = RuleViewMode.LIST,
    val logs: List<RuleLogEntity> = emptyList(),
    val showEditor: Boolean = false,
    val showJournal: Boolean = false,
    val editing: EditingRule = EditingRule(),
    val showDeleteConfirm: Long? = null,
    val places: List<PlaceEntity> = emptyList(),
    val editorError: String? = null,
    /** The rule being edited was deleted meanwhile: the editor says so and closes on «Закрыть». */
    val editorRuleDeleted: Boolean = false,
    /** «Поделиться» tapped: non-null shows what the file keeps, before anything is written. */
    val pendingShare: SharedRule? = null,
    /** Import step 1: non-null shows the list of share files in Download. */
    val importFiles: List<File>? = null,
    /** Why the picked file could not be imported, shown under the step 1 list. */
    val importError: String? = null,
    /** Import step 2: non-null shows the preview. */
    val importDraft: RuleImportDraft? = null,
    val testRunning: Boolean = false,
    /** A share file is being written: the «Поделиться» buttons wait for it. */
    val shareInProgress: Boolean = false,
    /** The Telegram bot is connected in Settings: the report dialog warns when it is not. */
    val tgBotConnected: Boolean = false,
    /** The newest journal entry of each rule, by rule id: the status line on its card. */
    val lastLogs: Map<Long, RuleLogEntity> = emptyMap(),
    /** The journal shows only this rule's entries: opened from the rule's status line. */
    val journalRuleId: Long? = null,
    val ruleLogs: List<RuleLogEntity> = emptyList(),
    /** A short note at the bottom of the tab (rule limit, test run refused); the screen hides it. */
    val message: String? = null,
    /** Rear seats whose heat this car reports (#327): the editor offers only their tiles. */
    val rearSeats: Set<RearSeat> = emptySet(),
)

@HiltViewModel
@Suppress("LargeClass") // the whole Automation tab: rules, editor, import/share, journal
class AutomationViewModel @Inject @Suppress("LongParameterList") constructor( // Hilt-injected dependencies
    private val ruleDao: RuleDao,
    private val ruleLogDao: RuleLogDao,
    private val placeRepository: PlaceRepository,
    private val vehicleApi: VehicleApi,
    private val actionDispatcher: ActionDispatcher,
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(AutomationUiState(viewMode = RuleViewMode.read(RuleViewMode.prefs(context))))
    val uiState: StateFlow<AutomationUiState> = _uiState.asStateFlow()

    // Test seams: tests point these at a temp dir, a test dispatcher, the telemetry and a
    // recorded share sheet (FileProvider caches its roots per process, so a real one leaks
    // between Robolectric tests).
    internal var downloadsDir: () -> File = {
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    }
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    internal var liveSnapshot: () -> DiParsData? = { TrackingService.lastData.value }
    internal var liveSample: () -> TimedSnapshot? = { TrackingService.lastSample }
    // The user's own phrases for built-in commands (normalized phrase -> command name): a voice
    // trigger may not reuse one. Read fresh from SharedPreferences on every save.
    internal var userCommandPhrases: () -> Map<String, String> = { VoiceUserPhrases(context).owners() }
    internal var serviceRunning: () -> Boolean = { TrackingService.isRunning.value }
    internal var elapsedNow: () -> Long = { SystemClock.elapsedRealtime() }
    internal var shareSheet: (File) -> Unit = { startShareSheet(it) }

    private var testRunJob: Job? = null
    private var ruleJournalJob: Job? = null
    private var importJob: Job? = null
    private var draftToken = 0L

    // Bumped on every editor open and close: a save that returns late only touches its own editor.
    private var editorSession = 0L
    private val shareMutex = Mutex()

    /** Rule ids in the driver's own order, as last saved; empty = newest first. */
    private var ruleOrder: List<Long> = emptyList()

    init {
        viewModelScope.launch { insertStarterTemplatesIfNeeded() }
        viewModelScope.launch {
            ruleOrder = RuleOrder.parse(settingsRepository.getAutomationRuleOrder())
            ruleDao.getAll().collect { rules ->
                _uiState.update { it.copy(rules = RuleOrder.apply(ruleOrder, rules)) }
            }
        }
        viewModelScope.launch {
            combine(
                settingsRepository.observeString(SettingsRepository.KEY_TG_BACKUP_TOKEN),
                settingsRepository.observeString(SettingsRepository.KEY_TG_BACKUP_CHAT_ID),
            ) { token, chat -> !token.isNullOrBlank() && chat?.toLongOrNull() != null }
                .collect { connected -> _uiState.update { it.copy(tgBotConnected = connected) } }
        }
        viewModelScope.launch {
            ruleLogDao.getRecent(100).collect { logs ->
                _uiState.update { it.copy(logs = logs) }
            }
        }
        viewModelScope.launch {
            ruleLogDao.getLastPerRule().collect { last ->
                _uiState.update { it.copy(lastLogs = last.associateBy { l -> l.ruleId }) }
            }
        }
        viewModelScope.launch {
            placeRepository.getAll().collect { places ->
                _uiState.update { it.copy(places = places) }
            }
        }
    }

    fun setFilter(filter: RuleFilter) {
        _uiState.update { it.copy(filter = filter) }
    }

    fun setViewMode(mode: RuleViewMode) {
        _uiState.update { it.copy(viewMode = mode) }
        RuleViewMode.prefs(context).edit().putString(RuleViewMode.KEY, mode.name).apply()
    }

    /**
     * A rule dropped into [target]'s place (#249): the new order shows at once and is saved once,
     * as the whole list of ids, so the rules a filter hides keep their places too.
     */
    fun moveRule(moved: Long, target: Long) {
        val rules = _uiState.value.rules
        val ids = rules.map { it.id }
        val next = RuleOrder.move(ids, moved, target)
        if (next == ids) return
        ruleOrder = next
        _uiState.update { it.copy(rules = RuleOrder.apply(next, it.rules)) }
        Log.i("AutomationViewModel", "rule order: moved id=$moved from=${ids.indexOf(moved)} to=${next.indexOf(moved)}")
        viewModelScope.launch { settingsRepository.setAutomationRuleOrder(RuleOrder.serialize(next)) }
    }

    fun toggleEnabled(rule: RuleEntity) {
        viewModelScope.launch { ruleDao.setEnabled(rule.id, !rule.enabled) }
    }

    /**
     * "Выполнить сейчас" — run a single action immediately, for live testing from the
     * rule editor. A "toggle" action goes through [ActionDispatcher] (it needs the live
     * state to pick the direction, and the dispatcher already applies every safety gate);
     * a param action takes the direct write path below. Result is surfaced via Toast.
     * Bypasses the automation edge/cooldown logic by design — this is a manual action.
     */
    fun executeNow(action: ActionDef) {
        if (action.kind == "toggle") {
            viewModelScope.launch {
                val result = actionDispatcher.dispatch(action, TrackingService.lastData.value)
                val lc = context.appLocalizedContext()
                val msg = if (result.success) lc.getString(R.string.auto_msg_dispatch_sent)
                          else result.reason ?: lc.getString(R.string.auto_msg_unavailable)
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
            return
        }
        executeCommandNow(action.command)
    }

    /**
     * Dispatches a raw vehicle command through the native write channel. The raw
     * autoservice status (real action vs no-op) lands in logcat via HelperClient.
     */
    private fun executeCommandNow(command: String) {
        viewModelScope.launch {
            // Manual test button bypasses ActionDispatcher.dispatch, so apply the
            // same safety gates explicitly (frunk/unlock fail closed on unknown speed).
            val block = ActionDispatcher.safetyBlockReason(command, TrackingService.lastData.value)
            if (block != null) {
                Toast.makeText(context, block.toText(context), Toast.LENGTH_SHORT).show()
                return@launch
            }
            val result = vehicleApi.dispatch(command)
            val lc = context.appLocalizedContext()
            val msg = if (result.isSuccess) lc.getString(R.string.auto_msg_dispatch_sent)
                      else lc.getString(
                          R.string.auto_msg_dispatch_error,
                          result.exceptionOrNull()?.let { actionDispatcher.vehicleFailureReason(it) }
                              ?: lc.getString(R.string.auto_msg_unavailable),
                      )
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // --- Editor ---

    fun openNewRule() {
        if (_uiState.value.rules.size >= MAX_RULES) {
            showLimitMessage()
            return
        }
        editorSession++
        _uiState.update {
            it.copy(
                showEditor = true,
                editing = EditingRule(
                    triggers = emptyList(),
                    actions = emptyList()
                )
            )
        }
        loadRearSeats()
    }

    fun openEditRule(rule: RuleEntity) {
        editorSession++
        _uiState.update {
            it.copy(
                showEditor = true,
                editing = EditingRule(
                    id = rule.id,
                    name = rule.name,
                    triggerLogic = rule.triggerLogic,
                    triggers = TriggerDef.listFromJson(rule.triggers),
                    actions = ActionDef.listFromJson(rule.actions),
                    cooldownSeconds = rule.cooldownSeconds,
                    requirePark = rule.requirePark,
                    confirmBeforeExecute = rule.confirmBeforeExecute,
                    fireOncePerTrip = rule.fireOncePerTrip,
                    playSound = rule.playSound,
                    isNew = false,
                    enabled = rule.enabled,
                )
            )
        }
        loadRearSeats()
    }

    /** Asks the car which rear seats have heat (#327), once per editor open. */
    private fun loadRearSeats() {
        viewModelScope.launch {
            val sides = withContext(ioDispatcher) { vehicleApi.rearSeatHeatSides() }
            _uiState.update { it.copy(rearSeats = sides) }
        }
    }

    fun closeEditor() {
        // «Отмена» also stops a test run: nothing is left on screen to watch or stop it.
        testRunJob?.cancel()
        editorSession++
        _uiState.update { it.copy(showEditor = false, editorError = null, editorRuleDeleted = false) }
    }

    /** The editor says the rule limit is reached; [what] names the save path in the log. */
    private fun refuseAtRuleLimit(what: String) {
        Log.i("AutomationViewModel", "$what not saved: limit of $MAX_RULES rules reached")
        _uiState.update {
            it.copy(editorError = context.appLocalizedContext().getString(R.string.automation_rule_limit, MAX_RULES))
        }
    }

    /**
     * «Правило уже удалено» → «Сохранить как новое»: the draft becomes a new rule, switch as it
     * was. The editor and the draft stay open until the insert returns: it closes only on
     * success, bound to this save's editor session, so a DB failure keeps the draft on screen
     * with a message instead of losing it. The draft is frozen ([EditingRule.saving]) for the
     * same stretch: an edit made while the insert awaits the DB would otherwise be neither saved
     * (a snapshot taken before the edit is what gets inserted) nor kept (the editor closes on
     * success and drops it).
     */
    @Suppress("TooGenericExceptionCaught")
    fun saveDeletedRuleAsNew() {
        val e = _uiState.value.editing
        if (!_uiState.value.editorRuleDeleted || e.saving) return
        val session = editorSession
        // Dismisses the confirmation dialog and freezes the draft right away, so a second tap or
        // an edit before the insert returns is a no-op.
        _uiState.update { it.copy(editorRuleDeleted = false, editing = it.editing.copy(saving = true)) }
        viewModelScope.launch {
            try {
                val id = RuleInserts.insertWithinLimit(
                    ruleDao, e.applyTo(RuleEntity(name = "", triggers = "", actions = "", enabled = e.enabled)), MAX_RULES,
                )
                if (session == editorSession) {
                    if (id != null) closeEditor() else refuseAtRuleLimit("saveDeletedRuleAsNew")
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                Log.w("AutomationViewModel", "saveDeletedRuleAsNew: insert failed", e)
                val msg = context.appLocalizedContext().getString(R.string.automation_import_save_failed, e.message ?: "?")
                if (session == editorSession) _uiState.update { it.copy(editorError = msg) }
            } finally {
                // Unfreezes the draft on any failure; a successful close already dropped this session.
                if (session == editorSession) _uiState.update { it.copy(editing = it.editing.copy(saving = false)) }
            }
        }
    }

    /** «Правило уже удалено» dismissed by Back or outside: the editor stays with the draft. */
    fun dismissRuleDeleted() {
        _uiState.update { it.copy(editorRuleDeleted = false) }
    }

    /** A no-op while the draft is [EditingRule.saving]: nothing edits a rule mid-insert. */
    fun updateEditing(transform: EditingRule.() -> EditingRule) {
        _uiState.update { if (it.editing.saving) it else it.copy(editing = it.editing.transform()) }
    }

    private fun validateActions(actions: List<ActionDef>): String? {
        val ctx = context.appLocalizedContext()
        return RuleDraftValidator.validateActions(actions)?.toMissing(ctx)?.message
    }

    private fun validateTriggers(
        triggers: List<TriggerDef>,
        editingId: Long,
        triggerLogic: String,
        keyPickedLater: Boolean = false,
    ): String? {
        val ctx = context.appLocalizedContext()
        val userPhrases = runCatching { userCommandPhrases() }.getOrDefault(emptyMap())
        val err = RuleDraftValidator.validateTriggers(triggers, editingId, _uiState.value.rules, userPhrases, triggerLogic)
            ?.takeUnless { keyPickedLater && it == TriggerValidationError.SteeringKeyUnassigned }
        if (err != null) Log.i("AutomationViewModel", "rule not saved: $err")
        return when (err) {
            TriggerValidationError.SteeringKeyUnassigned -> ctx.getString(R.string.automation_trigger_steering_key_unassigned)
            is TriggerValidationError.ValueNotNumber ->
                ctx.getString(R.string.auto_ui_miss_number, triggers.indexOfFirst { it.kind == "param" && it.param == err.param } + 1)
            TriggerValidationError.OneShotInvalid -> ctx.getString(R.string.auto_ui_err_one_shot_invalid)
            TriggerValidationError.OneShotWithOr -> ctx.getString(R.string.auto_ui_err_one_shot_or)
            TriggerValidationError.OneShotWithEvent -> ctx.getString(R.string.auto_ui_err_one_shot_event)
            TriggerValidationError.OneShotTwice -> ctx.getString(R.string.auto_ui_err_one_shot_twice)
            TriggerValidationError.VoicePhraseEmpty -> ctx.getString(R.string.automation_voice_phrase_empty)
            is TriggerValidationError.VoicePhraseBuiltin -> ctx.getString(R.string.automation_voice_phrase_taken, err.command)
            is TriggerValidationError.VoicePhraseTaken -> ctx.getString(R.string.automation_voice_phrase_taken, err.rule)
            null -> null
        }
    }

    fun saveRule() {
        val e = _uiState.value.editing
        if (e.name.isBlank() || e.triggers.isEmpty() || e.actions.isEmpty()) {
            _uiState.value = _uiState.value.copy(editorError =
                context.appLocalizedContext().getString(R.string.auto_msg_name_cond_action_required)
            )
            return
        }
        val actionError = validateActions(e.actions)
        if (actionError != null) {
            _uiState.value = _uiState.value.copy(editorError = actionError)
            return
        }
        // Every save path, «Сохранить изменения?» included: a list condition keeps no hidden operator.
        val noOperator = e.triggers.indexOfFirst { hasListOperatorMissing(it) }
        if (noOperator >= 0) {
            Log.i("AutomationViewModel", "rule not saved: list condition ${noOperator + 1} has operator ${e.triggers[noOperator].operator}")
            _uiState.value = _uiState.value.copy(
                editorError = context.appLocalizedContext().getString(R.string.auto_ui_miss_operator, noOperator + 1)
            )
            return
        }
        val triggerError = validateTriggers(e.triggers, if (e.isNew) -1L else e.id, e.triggerLogic)
        if (triggerError != null) {
            _uiState.value = _uiState.value.copy(editorError = triggerError)
            return
        }
        // Clear previous error on success path
        _uiState.value = _uiState.value.copy(editorError = null)
        // Names follow the catalog as saved: a value or param changed after the pick keeps no
        // stale text in the card, the confirm window or the journal.
        val named = e.copy(
            triggers = e.triggers.map { withCatalogName(it, context) },
            actions = e.actions.map { withCatalogName(it, context) },
        )

        if (e.isNew) {
            insertNewRule(named)
            return
        }
        val session = editorSession
        viewModelScope.launch {
            // The stored row keeps enabled, the counters and createdAt: the editor shows none of them.
            // A rule deleted meanwhile is not brought back: the editor stays open and says so.
            // Only this save's editor reacts: another one may be open by the time the DAO returns.
            val stored = ruleDao.getById(e.id)
            if (stored == null) {
                if (session == editorSession) _uiState.update { it.copy(editorRuleDeleted = true) }
            } else {
                ruleDao.update(named.applyTo(stored))
                if (session == editorSession) closeEditor()
            }
        }
    }

    /**
     * The editor closes once the insert got in: a save racing another one at the limit stays
     * open with the refusal instead of losing the draft, frozen while the insert runs.
     */
    private fun insertNewRule(named: EditingRule) {
        if (named.saving) return
        if (_uiState.value.rules.size >= MAX_RULES) {
            refuseAtRuleLimit("new rule")
            return
        }
        val session = editorSession
        _uiState.update { it.copy(editing = it.editing.copy(saving = true)) }
        viewModelScope.launch {
            try {
                val id = RuleInserts.insertWithinLimit(
                    ruleDao, named.applyTo(RuleEntity(name = "", triggers = "", actions = "")), MAX_RULES,
                )
                if (session == editorSession) {
                    if (id != null) closeEditor() else refuseAtRuleLimit("new rule")
                }
            } finally {
                if (session == editorSession) _uiState.update { it.copy(editing = it.editing.copy(saving = false)) }
            }
        }
    }

    // --- Duplicate / Delete ---

    fun duplicateRule(rule: RuleEntity) {
        viewModelScope.launch {
            val copy = rule.copy(
                id = 0,
                name = "${rule.name} (${context.appLocalizedContext().getString(R.string.auto_rule_copy_suffix)})",
                enabled = false,
                lastTriggeredAt = null,
                triggerCount = 0,
                createdAt = System.currentTimeMillis()
            )
            if (RuleInserts.insertWithinLimit(ruleDao, copy, MAX_RULES) == null) {
                Log.i("AutomationViewModel", "copy of rule ${rule.id} refused: limit of $MAX_RULES rules reached")
                showLimitMessage()
            }
        }
    }

    fun requestDelete(ruleId: Long) {
        _uiState.update { it.copy(showDeleteConfirm = ruleId) }
    }

    fun cancelDelete() {
        _uiState.update { it.copy(showDeleteConfirm = null) }
    }

    fun confirmDelete() {
        val ruleId = _uiState.value.showDeleteConfirm ?: return
        viewModelScope.launch {
            ruleDao.getById(ruleId)?.let { ruleDao.delete(it) }
        }
        _uiState.update { it.copy(showDeleteConfirm = null) }
    }

    // --- Journal ---

    fun showJournal() {
        ruleJournalJob?.cancel()
        _uiState.update { it.copy(showJournal = true, journalRuleId = null, ruleLogs = emptyList()) }
    }

    /** The status line on a card: the journal of this rule only, all its entries. */
    fun showRuleJournal(ruleId: Long) {
        ruleJournalJob?.cancel()
        _uiState.update { it.copy(showJournal = true, journalRuleId = ruleId, ruleLogs = emptyList()) }
        ruleJournalJob = viewModelScope.launch {
            ruleLogDao.getByRule(ruleId).collect { logs -> _uiState.update { it.copy(ruleLogs = logs) } }
        }
    }

    fun hideJournal() {
        ruleJournalJob?.cancel()
        _uiState.update { it.copy(showJournal = false, journalRuleId = null, ruleLogs = emptyList()) }
    }

    // --- Note at the bottom of the tab ---

    fun dismissMessage() { _uiState.update { it.copy(message = null) } }

    private fun showLimitMessage() {
        _uiState.update { it.copy(message = context.appLocalizedContext().getString(R.string.auto_ui_limit, MAX_RULES)) }
    }

    // --- Test run ---

    /**
     * «Тестовый запуск»: runs every action of the rule being edited, in order, right now.
     * Each action goes through [ActionDispatcher.dispatch] against the live snapshot, so the
     * speed gates apply exactly as when the rule fires; the chime follows «Выполнять со звуком».
     * A speed-gated action ([isSpeedGatedAction]) also needs a running service and a polled
     * snapshot measured less than [TEST_RUN_MAX_SNAPSHOT_AGE_MS] ago ([isSampleFresh]): the
     * dispatcher lets a window or the sunroof open with no snapshot at all, and a stale speed 0
     * passes every gate. Without one the action is skipped and counted as not done; so is an
     * action the pre-check cannot classify. Closing the editor stops the run.
     * «Только на парковке» holds as when the rule fires: off P, or with no fresh P, the run is refused with a note,
     * and a saved rule gets the refusal in its journal. The trigger conditions, cooldown, «Раз за
     * поездку» and «Спрашивать подтверждение» are skipped: the button press is the confirmation.
     * Otherwise a run writes no lastTriggeredAt / triggerCount update and no journal entry.
     */
    fun testRun() {
        val e = _uiState.value.editing
        if (_uiState.value.testRunning || e.actions.isEmpty()) return
        val actionError = validateActions(e.actions)
        if (actionError != null) {
            _uiState.update { it.copy(editorError = actionError) }
            return
        }
        val gear = liveSnapshot()?.gear
        if (e.requirePark && !inFreshPark(gear)) {
            _uiState.update { it.copy(message = context.appLocalizedContext().getString(R.string.auto_ui_test_park)) }
            if (!e.isNew) {
                viewModelScope.launch {
                    val rule = ruleDao.getById(e.id) ?: return@launch
                    RuleJournal(ruleLogDao, AppStrings(context))
                        .parkRequired(rule, JSONObject().put(TEST_RUN_KEY, true).toString(), gear)
                }
            }
            return
        }
        _uiState.update { it.copy(testRunning = true, editorError = null) }
        testRunJob = viewModelScope.launch {
            val lc = context.appLocalizedContext()
            try {
                if (e.playSound) OverlayNotificationManager.playNotificationSound(context)
                var failed = 0
                var firstReason: String? = null
                for (action in e.actions) {
                    val reason = runTestAction(action)
                    if (reason != null) {
                        failed++
                        if (firstReason == null) firstReason = reason
                    }
                }
                val msg = if (failed == 0) {
                    lc.getString(R.string.automation_test_run_done)
                } else {
                    lc.getString(R.string.automation_test_run_failed, failed, e.actions.size, firstReason)
                }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            } catch (c: CancellationException) {
                Toast.makeText(context, lc.getString(R.string.automation_test_run_stopped), Toast.LENGTH_SHORT).show()
                throw c
            } finally {
                _uiState.update { it.copy(testRunning = false) }
            }
        }
    }

    /**
     * P from a polled snapshot younger than [TEST_RUN_MAX_SNAPSHOT_AGE_MS] of a running service,
     * and still P in the live snapshot [gear]: the live snapshot outlives the service and never
     * says how old it is.
     */
    private fun inFreshPark(gear: Int?): Boolean {
        val freshGear = liveSample()?.takeIf { isSampleFresh(it, serviceRunning(), elapsedNow()) }?.data?.gear
        if (freshGear == 1 && gear == 1) return true
        Log.i("AutomationViewModel", "test run refused: park only, gear=$gear fresh=$freshGear")
        return false
    }

    /**
     * One test-run step: null when it was done, else why not. A speed-gated action is sent with
     * the very snapshot whose age was checked.
     */
    private suspend fun runTestAction(action: ActionDef): String? {
        val lc = context.appLocalizedContext()
        val gated = runCatching { isSpeedGatedAction(action) }.getOrElse {
            Log.w("AutomationViewModel", "test run: ${action.kind} ${action.command} skipped, not recognised", it)
            return lc.getString(R.string.automation_test_run_unrecognized)
        }
        val snapshot = if (gated) {
            val sample = liveSample()?.takeIf { isSampleFresh(it, serviceRunning(), elapsedNow()) }
            if (sample == null) {
                Log.w("AutomationViewModel", "test run: ${action.kind} ${action.command} skipped, no fresh speed")
                return lc.getString(R.string.automation_test_run_no_speed)
            }
            sample.data
        } else {
            liveSnapshot()
        }
        val result = actionDispatcher.dispatch(action.withReportRuleName(_uiState.value.editing.name), snapshot)
        return if (result.success) null else result.reason ?: lc.getString(R.string.auto_msg_unavailable)
    }

    // --- Share ---

    /** «Поделиться» on a rule card: first the note on what the file keeps. */
    fun shareRule(rule: RuleEntity) = requestShare(SharedRule.fromEntity(rule))

    /** «Поделиться» in the editor: shares the draft as it is on screen, saved or not. */
    fun shareEditing() {
        val e = _uiState.value.editing
        if (e.name.isBlank() || e.triggers.isEmpty() || e.actions.isEmpty()) return
        requestShare(e.toShared())
    }

    private fun requestShare(rule: SharedRule) {
        if (_uiState.value.shareInProgress) return
        _uiState.update { it.copy(pendingShare = rule) }
    }

    /** «Продолжить» under the note: writes the file and opens the system share sheet. */
    fun confirmShare() {
        val rule = _uiState.value.pendingShare ?: return
        _uiState.update { it.copy(pendingShare = null) }
        writeShareFile(rule)
    }

    /** «Отмена» under the note: nothing is written. */
    fun cancelShare() {
        _uiState.update { it.copy(pendingShare = null) }
    }

    /**
     * Writes the share file, opens the system share sheet and names the file in a Toast. One
     * export at a time: the mutex serialises the writes, [AutomationUiState.shareInProgress]
     * greys out the buttons meanwhile.
     */
    private fun writeShareFile(rule: SharedRule) {
        if (_uiState.value.shareInProgress) return
        _uiState.update { it.copy(shareInProgress = true) }
        viewModelScope.launch {
            try {
                val file = shareMutex.withLock {
                    withContext(ioDispatcher) {
                        RuleShareFiles.writeTo(downloadsDir(), rule, BuildConfig.VERSION_NAME)
                    }
                }
                shareSheet(file)
                val msg = context.appLocalizedContext().getString(R.string.automation_share_saved_toast, file.name)
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            } catch (e: IOException) {
                showShareError(e)
            } catch (e: SecurityException) {
                showShareError(e)
            } finally {
                _uiState.update { it.copy(shareInProgress = false) }
            }
        }
    }

    private fun showShareError(e: Exception) {
        val msg = context.appLocalizedContext().getString(R.string.automation_share_error, e.message ?: "?")
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    }

    private fun startShareSheet(file: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(shareIntent, null).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: IllegalArgumentException) {
            showShareError(e)
        } catch (e: ActivityNotFoundException) {
            showShareError(e)
        }
    }

    // --- Import ---

    /** «Импорт»: lists the share files in Download, newest first. */
    fun openImport() {
        importJob?.cancel()
        importJob = viewModelScope.launch {
            val files = withContext(ioDispatcher) { RuleShareFiles.listRuleFiles(downloadsDir()) }
            _uiState.update { it.copy(importFiles = files, importError = null, importDraft = null) }
        }
    }

    /**
     * Closes both import steps; a listing or a read still running is dropped with them. Ignored
     * while the insert runs: the preview closes by itself once the rule is in.
     */
    fun closeImport() {
        if (_uiState.value.importDraft?.saving == true) return
        importJob?.cancel()
        _uiState.update { it.copy(importFiles = null, importError = null, importDraft = null) }
    }

    /**
     * A file tapped in step 1: read it (at most [RuleShareFiles.MAX_FILE_BYTES]), parse it, link
     * its places and build the preview lines, all off the main thread; then open the preview or
     * say why it cannot be imported. A newer tap or [closeImport] cancels a read still running,
     * so only the last pick can open a preview.
     */
    fun pickImportFile(file: File) {
        importJob?.cancel()
        val places = _uiState.value.places
        importJob = viewModelScope.launch {
            val lc = context.appLocalizedContext()
            val callLabel = lc.getString(R.string.auto_act_call)
            val parsed = try {
                withContext(ioDispatcher) {
                    RuleShareFiles.readLimited(file)?.let { RuleShare.parse(it, callLabel) } ?: RuleParseResult.Invalid
                }
            } catch (e: IOException) {
                Log.w("AutomationViewModel", "import: cannot read ${file.name}", e)
                RuleParseResult.Invalid
            }
            when (parsed) {
                is RuleParseResult.Ok -> {
                    // A file from before the PowerState condition was removed: converted like a saved rule.
                    val triggers = PowerStateRuleMigration.convert(
                        parsed.rule.triggerLogic, parsed.rule.triggers, PowerStateRuleMigration.labels(context),
                        driveIsGear = PowerStateRuleMigration.isSunshadeTemplate(parsed.rule.name),
                    )
                    if (triggers == null) {
                        _uiState.update { it.copy(importError = lc.getString(R.string.automation_import_power_off_only)) }
                        return@launch
                    }
                    val rule = parsed.rule.copy(triggers = triggers)
                    val draft = withContext(ioDispatcher) { importDraft(rule, places, lc) }
                        .copy(token = ++draftToken, error = importProblem(rule)?.text(lc))
                    _uiState.update { it.copy(importFiles = null, importError = null, importDraft = draft) }
                }
                RuleParseResult.NewerVersion ->
                    _uiState.update { it.copy(importError = lc.getString(R.string.automation_import_newer_version)) }
                RuleParseResult.Invalid ->
                    _uiState.update { it.copy(importError = lc.getString(R.string.automation_import_invalid)) }
            }
        }
    }

    /** [rule] linked to [places] by name, with its preview lines. */
    private fun importDraft(rule: SharedRule, places: List<PlaceEntity>, lc: Context): RuleImportDraft {
        val linked = RuleShare.resolvePlaces(
            rule, places,
            lc.getString(R.string.automation_trigger_place_enter_prefix),
            lc.getString(R.string.automation_trigger_place_exit_prefix),
        )
        return RuleImportDraft(linked, RuleImportSummary.preview(linked, context, actionDispatcher::autoGoWillRun))
    }

    /** «Выбрать место» for the place trigger at [index] of the draft [token]. */
    fun resolveImportPlace(token: Long, index: Int, place: PlaceEntity) {
        val lc = context.appLocalizedContext()
        updateImportRule(token) { rule ->
            val t = rule.triggers.getOrNull(index) ?: return@updateImportRule rule
            val resolved = RuleShare.withPlace(
                t, place,
                lc.getString(R.string.automation_trigger_place_enter_prefix),
                lc.getString(R.string.automation_trigger_place_exit_prefix),
            )
            rule.copy(triggers = rule.triggers.toMutableList().apply { set(index, resolved) })
        }
    }

    /**
     * «Выбрать контакт» for the action at [index] of the draft [token]: a call gets the contact,
     * a `tel:` / `sms:` link gets the number back into its address.
     */
    fun resolveImportContact(token: Long, index: Int, phone: String, name: String, autoDial: Boolean) {
        updateImportRule(token) { rule ->
            val a = rule.actions.getOrNull(index) ?: return@updateImportRule rule
            val resolved = if (a.kind == "url") {
                val url = RuleShareUrl.withNumber(a.urlString(), phone)
                a.withUrl(url, a.urlMinimize()).copy(displayName = url)
            } else {
                a.withCall(phone, name, autoDial)
            }
            rule.copy(actions = rule.actions.toMutableList().apply { set(index, resolved) })
        }
    }

    // The rule is already checked against the file limits, so its preview is cheap to rebuild here.
    private fun updateImportRule(token: Long, transform: (SharedRule) -> SharedRule) {
        _uiState.update { s ->
            val draft = s.importDraft?.takeIf { it.token == token && !it.saving } ?: return@update s
            val rule = transform(draft.rule)
            val error = importProblem(rule)?.text(context.appLocalizedContext())
            s.copy(importDraft = draft.copy(rule = rule, preview = RuleImportSummary.preview(rule, context, actionDispatcher::autoGoWillRun), error = error))
        }
    }

    fun setImportEnableNow(enable: Boolean) {
        _uiState.update { s ->
            val draft = s.importDraft?.takeIf { !it.saving } ?: return@update s
            s.copy(importDraft = draft.copy(enableNow = enable && !draft.rule.hasUnresolved()))
        }
    }

    /**
     * «Добавить»: validates like the editor, renames on a clash, checks the [MAX_RULES] limit
     * and inserts the draft as it is on screen at the tap. Until the insert returns the draft is
     * [RuleImportDraft.saving]: edits and «Отмена» are ignored. The preview closes only once the
     * row is in; a refusal or a database error stays in the preview with the draft intact, so
     * «Добавить» can be pressed again.
     */
    fun confirmImport() {
        val draft = _uiState.value.importDraft ?: return
        if (draft.saving) return
        val rule = draft.rule
        // An unresolved number or address is missing by design: validate the rest of the rule around it.
        val unresolvedCalls = rule.unresolvedCallIndexes().toSet()
        val unresolvedUrls = rule.unresolvedUrlIndexes().toSet()
        val forValidation = rule.actions.mapIndexed { i, a ->
            when {
                i in unresolvedUrls -> a.withUrl(VALIDATION_URL, false)
                i !in unresolvedCalls -> a
                a.kind == "url" -> a.withUrl(RuleShareUrl.withNumber(a.urlString(), VALIDATION_PHONE), false)
                else -> a.withCall(VALIDATION_PHONE, "", false)
            }
        }
        val error = validateActions(forValidation) ?: importCheckError(rule)
        if (error != null) {
            _uiState.update { it.copy(importDraft = draft.copy(error = error)) }
            return
        }
        val lc = context.appLocalizedContext()
        val name = RuleShare.uniqueName(
            rule.name,
            _uiState.value.rules.map { it.name },
            lc.getString(R.string.automation_import_name_suffix),
        )
        val entity = RuleShare.toEntity(rule, name, draft.enableNow)
        _uiState.update { it.copy(importDraft = draft.copy(saving = true, error = null)) }
        viewModelScope.launch {
            val failure = try {
                if (RuleInserts.insertWithinLimit(ruleDao, entity, MAX_RULES) == null) {
                    Log.i("AutomationViewModel", "import refused: limit of $MAX_RULES rules reached")
                    lc.getString(R.string.automation_rule_limit, MAX_RULES)
                } else {
                    null
                }
            } catch (e: SQLiteException) {
                Log.w("AutomationViewModel", "import: insert failed", e)
                lc.getString(R.string.automation_import_save_failed, e.message ?: "?")
            }
            _uiState.update { s ->
                val current = s.importDraft?.takeIf { it.token == draft.token } ?: return@update s
                if (failure == null) s.copy(importDraft = null) else s.copy(importDraft = current.copy(saving = false, error = failure))
            }
        }
    }

    /** The [importProblem] of [rule] (logged), then the editor's trigger checks; null when the rule is fine. */
    private fun importCheckError(rule: SharedRule): String? {
        val problem = importProblem(rule)
            ?: return validateTriggers(rule.triggers, -1L, rule.triggerLogic, keyPickedLater = true)
        Log.i("AutomationViewModel", "import refused: $problem")
        return problem.text(context.appLocalizedContext())
    }

    // --- Starter templates ---

    private suspend fun insertStarterTemplatesIfNeeded() {
        val prefs = context.getSharedPreferences(AutomationEngine.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("templates_inserted", false)) {
            if (RuleInserts.insertAllWithinLimit(ruleDao, starterTemplates(currentLang(context)), MAX_RULES) == null) {
                Log.i("AutomationViewModel", "starter templates refused: limit of $MAX_RULES rules reached")
            }
            prefs.edit().putBoolean("templates_inserted", true).putBoolean(TG_TEMPLATES_KEY, true).apply()
            return
        }
        // Installs from before 3.19 got their templates already: add only the Telegram report ones,
        // once, and only where both fit under the rule limit.
        if (prefs.getBoolean(TG_TEMPLATES_KEY, false)) return
        val ids = RuleInserts.insertAllWithinLimit(ruleDao, telegramReportTemplates(currentLang(context)), MAX_RULES)
        Log.i("AutomationViewModel", "telegram report templates: added=${ids != null} (limit $MAX_RULES rules)")
        prefs.edit().putBoolean(TG_TEMPLATES_KEY, true).apply()
    }
}

/** Stand-in phone for validating an imported rule whose call contact is not picked yet. */
private const val VALIDATION_PHONE = "00000"

/** Stand-in address for validating an imported rule whose link has to be entered again. */
private const val VALIDATION_URL = "https://localhost"

/** Rules the user can have: the editor, the import and the voice agent stop at this many. */
internal const val MAX_RULES = 50

/** Actions one rule can hold: the editor stops offering «add» at this many. */
internal const val MAX_RULE_ACTIONS = 20

/** Set once the Telegram report templates were offered (3.19), on fresh and older installs alike. */
private const val TG_TEMPLATES_KEY = "templates_tg_report_inserted"

/** How old the telemetry snapshot may be for a test run to send a speed-gated action. */
internal const val TEST_RUN_MAX_SNAPSHOT_AGE_MS = 10_000L

/** Toggle targets that can resolve to a speed-gated command: sunroof open, frunk open, unlock. */
private val SPEED_GATED_TOGGLES = setOf(
    ActionDispatcher.TOGGLE_SUNROOF, ActionDispatcher.TOGGLE_FRONT_TRUNK, ActionDispatcher.TOGGLE_LOCKS,
)

/**
 * True when [action] can open a window, the sunroof or the frunk, or unlock the doors: the
 * commands ActionDispatcher gates by speed, detected with its own predicates. A toggle counts
 * by target, since which way it flips is only known against the live state.
 */
internal fun isSpeedGatedAction(action: ActionDef): Boolean = when (action.kind) {
    "param" -> ActionDispatcher.isWindowOpenCommand(action.command) ||
        ActionDispatcher.isSunroofOpenCommand(action.command) ||
        ActionDispatcher.isFrontTrunkOpenCommand(action.command) ||
        ActionDispatcher.isDoorUnlockCommand(action.command)
    "toggle" -> action.payload in SPEED_GATED_TOGGLES
    else -> false
}

/**
 * The service polls, and [sample] was measured less than [TEST_RUN_MAX_SNAPSHOT_AGE_MS] before
 * [nowElapsedMs], both on the monotonic clock: a wall-clock change cannot make it fresh, and a
 * replay to a restarted service keeps its original measurement time.
 */
internal fun isSampleFresh(sample: TimedSnapshot, serviceRunning: Boolean, nowElapsedMs: Long): Boolean =
    serviceRunning && sample.measuredAtElapsedMs > 0 &&
        nowElapsedMs - sample.measuredAtElapsedMs in 0 until TEST_RUN_MAX_SNAPSHOT_AGE_MS
