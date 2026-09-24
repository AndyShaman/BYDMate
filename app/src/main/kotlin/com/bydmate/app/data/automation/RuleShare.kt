package com.bydmate.app.data.automation

import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.PlaceEntity
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.TriggerDef
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer

/** The shareable part of a rule: everything the editor sets, nothing the engine counts. */
data class SharedRule(
    val name: String,
    val triggerLogic: String,
    val triggers: List<TriggerDef>,
    val actions: List<ActionDef>,
    val cooldownSeconds: Int,
    val requirePark: Boolean,
    val confirmBeforeExecute: Boolean,
    val fireOncePerTrip: Boolean,
    val playSound: Boolean,
) {
    /** Place triggers that point at no local place yet. */
    fun unresolvedPlaceIndexes(): List<Int> =
        triggers.indices.filter { triggers[it].kind in RuleShare.PLACE_KINDS && triggers[it].placeId == null }

    /** Call actions without a phone number (the file never carries one). */
    fun unresolvedCallIndexes(): List<Int> = actions.indices.filter {
        actions[it].kind == "call" && payloadOf(actions[it].payload).optString("phone").isBlank()
    }

    fun hasUnresolved(): Boolean = unresolvedPlaceIndexes().isNotEmpty() || unresolvedCallIndexes().isNotEmpty()

    companion object {
        fun fromEntity(rule: RuleEntity) = SharedRule(
            name = rule.name,
            triggerLogic = rule.triggerLogic,
            triggers = TriggerDef.listFromJson(rule.triggers),
            actions = ActionDef.listFromJson(rule.actions),
            cooldownSeconds = rule.cooldownSeconds,
            requirePark = rule.requirePark,
            confirmBeforeExecute = rule.confirmBeforeExecute,
            fireOncePerTrip = rule.fireOncePerTrip,
            playSound = rule.playSound,
        )
    }
}

sealed class RuleParseResult {
    data class Ok(val rule: SharedRule) : RuleParseResult()
    /** Not JSON, or not a bydmate_rule file. */
    object Invalid : RuleParseResult()
    /** A newer format version, or a trigger / action kind this build does not know. */
    object NewerVersion : RuleParseResult()
}

/**
 * The share file format: one rule, no private data. Pure Kotlin plus
 * org.json and java.io, so export, parsing and reference resolution are JVM-testable.
 */
object RuleShare {
    const val FORMAT = "bydmate_rule"
    const val VERSION = 1

    internal val PLACE_KINDS = setOf("place_enter", "place_exit")

    /** Trigger kinds AutomationEngine evaluates or fires in this build. */
    internal val KNOWN_TRIGGER_KINDS = setOf(
        "param", "place_enter", "place_exit", "time_of_day", "time_range", "service_start",
        "network_available", "button_press", AutomationEngine.TRIGGER_KIND_STEERING_KEY, "voice",
    )

    /** Action kinds ActionDispatcher.dispatch routes in this build. */
    internal val KNOWN_ACTION_KINDS = setOf(
        "param", "notification", "notification_silent", "notification_sound", "app_launch", "call",
        "navigate", "url", "yandex_music", "youtube", "go_home", "delay", "media_volume", "sentry",
        "hotspot", "cluster_projection", "toggle", "speak", "agent_query", "split_screen",
        "split_screen_close", "split_screen_toggle",
    )

    /** Marker left in a stripped call payload: the importer has to ask for a contact. */
    private const val CONTACT_REQUIRED = "contactRequired"

    // Query parameters that carry a secret: dropped from a shared url action.
    private val CREDENTIAL_PARAM = Regex(
        "(?i)^(.*token.*|.*secret.*|.*passw(or)?d.*|pwd|pass|key|apikey|api_key|api-key|auth|authorization|sig|signature)$"
    )

    // --- Export ---

    /**
     * The share file body. What is stripped, per kind:
     * - triggers `place_enter` / `place_exit`: `placeId` (a local row id); `placeName` stays so the
     *   importer can find the place by name.
     * - action `call`: `phone` and `name` (the contact), and the display name (the voice agent
     *   stores the contact name there); `autoDial` stays, plus a `contactRequired` marker.
     * - action `url`: user:password in the authority and credential query parameters
     *   (token, secret, password, key, auth, sig...); the rest of the address stays.
     * - every other kind (`param`, `notification*`, `app_launch`, `navigate`, `yandex_music`,
     *   `youtube`, `go_home`, `delay`, `media_volume`, `sentry`, `hotspot`, `cluster_projection`,
     *   `toggle`, `speak`, `agent_query`, `split_screen*`): the payload holds no key, token or
     *   contact (API keys live in settings, never in a rule), so it is copied as is.
     * Rule id, enabled, lastTriggeredAt, triggerCount and createdAt are never written.
     */
    fun exportJson(rule: SharedRule, appVersion: String): String {
        val triggers = JSONArray()
        rule.triggers.forEach { t ->
            triggers.put((if (t.kind in PLACE_KINDS) t.copy(placeId = null) else t).toJson())
        }
        val actions = JSONArray()
        rule.actions.forEach { actions.put(stripAction(it).toJson()) }
        val body = JSONObject().apply {
            put("name", rule.name)
            put("trigger_logic", rule.triggerLogic)
            put("triggers", triggers)
            put("actions", actions)
            put("cooldown_seconds", rule.cooldownSeconds)
            put("require_park", rule.requirePark)
            put("confirm_before_execute", rule.confirmBeforeExecute)
            put("fire_once_per_trip", rule.fireOncePerTrip)
            put("play_sound", rule.playSound)
        }
        return JSONObject().apply {
            put("format", FORMAT)
            put("version", VERSION)
            put("app_version", appVersion)
            put("rule", body)
        }.toString(2)
    }

