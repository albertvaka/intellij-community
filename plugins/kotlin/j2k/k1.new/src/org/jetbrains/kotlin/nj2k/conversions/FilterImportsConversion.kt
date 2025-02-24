// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.nj2k.conversions

import org.jetbrains.kotlin.nj2k.NewJ2kConverterContext
import org.jetbrains.kotlin.nj2k.RecursiveConversion
import org.jetbrains.kotlin.nj2k.tree.JKImportList
import org.jetbrains.kotlin.nj2k.tree.JKTreeElement

internal class FilterImportsConversion(context: NewJ2kConverterContext) : RecursiveConversion(context) {
    override fun applyToElement(element: JKTreeElement): JKTreeElement {
        if (element !is JKImportList) return recurse(element)
        element.imports = element.imports.filter { import ->
            context.importStorage.isImportNeeded(import.name.value, allowSingleIdentifierImport = true)
        }
        return recurse(element)
    }
}