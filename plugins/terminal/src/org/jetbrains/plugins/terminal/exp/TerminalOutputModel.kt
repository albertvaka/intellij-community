// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.terminal.exp

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.RangeMarkerImpl
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.util.concurrency.annotations.RequiresEdt
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import java.awt.Rectangle
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

class TerminalOutputModel(val editor: EditorEx) {
  private val blocks: MutableList<CommandBlock> = Collections.synchronizedList(ArrayList())
  private val decorations: MutableMap<CommandBlock, BlockDecoration> = HashMap()
  private val highlightings: MutableMap<CommandBlock, List<HighlightingInfo>> = LinkedHashMap()  // order matters
  private val blockStates: MutableMap<CommandBlock, List<BlockDecorationState>> = HashMap()
  private var allHighlightingsSnapshot: AllHighlightingsSnapshot? = null

  private val document: Document = editor.document
  private val listeners: MutableList<TerminalOutputListener> = CopyOnWriteArrayList()

  fun addListener(listener: TerminalOutputListener, disposable: Disposable? = null) {
    listeners.add(listener)
    if (disposable != null) {
      Disposer.register(disposable) { listeners.remove(listener) }
    }
  }

  @RequiresEdt
  fun createBlock(command: String?, prompt: PromptRenderingInfo?): CommandBlock {
    closeLastBlock()

    if (document.textLength > 0) {
      document.insertString(document.textLength, "\n")
    }
    val startOffset = document.textLength
    val marker = document.createRangeMarker(startOffset, startOffset)
    marker.isGreedyToRight = true

    val adjustedPrompt = prompt?.let { p ->
      val highlightings = p.highlightings.map {
        HighlightingInfo(startOffset + it.startOffset, startOffset + it.endOffset, it.textAttributes)
      }
      PromptRenderingInfo(p.text, highlightings)
    }
    val block = CommandBlock(command, adjustedPrompt, marker)
    blocks.add(block)
    return block
  }

  @RequiresEdt
  fun closeLastBlock() {
    val lastBlock = getLastBlock()
    // restrict previous block expansion
    if (lastBlock != null) {
      lastBlock.range.isGreedyToRight = false
      decorations[lastBlock]?.let {
        it.backgroundHighlighter.isGreedyToRight = false
        it.cornersHighlighter.isGreedyToRight = false
        (it.bottomInlay as RangeMarkerImpl).isStickingToRight = false
      }
    }
  }

  @RequiresEdt
  fun removeBlock(block: CommandBlock) {
    document.deleteString(block.startOffset, block.endOffset)

    block.range.dispose()
    decorations[block]?.let {
      Disposer.dispose(it.topInlay)
      Disposer.dispose(it.bottomInlay)
      it.commandToOutputInlay?.let { inlay -> Disposer.dispose(inlay) }
      editor.markupModel.removeHighlighter(it.backgroundHighlighter)
      editor.markupModel.removeHighlighter(it.cornersHighlighter)
    }

    blocks.remove(block)
    decorations.remove(block)
    highlightings.remove(block)
    allHighlightingsSnapshot = null
    blockStates.remove(block)
  }

  @RequiresEdt
  fun clearBlocks() {
    val blocksCopy = blocks.reversed()
    for (block in blocksCopy) {
      removeBlock(block)
    }
    editor.document.setText("")
  }

  fun getLastBlock(): CommandBlock? {
    return blocks.lastOrNull()
  }

  fun getByOffset(offset: Int): CommandBlock? {
    // todo: better to use binary search here, but default implementation doesn't not acquire the lock of the list
    return blocks.find { offset in (it.startOffset..it.endOffset) }
  }

  fun getByIndex(index: Int): CommandBlock {
    return blocks[index]
  }

  fun getIndexOfBlock(block: CommandBlock): Int {
    return blocks.indexOf(block)
  }

