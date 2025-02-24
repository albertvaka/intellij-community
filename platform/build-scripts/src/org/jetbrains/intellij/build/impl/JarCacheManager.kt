// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("ReplaceGetOrSet", "ReplacePutWithAssignment")

package org.jetbrains.intellij.build.impl

import com.intellij.util.io.sha3_224
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.intellij.build.*
import org.jetbrains.intellij.build.dependencies.CacheDirCleanup
import java.math.BigInteger
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.time.Instant
import kotlin.time.Duration.Companion.days

private const val jarSuffix = ".jar"
private const val metaSuffix = ".json"

private const val cacheVersion: Byte = 6

internal interface SourceBuilder {
  val useCacheAsTargetFile: Boolean

  // one module (source) can be included in different plugins - cache per plugin
  fun updateDigest(digest: MessageDigest)

  suspend fun produce()
}

internal sealed interface JarCacheManager {
  suspend fun computeIfAbsent(sources: List<Source>,
                              targetFile: Path,
                              nativeFiles: MutableMap<ZipSource, List<String>>?,
                              span: Span,
                              producer: SourceBuilder): Path

  fun validateHash(source: Source)

  fun cleanup()
}

internal data object NonCachingJarCacheManager : JarCacheManager {
  override suspend fun computeIfAbsent(sources: List<Source>,
                                       targetFile: Path,
                                       nativeFiles: MutableMap<ZipSource, List<String>>?,
                                       span: Span,
                                       producer: SourceBuilder): Path {
    producer.produce()
    return targetFile
  }

  override fun validateHash(source: Source) {
  }

  override fun cleanup() {
  }
}

private val json by lazy {
  Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    prettyPrintIndent = "  "
    isLenient = true
  }
}

internal class LocalDiskJarCacheManager(private val cacheDir: Path, private val classOutDirectory: Path) : JarCacheManager {
  init {
    Files.createDirectories(cacheDir)
  }

  override fun cleanup() {
    CacheDirCleanup(cacheDir = cacheDir, maxAccessTimeAge = 2.days).runCleanupIfRequired()
  }

  override suspend fun computeIfAbsent(sources: List<Source>,
                                       targetFile: Path,
                                       nativeFiles: MutableMap<ZipSource, List<String>>?,
                                       span: Span,
                                       producer: SourceBuilder): Path {
    val items = createSourceAndCacheStrategyList(sources = sources, classOutDirectory = classOutDirectory)

    // 224 bit and not 256/512 - use a slightly shorter filename
    // xxh3 is not used as it is not secure and moreover, better to stick to JDK API
    val hash = sha3_224()
    hash.update(cacheVersion)
    hash.update(items.size.toByte())
    hash.update((items.size shr 8).toByte())
    for (source in items) {
      val pathLength = source.path.length
      hash.update(pathLength.toByte())
      hash.update((pathLength shr 8).toByte())
      hash.update(source.path.encodeToByteArray())

      source.updateDigest(hash)
    }

    producer.updateDigest(hash)

    val hashString = BigInteger(1, hash.digest()).toString(Character.MAX_RADIX)
    val cacheName = "${targetFile.fileName.toString().removeSuffix(jarSuffix)}-$hashString"
    val cacheFileName = (cacheName + jarSuffix).takeLast(255)
    val cacheFile = cacheDir.resolve(cacheFileName)
    val cacheMetadataFile = cacheDir.resolve((cacheName + metaSuffix).takeLast(255))
    if (checkCache(cacheMetadataFile = cacheMetadataFile,
                   cacheFile = cacheFile,
                   sources = sources,
                   items = items,
                   span = span,
                   nativeFiles = nativeFiles)) {
      if (!producer.useCacheAsTargetFile) {
        Files.createDirectories(targetFile.parent)
        Files.copy(cacheFile, targetFile)
      }
      span.addEvent(
        "use cache",
        Attributes.of(
          AttributeKey.stringKey("file"), targetFile.toString(),
          AttributeKey.stringKey("cacheFile"), cacheFileName,
        ),
      )

      // update file modification time to maintain FIFO caches i.e., in persistent cache folder on TeamCity agent and for CacheDirCleanup
      Files.setLastModifiedTime(cacheFile, FileTime.from(Instant.now()))

      if (producer.useCacheAsTargetFile) {
        return cacheFile
      }
      return targetFile
    }

    producer.produce()

    val sourceCacheItems = items.map { source ->
      SourceCacheItem(path = source.path,
                      size = source.getSize().toInt(),
                      hash = source.getHash(),
                      nativeFiles = (source.source as? ZipSource)?.let { nativeFiles?.get(it) } ?: emptyList())
    }

    coroutineScope {
      launch {
        try {
          Files.copy(targetFile, cacheFile)
        }
        catch (e: FileAlreadyExistsException) {
          // concurrent access?
          span.addEvent("Cache file $cacheFile already exists")
          check(Files.size(targetFile) == Files.size(cacheFile)) {
            "targetFile=$targetFile, cacheFile=$cacheFile, sources=$sources"
          }
        }
      }

      launch {
        Files.writeString(cacheMetadataFile, json.encodeToString(JarCacheItem(sources = sourceCacheItems)))
      }

      launch(Dispatchers.Default) {
        notifyAboutMetadata(sources = sourceCacheItems, items = items, nativeFiles = nativeFiles)
      }
    }

    if (producer.useCacheAsTargetFile) {
      return cacheFile
    }
    return targetFile
  }

