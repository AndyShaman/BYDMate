package com.bydmate.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsVoiceCatalogTest {

    @Test fun `unknown and retired ids fall back to dmitri`() {
        assertEquals("dmitri", TtsVoiceCatalog.byId("artem").id)
        assertEquals("dmitri", TtsVoiceCatalog.byId("garbage").id)
    }

    @Test fun `counterpart swaps gender within engine and falls back across engines`() {
        assertEquals("irina", TtsVoiceCatalog.counterpart(TtsVoiceCatalog.byId("dmitri")).id)
        assertEquals("sofia", TtsVoiceCatalog.counterpart(TtsVoiceCatalog.byId("mark")).id)
        // alena is the only VITS_MULTI voice left: counterpart falls back to the default male
        assertEquals("dmitri", TtsVoiceCatalog.counterpart(TtsVoiceCatalog.byId("alena")).id)
    }
}
