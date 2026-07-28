package com.bydmate.app.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioUrlValidatorTest {

    @Test
    fun `accepts http https and rtsp streams`() {
        assertTrue(RadioUrlValidator.isValidStreamUrl("http://stream.example/live.mp3"))
        assertTrue(RadioUrlValidator.isValidStreamUrl("https://stream.example/live.aac"))
        assertTrue(RadioUrlValidator.isValidStreamUrl("HTTPS://Stream.Example/live.m3u8"))
        assertTrue(RadioUrlValidator.isValidStreamUrl("rtsp://stream.example/live"))
        assertTrue(RadioUrlValidator.isValidStreamUrl("  https://stream.example/live  "))
    }

    @Test
    fun `rejects blank unsupported and scheme-only stream urls`() {
        assertFalse(RadioUrlValidator.isValidStreamUrl(""))
        assertFalse(RadioUrlValidator.isValidStreamUrl("   "))
        assertFalse(RadioUrlValidator.isValidStreamUrl("stream.example/live.mp3"))
        assertFalse(RadioUrlValidator.isValidStreamUrl("ftp://stream.example/live.mp3"))
        assertFalse(RadioUrlValidator.isValidStreamUrl("https://"))
        assertFalse(RadioUrlValidator.isValidStreamUrl("https://stream example/live"))
    }

    @Test
    fun `icon accepts blank remote urls and local uris`() {
        assertTrue(RadioUrlValidator.isValidIconUrl(""))
        assertTrue(RadioUrlValidator.isValidIconUrl("   "))
        assertTrue(RadioUrlValidator.isValidIconUrl("https://cdn.example/logo.png"))
        assertTrue(RadioUrlValidator.isValidIconUrl("content://com.android.providers.media.documents/document/image%3A42"))
        assertTrue(RadioUrlValidator.isValidIconUrl("file:///sdcard/logo.png"))
    }

    @Test
    fun `icon rejects unsupported schemes`() {
        assertFalse(RadioUrlValidator.isValidIconUrl("/sdcard/logo.png"))
        assertFalse(RadioUrlValidator.isValidIconUrl("javascript:alert(1)"))
        assertFalse(RadioUrlValidator.isValidIconUrl("content://"))
    }
}