  @RequiresEdt
  fun getBlockBounds(block: CommandBlock): Rectangle {
    val topY = editor.offsetToXY(block.startOffset).y - TerminalUi.blockTopInset
    val bottomY = editor.offsetToXY(block.endOffset).y + editor.lineHeight + TerminalUi.blockBottomInset
    val width = editor.scrollingModel.visibleArea.width - TerminalUi.cornerToBlockInset
    return Rectangle(0, topY, width, bottomY - topY)
  }

  fun getBlocksSize(): Int = blocks.size

  @RequiresEdt
  fun getDecoration(block: CommandBlock): BlockDecoration? {
    return decorations[block]
  }

  @RequiresEdt
  fun putDecoration(block: CommandBlock, decoration: BlockDecoration) {
    decorations[block] = decoration
  }

  @RequiresEdt
  internal fun getHighlightingsSnapshot(): AllHighlightingsSnapshot {
    var snapshot: AllHighlightingsSnapshot? = allHighlightingsSnapshot
    if (snapshot == null) {
      snapshot = AllHighlightingsSnapshot(highlightings.flatMap { it.value })
      allHighlightingsSnapshot = snapshot
    }
    return snapshot
  }

  @RequiresEdt
  fun getHighlightings(block: CommandBlock): List<HighlightingInfo>? {
    return highlightings[block]
  }

  @RequiresEdt
  fun putHighlightings(block: CommandBlock, highlightings: List<HighlightingInfo>) {
    this.highlightings[block] = highlightings
    allHighlightingsSnapshot = null
  }

  @RequiresEdt
  fun getBlockState(block: CommandBlock): List<BlockDecorationState> {
    return blockStates[block] ?: emptyList()
  }

  @RequiresEdt
  fun addBlockState(block: CommandBlock, state: BlockDecorationState) {
    val curStates = blockStates[block] ?: listOf()
    if (curStates.find { it.name == state.name } == null) {
      updateBlockStates(block, curStates, curStates.toMutableList() + state)
    }
  }

  @RequiresEdt
  fun removeBlockState(block: CommandBlock, stateName: String) {
    val curStates = blockStates[block]
    if (curStates?.find { it.name == stateName } != null) {
      updateBlockStates(block, curStates, curStates.filter { it.name != stateName })
    }
  }

  private fun updateBlockStates(block: CommandBlock, oldStates: List<BlockDecorationState>, newStates: List<BlockDecorationState>) {
    blockStates[block] = newStates
    listeners.forEach { it.blockDecorationStateChanged(block, oldStates, newStates) }
  }

  interface TerminalOutputListener {
    fun blockRemoved(block: CommandBlock) {}
    fun blockDecorationStateChanged(block: CommandBlock, oldStates: List<BlockDecorationState>, newStates: List<BlockDecorationState>) {}
  }
}

internal class AllHighlightingsSnapshot(highlightings: List<HighlightingInfo>) {

  val allSortedHighlightings: ImmutableList<HighlightingInfo>

  init {
    val sortedHighlightings = highlightings.sortedBy { it.startOffset }
    val result : MutableList<HighlightingInfo> = ArrayList(sortedHighlightings.size * 2)
    var offset = 0
    for (highlighting in sortedHighlightings) {
      if (offset < highlighting.startOffset) {
        result.add(HighlightingInfo(offset, highlighting.startOffset, TextAttributes.ERASE_MARKER))
      }
      result.add(highlighting)
      offset = highlighting.endOffset
    }
    allSortedHighlightings = result.toImmutableList()
  }
}

data class CommandBlock(val command: String?, val prompt: PromptRenderingInfo?, val range: RangeMarker) {
  val startOffset: Int
    get() = range.startOffset
  val endOffset: Int
    get() = range.endOffset
  val commandStartOffset: Int
    get() = range.startOffset + if (withPrompt) prompt!!.text.length + 1 else 0
  val outputStartOffset: Int
    get() = commandStartOffset + if (withCommand) command!!.length + 1 else 0
  val textRange: TextRange
    get() = range.textRange

  val withPrompt: Boolean = !prompt?.text.isNullOrEmpty()
  val withCommand: Boolean = !command.isNullOrEmpty()
}