    private fun stripAction(action: ActionDef): ActionDef = when (action.kind) {
        "call" -> action.copy(
            displayName = "",
            payload = JSONObject().apply {
                put("autoDial", payloadOf(action.payload).optBoolean("autoDial", false))
                put(CONTACT_REQUIRED, true)
            }.toString(),
        )
        "url" -> {
            val json = payloadOf(action.payload)
            json.put("url", stripUrlCredentials(json.optString("url")))
            action.copy(payload = json.toString())
        }
        else -> action
    }

    internal fun stripUrlCredentials(url: String): String {
        val noUserInfo = url.replace(Regex("^([a-zA-Z][a-zA-Z0-9+.\\-]*://)[^/?#@]*@"), "$1")
        val hashAt = noUserInfo.indexOf('#')
        val beforeHash = if (hashAt >= 0) noUserInfo.substring(0, hashAt) else noUserInfo
        val fragment = if (hashAt >= 0) noUserInfo.substring(hashAt) else ""
        val qAt = beforeHash.indexOf('?')
        if (qAt < 0) return noUserInfo
        val kept = beforeHash.substring(qAt + 1).split('&')
            .filter { it.isNotEmpty() && !CREDENTIAL_PARAM.matches(it.substringBefore('=')) }
        val base = beforeHash.substring(0, qAt)
        return (if (kept.isEmpty()) base else base + "?" + kept.joinToString("&")) + fragment
    }

    // --- Import ---

    /** Parses a share file. [callLabel] names call actions, whose display name was stripped. */
    fun parse(text: String, callLabel: String): RuleParseResult {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return RuleParseResult.Invalid
        if (root.optString("format") != FORMAT) return RuleParseResult.Invalid
        val version = root.optInt("version", -1)
        if (version < 1) return RuleParseResult.Invalid
        if (version > VERSION) return RuleParseResult.NewerVersion
        val body = root.optJSONObject("rule") ?: return RuleParseResult.Invalid
        return runCatching { parseBody(body, callLabel) }.getOrDefault(RuleParseResult.Invalid)
    }

    private fun parseBody(body: JSONObject, callLabel: String): RuleParseResult {
        val triggersJson = body.getJSONArray("triggers")
        val actionsJson = body.getJSONArray("actions")
        val triggers = (0 until triggersJson.length()).map {
            // Only a name travels: the id always comes from the importing device.
            TriggerDef.fromJson(triggersJson.getJSONObject(it)).let { t -> if (t.kind in PLACE_KINDS) t.copy(placeId = null) else t }
        }
        val actions = (0 until actionsJson.length()).map {
            ActionDef.fromJson(actionsJson.getJSONObject(it)).let { a ->
                if (a.kind == "call" && a.displayName.isBlank()) a.copy(displayName = callLabel) else a
            }
        }
        if (triggers.any { it.kind !in KNOWN_TRIGGER_KINDS } || actions.any { !isKnownAction(it) }) {
            return RuleParseResult.NewerVersion
        }
        val name = body.getString("name").trim()
        if (name.isEmpty() || triggers.isEmpty() || actions.isEmpty()) return RuleParseResult.Invalid
        return RuleParseResult.Ok(
            SharedRule(
                name = name,
                triggerLogic = if (body.optString("trigger_logic") == "OR") "OR" else "AND",
                triggers = triggers,
                actions = actions,
                cooldownSeconds = body.optInt("cooldown_seconds", 60),
                requirePark = body.optBoolean("require_park", false),
                confirmBeforeExecute = body.optBoolean("confirm_before_execute", false),
                fireOncePerTrip = body.optBoolean("fire_once_per_trip", false),
                playSound = body.optBoolean("play_sound", false),
            )
        )
    }

    // A toggle on a target this build cannot name came from a newer version too.
    private fun isKnownAction(action: ActionDef): Boolean =
        action.kind in KNOWN_ACTION_KINDS &&
            (action.kind != "toggle" || ActionDispatcher.toggleTargetNameRes(action.payload.orEmpty()) != null)

