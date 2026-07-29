package com.bydmate.app.split

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SplitPairPickerTest {

    private val existing = SplitPair(narrowPkg = "com.n", widePkg = "com.w", narrowSide = SplitSide.LEFT)

    // ── stored pair present ───────────────────────────────────────────────────

    @Test fun `applyPick WIDE with stored pair updates widePkg only`() {
        val result = applyPick(stored = existing, pendingOther = null, role = SplitRole.WIDE, pkg = "com.new")
        assertEquals("com.new", result?.widePkg)
        assertEquals("com.n", result?.narrowPkg)
        assertEquals(SplitSide.LEFT, result?.narrowSide)
    }

    @Test fun `applyPick NARROW with stored pair updates narrowPkg only`() {
        val result = applyPick(stored = existing, pendingOther = null, role = SplitRole.NARROW, pkg = "com.new")
        assertEquals("com.new", result?.narrowPkg)
        assertEquals("com.w", result?.widePkg)
        assertEquals(SplitSide.LEFT, result?.narrowSide)
    }

    @Test fun `applyPick with stored pair ignores pendingOther`() {
        val result = applyPick(stored = existing, pendingOther = "com.ignored", role = SplitRole.WIDE, pkg = "com.x")
        assertEquals("com.x", result?.widePkg)
        assertEquals("com.n", result?.narrowPkg)
    }

    // ── no stored pair, pendingOther present ──────────────────────────────────

    @Test fun `applyPick WIDE with pending narrow builds pair with RIGHT default side`() {
        val result = applyPick(stored = null, pendingOther = "com.pending", role = SplitRole.WIDE, pkg = "com.w2")
        assertEquals(SplitPair(narrowPkg = "com.pending", widePkg = "com.w2", narrowSide = SplitSide.RIGHT), result)
    }

    @Test fun `applyPick NARROW with pending wide builds pair with RIGHT default side`() {
        val result = applyPick(stored = null, pendingOther = "com.pending", role = SplitRole.NARROW, pkg = "com.n2")
        assertEquals(SplitPair(narrowPkg = "com.n2", widePkg = "com.pending", narrowSide = SplitSide.RIGHT), result)
    }

    // ── no stored pair, no pendingOther ───────────────────────────────────────

    @Test fun `applyPick WIDE without stored or pending returns null`() {
        assertNull(applyPick(stored = null, pendingOther = null, role = SplitRole.WIDE, pkg = "com.x"))
    }

    @Test fun `applyPick NARROW without stored or pending returns null`() {
        assertNull(applyPick(stored = null, pendingOther = null, role = SplitRole.NARROW, pkg = "com.x"))
    }

    // ── degenerate pair guard ────────────────────────────────────────────────

    @Test fun `applyPick WIDE picking same pkg as stored narrow returns stored unchanged`() {
        // existing.narrowPkg = "com.n"; picking "com.n" for wide would yield a degenerate pair.
        val result = applyPick(stored = existing, pendingOther = null, role = SplitRole.WIDE, pkg = "com.n")
        assertEquals(existing, result)
    }

    @Test fun `applyPick NARROW picking same pkg as stored wide returns stored unchanged`() {
        // existing.widePkg = "com.w"; picking "com.w" for narrow would yield a degenerate pair.
        val result = applyPick(stored = existing, pendingOther = null, role = SplitRole.NARROW, pkg = "com.w")
        assertEquals(existing, result)
    }

    @Test fun `applyPick WIDE picking same pkg as pendingOther with no stored pair returns null`() {
        // No stored pair; pending narrow = "com.x"; picking "com.x" for wide → degenerate → null.
        val result = applyPick(stored = null, pendingOther = "com.x", role = SplitRole.WIDE, pkg = "com.x")
        assertNull(result)
    }
}
