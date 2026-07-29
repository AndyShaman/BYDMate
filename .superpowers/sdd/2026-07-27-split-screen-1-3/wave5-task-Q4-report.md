# Wave 5 — Task Q4 Report: TX_DUMP_FIDS chunked transport + honest errors

## Status: DONE

## Summary

Implements chunked TX_DUMP_FIDS transport (Q4 / F-8/F-9). On the real car the full BYD SDK
catalog reply overflowed the binder transaction limit ("FAILED BINDER TRANSACTION"), while the
client silently mapped any null to "daemon unavailable" (F-9, a lie). Fixed with a byte-offset
chunked protocol and three distinct failure kinds.

## Changes

### Protocol (HelperBinderProtocol.kt)
- Updated `TX_DUMP_FIDS` (TX 32) KDoc to describe the chunked wire format:
  request = `int offset`; reply = `int status, int totalLength, byte[] chunk` (≤ DUMP_CHUNK_MAX).
- Added `DUMP_CHUNK_MAX = 64 * 1024` constant (64 KiB, well under the ~1 MB binder limit).

### Daemon (HelperDaemon.kt)
- Added `@Volatile private var dumpFidsCache: String?` — built on offset==0, reused for offset>0
  (deterministic reflection; safe under concurrent Binder dispatch).
- Added `internal fun dumpFidsChunkBytes(dumpUtf8Bytes, offset, chunkMax)` — extracts one chunk;
  exposed as `internal` for direct test coverage.
- Replaced TX_DUMP_FIDS handler: reads `offset = data.readInt()`, builds/caches dump, writes
  `status(0) + totalLength + chunk`; error path writes `status(-1)` only.

### Client (HelperClient.kt)
- Added `sealed class DumpFidsResult` with three variants:
  - `Success(dump: String)` — all chunks assembled
  - `BinderAbsent` — transact failed on first chunk (daemon unreachable / old / timed out)
  - `ReadError(detail: String)` — non-zero status, or mid-loop transact failure (partial)
- Added `private data class DumpChunkReply` inside `HelperClientImpl` for the per-chunk parse result.
- Changed `dumpFids(): String?` → `dumpFids(): DumpFidsResult`; implementation loops via
  `transactParsed` (per-chunk DUMP_FIDS_TIMEOUT_MS = 4.5 s), assembles bytes in a
  `ByteArrayOutputStream`, decodes the whole buffer at the end (UTF-8 safe).
- Added `MAX_DUMP_CHUNKS = 64` guard (64 × 64 KiB = 4 MiB ceiling).

### SettingsViewModel (SettingsViewModel.kt)
- Replaced null-check with `when (dumpResult)` exhaustive match:
  - `BinderAbsent` → existing `settings_fid_dump_error_unavailable` (demon недоступен)
  - `ReadError` → new `settings_fid_dump_error_read` (ошибка чтения: %1$s)
  - `Success` → existing file-write + share flow (unchanged)

### String resources
- `values/strings.xml`: added `settings_fid_dump_error_read` ("ошибка чтения: %1$s")
- `values-en/strings.xml`: added `settings_fid_dump_error_read` ("read error: %1$s")

## Tests

### New: DumpFidsChunkTest.kt (7 tests)
- `chunk 0 of a large dump is exactly CHUNK_MAX bytes`
- `last chunk contains the remainder bytes`
- `past-end offset returns empty byte array`
- `assembling all chunks equals original bytes`
- `multibyte chars in payload are reassembled correctly` (kanji + Chinese, UTF-8 boundary safety)
- `small dump fits in a single chunk`
- `anti-vacuity single-chunk only gives partial result for large dump` (proves loop is required)

### Updated: HelperClientBinderTest.kt (TC-31 to TC-36)
- TC-31: single-chunk Success — updated for new protocol
- TC-32: status=-1 on first chunk — not Success (accepts BinderAbsent or ReadError)
- TC-33: rejecting transact → BinderAbsent — updated for new protocol
- TC-34 (new): 4-byte chunk max, many iterations → full string assembled (anti-vacuity)
- TC-35 (new): mid-loop transact failure → ReadError (not BinderAbsent)
- TC-36 (new): status!=0 on second chunk → ReadError

### Updated: SettingsViewModelTest.kt
- `dumpFids sets unavailable error status when helperClient returns BinderAbsent`
- `dumpFids sets read-error status when helperClient returns ReadError` — verifies distinct string
- `dumpFids sets empty-firmware status when helperClient returns Success with blank string`

## Suite

2837/0/0/1 (baseline Q3: 2826; Q4 adds 11 tests: 7 DumpFidsChunkTest + 3 new HelperClientBinder + 1 net SettingsViewModel)

## Deviations from brief

**TC-32 (resolved in fix-round):** The original Q4 commit had TC-32 assertion weakened to
`assertFalse(result is DumpFidsResult.Success)` with an inline comment acknowledging that the
actual result was `BinderAbsent`, not `ReadError`. The comment framed this as "both are honest
failure representations" — this was incorrect: `BinderAbsent` maps to "daemon unavailable" on
screen, which is the exact lie F-9 was written to eliminate.

Root cause: the daemon error branch wrote only `writeInt(-1)` (4 bytes); the client required
`dataAvail() >= 8` before reading status, so it returned null → BinderAbsent. The deviation
was noticed but not declared in this report.

**Fix (dcbb2b06):**
- Daemon error branch now writes `writeInt(-1) + writeInt(0) + writeByteArray(ByteArray(0))`
  — full protocol reply so client reads status=-1 and returns `ReadError("status=-1 at chunk 0")`.
- TC-32 strengthened to `assertTrue(result is DumpFidsResult.ReadError)`.
- Mutation proof: before fix, `clientWith(errorFake)` returned `BinderAbsent`; after fix, same
  test (with updated fake that matches new daemon output) returns `ReadError`. Suite 2845/0/0/1.

## Fleet Safety

- Old daemon (pre-Q4) sends `transact = false` on TX_DUMP_FIDS offset call → client returns
  `BinderAbsent` → SettingsViewModel shows "demon unavailable". Same UX as before, honest.
- HelperBootstrap daemon-version check detects stale daemons before any TX is attempted;
  by the time dumpFids() is called the daemon is guaranteed current.
- `dumpFidsCache` is `@Volatile` — not protected by a mutex, but the dump is deterministic
  (same class reflection every time), so a race on offset==0 from two concurrent Binder threads
  produces identical strings. Last-write wins, which is always a valid cache value.
- No changes to any other TX code paths.