    /** Links place triggers to local places by name (case-insensitive); the rest stay unresolved. */
    fun resolvePlaces(rule: SharedRule, places: List<PlaceEntity>, enterPrefix: String, exitPrefix: String): SharedRule =
        rule.copy(triggers = rule.triggers.map { t ->
            if (t.kind !in PLACE_KINDS || t.placeId != null) return@map t
            val wanted = t.placeName?.trim().orEmpty()
            val place = places.firstOrNull { it.name.trim().equals(wanted, ignoreCase = true) }
            if (place == null) t else withPlace(t, place, enterPrefix, exitPrefix)
        })

    /** [trigger] pointed at [place], with the display name the editor would give it. */
    fun withPlace(trigger: TriggerDef, place: PlaceEntity, enterPrefix: String, exitPrefix: String): TriggerDef {
        val prefix = if (trigger.kind == "place_enter") enterPrefix else exitPrefix
        return trigger.copy(placeId = place.id, placeName = place.name, displayName = "$prefix «${place.name}»")
    }

    /** [name], or `name (suffix)`, `name (suffix 2)`... when a rule with that name exists. */
    fun uniqueName(name: String, existing: Collection<String>, suffix: String): String {
        val taken = existing.map { it.trim().lowercase() }.toSet()
        if (name.trim().lowercase() !in taken) return name
        var candidate = "$name ($suffix)"
        var n = 2
        while (candidate.lowercase() in taken) {
            candidate = "$name ($suffix $n)"
            n++
        }
        return candidate
    }

    /** The row to insert. Enabled only when asked and nothing is left unresolved. */
    fun toEntity(rule: SharedRule, name: String, enableNow: Boolean): RuleEntity = RuleEntity(
        name = name,
        enabled = enableNow && !rule.hasUnresolved(),
        triggerLogic = rule.triggerLogic,
        triggers = TriggerDef.listToJson(rule.triggers),
        actions = ActionDef.listToJson(rule.actions),
        cooldownSeconds = rule.cooldownSeconds.coerceAtLeast(1),
        requirePark = rule.requirePark,
        confirmBeforeExecute = rule.confirmBeforeExecute,
        fireOncePerTrip = rule.fireOncePerTrip,
        playSound = rule.playSound,
    )
}

/** Where share files live: `bydmate_rule_<slug>.json` in the public Download folder. */
object RuleShareFiles {
    const val FILE_PREFIX = "bydmate_rule_"
    const val FILE_SUFFIX = ".json"
    private const val SLUG_MAX = 40

    private val CYRILLIC = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'ґ' to "g", 'д' to "d", 'е' to "e", 'ё' to "e",
        'є' to "ye", 'ж' to "zh", 'з' to "z", 'и' to "i", 'і' to "i", 'ї' to "yi", 'й' to "y", 'к' to "k",
        'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t",
        'у' to "u", 'ў' to "u", 'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh",
        'щ' to "shch", 'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya", 'ł' to "l",
    )

    /** Lowercase ASCII slug: Cyrillic transliterated, accents dropped, the rest `_`, at most 40 chars. */
    fun slug(name: String): String {
        val latin = buildString {
            name.lowercase().forEach { c -> append(CYRILLIC[c] ?: c.toString()) }
        }
        val ascii = Normalizer.normalize(latin, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        val slug = ascii.replace(Regex("[^a-z0-9]+"), "_").trim('_').take(SLUG_MAX).trimEnd('_')
        return slug.ifEmpty { "rule" }
    }

    /** `bydmate_rule_<slug>.json` in [dir], or `_2`, `_3`... when that name is taken. */
    fun freeFile(dir: File, name: String): File {
        val base = FILE_PREFIX + slug(name)
        var file = File(dir, base + FILE_SUFFIX)
        var n = 2
        while (file.exists()) {
            file = File(dir, "${base}_$n$FILE_SUFFIX")
            n++
        }
        return file
    }

    /** Writes [rule] to a free name in [dir] (created when missing) and returns the file. */
    fun writeTo(dir: File, rule: SharedRule, appVersion: String): File {
        if (!dir.exists()) dir.mkdirs()
        val file = freeFile(dir, rule.name)
        file.writeText(RuleShare.exportJson(rule, appVersion), Charsets.UTF_8)
        return file
    }

    /** Share files in [dir], newest first (same listing approach as the backup restore picker). */
    fun listRuleFiles(dir: File): List<File> =
        (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.startsWith(FILE_PREFIX) && it.name.endsWith(FILE_SUFFIX) }
            .sortedByDescending { it.lastModified() }
}

private fun payloadOf(payload: String?): JSONObject =
    runCatching { JSONObject(payload ?: "{}") }.getOrDefault(JSONObject())