  override fun validateHash(source: Source) {
    if (source.hash == 0L && (source !is DirSource || Files.exists(source.dir))) {
      Span.current().addEvent("Zero hash for $source")
    }
  }
}

private fun checkCache(cacheMetadataFile: Path,
                       cacheFile: Path,
                       sources: List<Source>,
                       items: List<SourceAndCacheStrategy>,
                       nativeFiles: MutableMap<ZipSource, List<String>>?,
                       span: Span): Boolean {
  if (Files.notExists(cacheMetadataFile) || Files.notExists(cacheFile)) {
    return false
  }

  val metadataString = Files.readString(cacheMetadataFile)
  val metadata = try {
    json.decodeFromString<JarCacheItem>(metadataString)
  }
  catch (e: SerializationException) {
    span.addEvent("Metadata $metadataString not equal to ${sources}", Attributes.of(AttributeKey.stringKey("error"), e.toString()))
    return false
  }

  if (!checkSavedAndActualSources(metadata = metadata, sources = sources, items = items)) {
    span.addEvent("Metadata $metadataString not equal to $sources")
    return false
  }

  notifyAboutMetadata(sources = metadata.sources, items = items, nativeFiles = nativeFiles)
  return true
}

private fun checkSavedAndActualSources(metadata: JarCacheItem, sources: List<Source>, items: List<SourceAndCacheStrategy>): Boolean {
  if (metadata.sources.size != sources.size) {
    return false
  }

  for ((index, metadataItem) in metadata.sources.withIndex()) {
    if (items.get(index).path != metadataItem.path) {
      return false
    }
  }
  return true
}

private fun notifyAboutMetadata(sources: List<SourceCacheItem>,
                                items: List<SourceAndCacheStrategy>,
                                nativeFiles: MutableMap<ZipSource, List<String>>?) {
  for ((index, sourceCacheItem) in sources.withIndex()) {
    val source = items.get(index).source
    source.size = sourceCacheItem.size
    source.hash = sourceCacheItem.hash
    if (sourceCacheItem.nativeFiles.isNotEmpty()) {
      nativeFiles?.put(source as ZipSource, sourceCacheItem.nativeFiles)
    }
  }
}

@Serializable
private class JarCacheItem(
  @JvmField val sources: List<SourceCacheItem> = emptyList(),
)

@Serializable
private class SourceCacheItem(
  @JvmField val path: String,
  @JvmField val size: Int,
  @JvmField val hash: Long,
  @JvmField val nativeFiles: List<String> = emptyList(),
)