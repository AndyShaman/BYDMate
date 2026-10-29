package com.bydmate.app.data.dtc

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Reader

/**
 * Descriptions of the found codes: loads the `dtc/` tables once, lazily, on [dispatcher], and
 * looks every code up by the car's model code, its energy and the block class. A missing or broken
 * table means no descriptions (logged once), never a crash.
 */
class DtcDescriber(
    private val open: (String) -> Reader,
    private val autoType: suspend () -> Int?,
    private val energy: () -> DtcEnergy,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Mutex()
    @Volatile private var descriptions: DtcDescriptions? = null

    /** Every found code that has a description; the rest are absent from the map. */
    suspend fun describe(found: List<DtcFound>): Map<DtcFound, DtcDescription> {
        if (found.isEmpty()) return emptyMap()
        val d = load()
        val model = autoType()
        val kind = energy()
        return withContext(dispatcher) {
            found.mapNotNull { f ->
                val code7 = f.row.code.replace("-", "")
                val cls = DtcBlockClass.of(f.block)
                val desc = d.lookup(model, kind, cls, code7)
                Log.i(
                    TAG,
                    "desc addr=0x%X code=%s class=%s autotype=%s energy=%s level=%s manual=%s".format(
                        f.addr, code7, cls ?: "?", model ?: "?", kind, desc?.level ?: "NONE", desc?.manual ?: "-",
                    ),
                )
                desc?.let { f to it }
            }.toMap()
        }
    }

    @Suppress("TooGenericExceptionCaught") // a broken asset is no descriptions, never a crash
    private suspend fun load(): DtcDescriptions = lock.withLock {
        descriptions?.let { return it }
        val loaded = withContext(dispatcher) {
            val started = System.currentTimeMillis()
            try {
                val tables = open("texts.tsv").use { texts ->
                    open("manuals.tsv").use { manuals ->
                        open("rows.tsv").use { rows ->
                            open("models.tsv").use { models -> DtcTablesLoader.parse(texts, manuals, rows, models) }
                        }
                    }
                }
                Log.i(
                    TAG,
                    "tables loaded codes=${tables.rowsByCode.size} rows=${tables.rowCount} " +
                        "manuals=${tables.manualEnergy.size} models=${tables.manualsByModel.size} " +
                        "ms=${System.currentTimeMillis() - started}",
                )
                tables
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "tables unavailable ${e.javaClass.simpleName}: ${e.message} (no descriptions)")
                DtcTables.EMPTY
            }
        }
        DtcDescriptions(loaded).also { descriptions = it }
    }

    companion object {
        private const val TAG = "DtcDesc"
    }
}
