package com.bydmate.app.util

import android.content.Context
import io.mockk.every
import io.mockk.mockk

/** [AppStrings] over a mocked [Context]: `get` resolves through that context's own getString stubs. */
fun appStringsOver(context: Context): AppStrings = mockk {
    every { get(any(), *anyVararg()) } answers {
        val id = firstArg<Int>()
        val rest = args.drop(1).flatMap { if (it is Array<*>) it.toList() else listOf(it) }
        if (rest.isEmpty()) context.getString(id) else context.getString(id, *rest.toTypedArray())
    }
    every { this@mockk.context } returns context
}
