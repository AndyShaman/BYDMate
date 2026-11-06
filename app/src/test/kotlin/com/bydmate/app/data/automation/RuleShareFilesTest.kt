package com.bydmate.app.data.automation

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RuleShareFilesTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun file(root: File, path: String, modified: Long): File =
        File(root, path).apply {
            parentFile!!.mkdirs()
            writeText("{}")
            setLastModified(modified)
        }

    @Test fun `share files are found in subfolders, newest first`() {
        val dir = tmp.newFolder("Download")
        val a = file(dir, "bydmate_rule_a.json", 1_000_000)
        val b = file(dir, "Telegram/bydmate_rule_b.json", 5_000_000)
        file(dir, ".hidden/bydmate_rule_c.json", 3_000_000)
        file(dir, "x/y/bydmate_rule_d.json", 2_000_000)
        file(dir, "Telegram/other.json", 9_000_000)

        assertEquals(listOf(b.absoluteFile, a.absoluteFile), RuleShareFiles.listRuleFiles(dir))
    }
}
