package com.bydmate.app.camera

import android.hardware.AVMCamera
import android.hardware.BmmCameraInfo
import android.view.Surface
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * On some firmwares (Yuan Up, DiLink 3.0, issue #299) the camera classes are not on the app's
 * class path but sit in /system/framework/bmmcamera.jar; the probe then loads that jar and looks
 * again. Where the first lookup works (Leopard 3, Sea Lion) the jar is never touched.
 */
class AvmCameraProbeJarFallbackTest {

    private val jarLoader = object : ClassLoader() {}
    private val factoryCalls = mutableListOf<String>()
    private val lookupLoaders = mutableListOf<ClassLoader?>()

    @Before fun setUp() = AVMCamera.reset()

    @After fun tearDown() = AVMCamera.reset()

    /** Off the class path: only a lookup through the jar's loader finds the classes. */
    private fun offClassPath(name: String, loader: ClassLoader?): Class<*> {
        lookupLoaders += loader
        if (loader !== jarLoader) throw ClassNotFoundException(name)
        return Class.forName(name)
    }

    private fun probe(
        lookup: (String, ClassLoader?) -> Class<*> = ::offClassPath,
        jarExists: Boolean = true,
        loader: (String) -> ClassLoader = { path -> factoryCalls += path; jarLoader },
    ) = AvmCameraProbe(classLookup = lookup, jarExists = { jarExists }, jarLoader = loader)

    @Test fun `a class off the class path is found through the loaded jar`() {
        val probe = probe()

        assertTrue(probe.discover())

        assertEquals(listOf("/system/framework/bmmcamera.jar"), factoryCalls)
        assertEquals(listOf(null, jarLoader), lookupLoaders)
        assertEquals(BmmCameraInfo.CAMERA_ID, probe.cameraId)
        assertEquals("bmmcamera.jar: loaded", probe.discoverJournal.first())
    }

    @Test fun `the camera is opened through the same loader the jar was loaded into`() {
        val probe = probe()
        assertTrue(probe.discover())
        lookupLoaders.clear()

        assertTrue(probe.open(2, mockk<Surface>(relaxed = true)))

        assertTrue(lookupLoaders.isNotEmpty())
        assertTrue(lookupLoaders.all { it === jarLoader })
    }

    @Test fun `a later pass reuses the jar loader of the first one`() {
        // No tag answers, so the controller keeps calling discover() once a minute.
        val probe = probe(lookup = { name, loader -> offClassPath(name, loader); NoCamera::class.java })

        assertFalse(probe.discover())
        assertEquals("bmmcamera.jar: loaded", probe.discoverJournal.first())
        assertFalse(probe.discover())

        assertEquals(listOf("/system/framework/bmmcamera.jar"), factoryCalls)
        assertEquals(listOf(null, jarLoader, null, jarLoader), lookupLoaders)
        assertEquals("bmmcamera.jar: reused", probe.discoverJournal.first())
    }

    @Test fun `no jar on the car means no loader and no camera`() {
        val probe = probe(jarExists = false)

        assertFalse(probe.discover())

        assertEquals(emptyList<String>(), factoryCalls)
        assertTrue(probe.discoverJournal.contains("bmmcamera.jar: not found"))
    }

    @Test fun `a class on the class path never touches the jar`() {
        val probe = probe(lookup = { name, loader -> lookupLoaders += loader; Class.forName(name) })

        assertTrue(probe.discover())

        assertEquals(emptyList<String>(), factoryCalls)
        assertTrue(lookupLoaders.all { it == null })
        assertTrue(probe.discoverJournal.none { it.startsWith("bmmcamera.jar") })
    }

    @Test fun `a failure other than a missing class does not try the jar`() {
        val probe = probe(lookup = { _, _ -> throw LinkageError("bad class") })

        assertFalse(probe.discover())

        assertEquals(emptyList<String>(), factoryCalls)
    }

    @Test fun `a loader that throws is journaled and nothing escapes`() {
        val probe = probe(loader = { throw IllegalStateException("bad dex") })

        assertFalse(probe.discover())

        assertTrue(
            probe.discoverJournal.toString(),
            probe.discoverJournal.contains("bmmcamera.jar: load failed: java.lang.IllegalStateException: bad dex"),
        )
    }
}

/** A camera lookup whose firmware has no camera under any tag. */
@Suppress("UnusedParameter", "FunctionOnlyReturningConstant")
private object NoCamera {
    @JvmStatic fun getCameraNumbers(): Int = 0

    @JvmStatic fun getValidCameraTag(): String = ""

    @JvmStatic fun getCameraId(tag: String): Int = -1
}
