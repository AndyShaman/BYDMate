package com.bydmate.app.voice

import java.io.File

/** Where a model-archive tar entry lands: [rel] is the entry name with its top-level folder
 *  stripped. Null for the top-level folder itself and for an entry that would land outside
 *  [canonicalTarget] (Tar Slip). Shared by GigaAmModelManager and TtsModelManager. */
internal fun tarEntryFile(rel: String, target: File, canonicalTarget: File): File? {
    if (rel.isBlank()) return null
    val outFile = File(target, rel)
    val canonicalOut = outFile.canonicalFile
    if (canonicalOut.path != canonicalTarget.path &&
        !canonicalOut.path.startsWith(canonicalTarget.path + File.separator)) return null
    return outFile
}
