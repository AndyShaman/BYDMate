package com.bydmate.app.diagnostics

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.rules.ExternalResource
import java.util.concurrent.Executors

/**
 * Installs a memory-only journal behind [Trace] for one test, so a wiring test can read what
 * the code under test recorded. [events] waits for every event recorded before the call: the
 * snapshot request queues behind them on the journal's single worker thread.
 */
class TraceRecorder : ExternalResource() {

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var journal: TraceJournal

    override fun before() {
        journal = TraceJournal(null, executor.asCoroutineDispatcher(), logcat = {})
        Trace.install(journal)
    }

    override fun after() {
        Trace.install(null)
        journal.close()
        executor.shutdownNow()
    }

    /** The event lines without their clock time, oldest first. */
    fun events(): List<String> =
        runBlocking { journal.lines() }.filterNot { it.startsWith("-- ") }.map { it.substringAfter(' ') }
}
