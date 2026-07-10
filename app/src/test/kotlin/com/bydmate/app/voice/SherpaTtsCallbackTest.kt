package com.bydmate.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SherpaTtsCallbackTest {
    /** Mirrors the exact JNI lookup sherpa-onnx does:
     *  GetMethodID(cls, "invoke", "([F)Ljava/lang/Integer;"). */
    @Test
    fun `callback class declares the typed invoke method JNI looks up`() {
        val cb = TtsSamplesCallback { it.size }
        val m = cb.javaClass.methods.firstOrNull {
            it.name == "invoke" &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == FloatArray::class.java &&
                (it.returnType == Integer::class.java || it.returnType == Int::class.javaPrimitiveType)
        }
        assertNotNull("typed invoke([F) method missing — JNI GetMethodID would fail", m)
        assertEquals(3, cb.invoke(floatArrayOf(1f, 2f, 3f)))
    }
}
