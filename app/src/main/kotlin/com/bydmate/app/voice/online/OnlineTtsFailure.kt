package com.bydmate.app.voice.online

/** One reply the online voice failed to speak: [cause] is what the backend threw (or the
 *  timeout), [spokenOffline] whether the reply went to the local voice instead. */
class OnlineTtsFailure(val backendId: String, val cause: Throwable?, val spokenOffline: Boolean)

enum class OnlineTtsFailureKind { DATA_POLICY, OTHER }

/** DATA_POLICY: OpenRouter refused the request by the account's privacy settings (guardrail /
 *  data policy), something only the user can change; everything else is OTHER. */
fun OnlineTtsFailure.kind(): OnlineTtsFailureKind {
    val message = cause?.message.orEmpty()
    return if (message.contains("data policy", ignoreCase = true) || message.contains("guardrail", ignoreCase = true)) {
        OnlineTtsFailureKind.DATA_POLICY
    } else {
        OnlineTtsFailureKind.OTHER
    }
}

/** Shows a failure to the driver once per (backendId, kind) for the life of the object. */
class OnlineTtsFailureNotifier(private val show: (OnlineTtsFailureKind, Boolean) -> Unit) {
    private val shown = mutableSetOf<Pair<String, OnlineTtsFailureKind>>()

    fun onFailure(failure: OnlineTtsFailure) {
        val kind = failure.kind()
        val first = synchronized(shown) { shown.add(failure.backendId to kind) }
        if (first) show(kind, failure.spokenOffline)
    }
}
