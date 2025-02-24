// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.nj2k.conversions

import org.jetbrains.kotlin.nj2k.NewJ2kConverterContext
import org.jetbrains.kotlin.nj2k.RecursiveConversionWithData
import org.jetbrains.kotlin.nj2k.asStatement
import org.jetbrains.kotlin.nj2k.tree.*

internal class YieldStatementConversion(context: NewJ2kConverterContext) :
    RecursiveConversionWithData<Boolean>(context, initialData = false) {

    override fun applyToElementWithData(element: JKTreeElement, data: Boolean /* is yield allowed */): JKTreeElement {
        when (element) {
            is JKKtWhenExpression -> return recurseWithData(element, data = true)
            is JKMethod -> return recurseWithData(element, data = false)
            is JKLambdaExpression -> return recurseWithData(element, data = false)
            !is JKJavaYieldStatement -> return recurseWithData(element, data)
        }
        element.invalidate()

        check(element is JKJavaYieldStatement)
        val newElement = if (data) {
            element.expression.asStatement()
        } else {
            JKErrorStatement(element.psi, "yield is not allowed outside switch expression")
        }

        return recurseWithData(newElement, data = false)
    }
}