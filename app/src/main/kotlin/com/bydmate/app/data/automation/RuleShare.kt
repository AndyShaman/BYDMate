package com.bydmate.app.data.automation

import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.PlaceEntity
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.TriggerDef
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
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

    /** `url` actions whose address could not be cleaned on export: the user enters it again in the editor. */
    fun unresolvedUrlIndexes(): List<Int> = actions.indices.filter { needsUrl(actions[it]) }

    /**
     * Actions waiting for a phone number: calls without one (the file never carries one) and
     * `tel:` / `sms:` links whose number was removed on export.
     */
    fun unresolvedCallIndexes(): List<Int> = actions.indices.filter { needsContact(actions[it]) }

    /** `url` actions that lost credential-like parameters on export: the preview asks to check the address. */
    fun strippedUrlIndexes(): List<Int> = actions.indices.filter {
        actions[it].kind == "url" && payloadOf(actions[it].payload).optBoolean(RuleShare.PARAMS_STRIPPED)
    }

    fun hasUnresolved(): Boolean =
        unresolvedPlaceIndexes().isNotEmpty() || unresolvedCallIndexes().isNotEmpty() || unresolvedUrlIndexes().isNotEmpty()

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

    /** Marker left in a stripped call or tel/sms url payload: the importer has to ask for a number. */
    internal const val CONTACT_REQUIRED = "contactRequired"

    /** Marker left in a url payload whose address was emptied on export: it has to be entered again. */
    internal const val URL_REQUIRED = "urlRequired"

    /** Marker left in a url payload that lost credential-like parameters on export: worth a look, not a blocker. */
    internal const val PARAMS_STRIPPED = "paramsStripped"

    // --- Export ---

    /**
     * The share file body. What is stripped, per kind:
     * - triggers `place_enter` / `place_exit`: `placeId` (a local row id); `placeName` stays so the
     *   importer can find the place by name.
     * - action `call`: `phone` and `name` (the contact), and the display name (the voice agent
     *   stores the contact name there); `autoDial` stays, plus a `contactRequired` marker.
     * - action `url`: what [RuleShareUrl.strip] removes, and the display name is rebuilt from the
     *   stripped address (the voice agent stores the original address there).
     * - every other kind (`param`, `notification*`, `app_launch`, `navigate`, `yandex_music`,
     *   `youtube`, `go_home`, `delay`, `media_volume`, `sentry`, `hotspot`, `cluster_projection`,
     *   `toggle`, `speak`, `agent_query`, `split_screen*`): copied as is. Free texts (notification
     *   and speak texts, agent prompts, navigation points, voice phrases, the rule name) stay: the
     *   user shares their own rule and the share note asks them to check those.
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
            val stripped = RuleShareUrl.strip(json.optString("url"))
            json.put("url", stripped.url)
            json.remove(CONTACT_REQUIRED)
            json.remove(URL_REQUIRED)
            json.remove(PARAMS_STRIPPED)
            if (stripped.contactRequired) json.put(CONTACT_REQUIRED, true)
            if (stripped.urlRequired) json.put(URL_REQUIRED, true)
            if (stripped.paramsStripped) json.put(PARAMS_STRIPPED, true)
            action.copy(displayName = stripped.url, payload = json.toString())
        }
        else -> action
    }

    // --- Import ---

    /**
     * Parses a share file. [callLabel] names call actions, whose display name was stripped.
     * A file past [RuleShareJsonLimits] is invalid before org.json ever sees it.
     */
    fun parse(text: String, callLabel: String): RuleParseResult {
        if (!RuleShareJsonLimits.accepts(text)) return RuleParseResult.Invalid
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

    /**
     * Links place triggers to local places by name, ignoring case and extra whitespace, but only
     * when exactly one place has that name: with none or several the user picks the place.
     */
    fun resolvePlaces(rule: SharedRule, places: List<PlaceEntity>, enterPrefix: String, exitPrefix: String): SharedRule =
        rule.copy(triggers = rule.triggers.map { t ->
            if (t.kind !in PLACE_KINDS || t.placeId != null) return@map t
            val wanted = normalizedPlaceName(t.placeName.orEmpty())
            val matches = places.filter { normalizedPlaceName(it.name) == wanted }
            if (matches.size == 1) withPlace(t, matches.single(), enterPrefix, exitPrefix) else t
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

/**
 * A share-safe address; whether the importer has to enter a phone number into it; whether the
 * address could not be cleaned and was emptied, so the importer has to enter it again; whether
 * credential-like parameters were removed from it, so the address may need a look.
 */
data class StrippedUrl(
    val url: String,
    val contactRequired: Boolean,
    val urlRequired: Boolean = false,
    val paramsStripped: Boolean = false,
)

/**
 * Credential stripping for a shared `url` action. The address is parsed as a [URI]:
 * - user:password in the authority is dropped;
 * - query parameters whose URL-decoded name reads as a credential ([isCredential]) are dropped;
 * - the fragment is dropped (OAuth-style links carry tokens there), except in an Android
 *   `intent:` link, where the fragment IS the intent: there only credential-named extras go;
 * - `tel:` loses the number, `sms:` and `smsto:` lose the number and keep the query (the
 *   message body); all three are marked contact-required, like `call`.
 * An address [URI] cannot parse, `javascript:` / `data:` content, and an empty address (one
 * emptied by an earlier export) are not guessed at: they are emptied and marked url-required. A secret in the path or in a link nested inside another
 * (a webhook id, an intent fallback URL) cannot be recognised structurally: the share note
 * says what is removed and asks the user to check the address itself.
 */
internal object RuleShareUrl {
    private val CONTACT_SCHEMES = setOf("tel", "sms", "smsto")
    private val CONTENT_SCHEMES = setOf("javascript", "data")

    // Words of a parameter name that make it a credential: `X-Api-Key`, `access_token`, `sig`.
    private val CREDENTIAL_WORDS = setOf(
        "token", "secret", "key", "pass", "passwd", "password", "pwd", "auth", "authorization", "sig",
        "signature", "api", "apikey", "credential", "credentials", "session", "sid",
    )

    // Endings of a parameter name written without separators: `accesstoken`, `myapikey`.
    private val CREDENTIAL_ENDINGS = listOf(
        "apikey", "accesstoken", "authtoken", "refreshtoken", "clientsecret", "password", "passwd",
        "signature", "secret", "token",
    )

    // Where a camelCase name starts a new word: `accessToken`, `APIKey`.
    private val CAMEL_BOUNDARY = Regex("(?<=[\\p{Ll}\\p{N}])(?=\\p{Lu})|(?<=\\p{Lu})(?=\\p{Lu}\\p{Ll})")
    private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")

    private val EMPTIED = StrippedUrl("", contactRequired = false, urlRequired = true)

    fun strip(url: String): StrippedUrl {
        val trimmed = url.trim()
        val scheme = trimmed.substringBefore(':', "").lowercase()
        if (scheme in CONTACT_SCHEMES) return withoutNumber(scheme, trimmed)
        if (scheme in CONTENT_SCHEMES || trimmed.isEmpty()) return EMPTIED
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return EMPTIED
        return rebuild(uri)
    }

    /** A contact-required `tel:` / `sms:` link from [strip] with [phone] put back before its query. */
    fun withNumber(url: String, phone: String): String {
        val query = url.substringAfter('?', "")
        return url.substringBefore(':') + ":" + phone.trim() + (if (query.isEmpty()) "" else "?$query")
    }

    private fun withoutNumber(scheme: String, url: String): StrippedUrl {
        val rawQuery = if (scheme == "tel") null else url.substringBefore('#').substringAfter('?', "").ifEmpty { null }
        val query = filterQuery(rawQuery)
        return StrippedUrl(
            "$scheme:" + (query?.let { "?$it" } ?: ""), contactRequired = true,
            paramsStripped = dropsParams(rawQuery, null),
        )
    }

    private fun rebuild(uri: URI): StrippedUrl {
        if (uri.isOpaque) {
            // mailto:, geo:... have no authority, but may still carry a query.
            val ssp = uri.rawSchemeSpecificPart
            val rawQuery = if ('?' in ssp) ssp.substringAfter('?') else null
            val query = filterQuery(rawQuery)
            val url = "${uri.scheme}:${ssp.substringBefore('?')}" + (query?.let { "?$it" } ?: "")
            return StrippedUrl(url, contactRequired = false, paramsStripped = dropsParams(rawQuery, null))
        }
        val intentExtras = uri.rawFragment?.takeIf { uri.scheme.equals("intent", ignoreCase = true) }
        val url = buildString {
            uri.scheme?.let { append(it).append(':') }
            // Userinfo cannot hold an unescaped '@', so the host starts after the last one.
            if (uri.rawSchemeSpecificPart.startsWith("//")) append("//").append(uri.rawAuthority?.substringAfterLast('@').orEmpty())
            append(uri.rawPath.orEmpty())
            filterQuery(uri.rawQuery)?.let { append('?').append(it) }
            intentExtras?.let { append('#').append(filterIntentExtras(it)) }
        }
        return StrippedUrl(url, contactRequired = false, paramsStripped = dropsParams(uri.rawQuery, intentExtras))
    }

    /** The query without credential parameters, or null when nothing is left. */
    private fun filterQuery(rawQuery: String?): String? =
        rawQuery?.split('&')
            ?.filter { it.isNotEmpty() && !isCredentialParam(it) }
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString("&")

    /** `#Intent;scheme=https;S.token=abc;end` without the credential-named extras (`S.token`). */
    private fun filterIntentExtras(fragment: String): String =
        fragment.split(';').filterNot(::isCredentialExtra).joinToString(";")

    /** Whether [filterQuery] / [filterIntentExtras] drop anything from these parts. */
    private fun dropsParams(rawQuery: String?, intentExtras: String?): Boolean =
        rawQuery.orEmpty().split('&').any { it.isNotEmpty() && isCredentialParam(it) } ||
            intentExtras.orEmpty().split(';').any(::isCredentialExtra)

    private fun isCredentialParam(part: String): Boolean = isCredential(part.substringBefore('='))

    private fun isCredentialExtra(part: String): Boolean =
        '=' in part && isCredential(part.substringBefore('=').substringAfter('.'))

    /**
     * A name is a credential when one of its words is in [CREDENTIAL_WORDS] (words split at
     * non-alphanumerics and camelCase), or when written without separators it ends like one of
     * [CREDENTIAL_ENDINGS]. Whole words only: `mapid` and `design` are not `api` and `sig`.
     * A name that does not even decode is treated as a credential: dropping it is the safe side.
     */
    private fun isCredential(rawName: String): Boolean {
        val name = runCatching { URLDecoder.decode(rawName, "UTF-8") }.getOrNull() ?: return true
        val words = name.replace(CAMEL_BOUNDARY, " ").lowercase().split(NON_ALNUM).filter { it.isNotEmpty() }
        val compact = words.joinToString("")
        return words.any { it in CREDENTIAL_WORDS } || CREDENTIAL_ENDINGS.any { compact.endsWith(it) }
    }
}

/**
 * Keeps org.json away from input it cannot survive. Android's parser recurses once per nesting
 * level, and a string field can hold JSON of its own that is parsed later (action payloads,
 * the schedule of a `time_range` trigger). So the file, and every string in it that org.json
 * could take for JSON, nests `{` / `[` at most [MAX_DEPTH] deep, and no string is longer than
 * [MAX_STRING_CHARS]. The scan tokenises like the lenient Android parser: double- and
 * single-quoted strings, unquoted literals, `/* */`, `//` and `#` comments. Where that parser would give up
 * (an unmatched close, an unterminated string) the scan stops too: nothing after it is parsed.
 */
internal object RuleShareJsonLimits {
    const val MAX_DEPTH = 32
    const val MAX_STRING_CHARS = 8 * 1024

    private const val BOM = '\uFEFF'

    private val ESCAPES = mapOf('t' to '\t', 'b' to '\b', 'n' to '\n', 'r' to '\r', 'f' to '\u000C')

    // What ends an unquoted literal (`true`, `12`, `abc`) for the Android parser. A quote inside
    // a literal is part of it, not the start of a string.
    private const val LITERAL_END = "{}[]/\\:,=;# \t\u000C\r\n"

    fun accepts(text: String): Boolean {
        var depth = 0
        // The Android tokenizer drops one leading byte order mark before it parses.
        var i = if (text.startsWith(BOM)) 1 else 0
        while (i < text.length) {
            val c = text[i]
            when {
                c in "{[" -> if (++depth > MAX_DEPTH) return false
                c in "}]" -> if (--depth < 0) return true
                c in "\"'" -> i = acceptString(text, i) ?: return false
                c in "/#" -> i = skipComment(text, i)
                c !in LITERAL_END -> i = literalEnd(text, i) ?: return false
            }
            i++
        }
        return true
    }

    /**
     * The last index of the unquoted literal starting at [start], null when it is longer than
     * [MAX_STRING_CHARS]: the parser reads an unquoted `name:aaaa` as a string too.
     */
    private fun literalEnd(text: String, start: Int): Int? {
        var i = start
        while (i + 1 < text.length && text[i + 1] !in LITERAL_END) i++
        return i.takeIf { it - start < MAX_STRING_CHARS }
    }

    /**
     * Checks the string opening at [open]: the index of its closing quote, null when it breaks a
     * limit. An unterminated string ends the text: org.json fails there.
     */
    private fun acceptString(text: String, open: Int): Int? {
        val value = StringBuilder()
        val close = readString(text, open, value) ?: return text.length
        if (value.length > MAX_STRING_CHARS) return null
        return if (looksLikeJson(value) && !accepts(value.toString())) null else close
    }

    /** Decodes the string opening at [open] into [out]: the index of the closing quote, null when there is none. */
    private fun readString(text: String, open: Int, out: StringBuilder): Int? {
        val quote = text[open]
        var i = open + 1
        while (i < text.length) {
            val c = text[i]
            if (c == quote) return i
            if (c != '\\') {
                out.append(c)
            } else if (text.getOrNull(i + 1) == 'u') {
                out.append(text.substring(i + 2, minOf(i + 6, text.length)).toIntOrNull(16)?.toChar() ?: return null)
                i += 5
            } else {
                val e = text.getOrNull(i + 1) ?: return null
                out.append(ESCAPES[e] ?: e)
                i++
            }
            i++
        }
        return null
    }

    /** The last index of a comment starting at [start], or [start] when none starts there. */
    private fun skipComment(text: CharSequence, start: Int): Int {
        val c = text[start]
        val next = text.getOrNull(start + 1)
        return when {
            c == '/' && next == '*' -> text.indexOf("*/", start + 2).let { if (it < 0) text.length else it + 1 }
            c == '#' || (c == '/' && next == '/') ->
                text.indexOfAny(charArrayOf('\n', '\r'), start).let { if (it < 0) text.length else it }
            else -> start
        }
    }

    /**
     * True when org.json would read [s] as an object or array: its first token, after one
     * leading byte order mark (the tokenizer drops it), is `{` or `[`.
     */
    private fun looksLikeJson(s: CharSequence): Boolean {
        var i = if (s.startsWith(BOM)) 1 else 0
        while (i < s.length) {
            val c = s[i]
            if (c == '{' || c == '[') return true
            if (!c.isWhitespace()) {
                val end = skipComment(s, i)
                if (end == i) return false
                i = end
            }
            i++
        }
        return false
    }
}

/** Where share files live: `bydmate_rule_<slug>.json` in the public Download folder. */
object RuleShareFiles {
    const val FILE_PREFIX = "bydmate_rule_"
    const val FILE_SUFFIX = ".json"
    private const val SLUG_MAX = 40

    /** Largest share file the importer reads: a real rule is a few KiB. */
    const val MAX_FILE_BYTES = 256 * 1024

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

    /**
     * Writes [rule] to a free name in [dir] (created when missing) and returns the file. The text
     * goes to a hidden temp name the import list never shows and is then renamed in one step, so
     * a failed or half-done write never appears as a share file. Callers serialise exports.
     */
    fun writeTo(dir: File, rule: SharedRule, appVersion: String): File {
        if (!dir.exists()) dir.mkdirs()
        val file = freeFile(dir, rule.name)
        val tmp = File(dir, ".${file.name}.tmp")
        try {
            tmp.writeText(RuleShare.exportJson(rule, appVersion), Charsets.UTF_8)
            if (!tmp.renameTo(file)) throw IOException("cannot rename ${tmp.name} to ${file.name}")
        } finally {
            // After a successful rename there is nothing left to delete.
            tmp.delete()
        }
        return file
    }

    /**
     * The text of [file], or null when it is bigger than [MAX_FILE_BYTES]. The limit is checked
     * while reading, so a file that grows meanwhile cannot slip past it.
     */
    fun readLimited(file: File): String? {
        val out = ByteArrayOutputStream()
        file.inputStream().use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_FILE_BYTES) return null
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /** Share files in [dir], newest first (same listing approach as the backup restore picker). */
    fun listRuleFiles(dir: File): List<File> =
        (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.startsWith(FILE_PREFIX) && it.name.endsWith(FILE_SUFFIX) }
            .sortedByDescending { it.lastModified() }
}

private fun payloadOf(payload: String?): JSONObject =
    runCatching { JSONObject(payload ?: "{}") }.getOrDefault(JSONObject())

private fun needsUrl(action: ActionDef): Boolean =
    action.kind == "url" && payloadOf(action.payload).optBoolean(RuleShare.URL_REQUIRED, false)

private fun needsContact(action: ActionDef): Boolean = when (action.kind) {
    "call" -> payloadOf(action.payload).optString("phone").isBlank()
    "url" -> payloadOf(action.payload).optBoolean(RuleShare.CONTACT_REQUIRED, false)
    else -> false
}

private val WHITESPACE = Regex("\\s+")

private fun normalizedPlaceName(name: String): String = name.trim().replace(WHITESPACE, " ").lowercase()
