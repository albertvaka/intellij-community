// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.ui.laf

import com.intellij.ide.IdeBundle
import com.intellij.ide.ui.TargetUIType
import com.intellij.ide.ui.ThemeListProvider
import com.intellij.openapi.editor.colors.Groups
import com.intellij.ui.ExperimentalUI

private class ThemeListProviderImpl : ThemeListProvider {
  override fun getShownThemes(): Groups<UIThemeLookAndFeelInfo> {
    val uiThemeProviderListManager = UiThemeProviderListManager.getInstance()

    val newUiThemes = mutableListOf<UIThemeLookAndFeelInfo>()
    val classicUiThemes = mutableListOf<UIThemeLookAndFeelInfo>()
    val customThemes = mutableListOf<UIThemeLookAndFeelInfo>()

    val highContrastThemeId = "JetBrainsHighContrastTheme"
    val highContrastThemeToAdd = uiThemeProviderListManager.findThemeById(highContrastThemeId)

    if (ExperimentalUI.isNewUI()) {
      uiThemeProviderListManager.getThemeListForTargetUI(TargetUIType.NEW).forEach { info ->
        if (info.isThemeFromJetBrains) newUiThemes.add(info)
        else customThemes.add(info)
      }
    }

    (uiThemeProviderListManager.getThemeListForTargetUI(TargetUIType.CLASSIC) +
     uiThemeProviderListManager.getThemeListForTargetUI(TargetUIType.UNSPECIFIED))
      .forEach { info ->
        if (info.id == highContrastThemeId
            || info.id == "IntelliJ"
            || (info.id == "JetBrainsLightTheme" && ExperimentalUI.isNewUI())) return@forEach

        if (info.isThemeFromJetBrains) classicUiThemes.add(info)
        else customThemes.add(info)
      }

    newUiThemes.sortBy { it.name }
    classicUiThemes.sortBy { it.name }
    customThemes.sortBy { it.name }

    if (highContrastThemeToAdd != null) {
      val destination = if (newUiThemes.isEmpty()) classicUiThemes else newUiThemes
      destination.add(highContrastThemeToAdd)
    }

    val groupInfos = mutableListOf<Groups.GroupInfo<UIThemeLookAndFeelInfo>>()

    if (newUiThemes.isNotEmpty()) groupInfos.add(Groups.GroupInfo(newUiThemes))
    if (classicUiThemes.isNotEmpty()) groupInfos.add(Groups.GroupInfo(classicUiThemes))
    if (customThemes.isNotEmpty()) groupInfos.add(Groups.GroupInfo(customThemes, IdeBundle.message("combobox.list.custom.section.title")))

    return Groups(groupInfos)
  }
}