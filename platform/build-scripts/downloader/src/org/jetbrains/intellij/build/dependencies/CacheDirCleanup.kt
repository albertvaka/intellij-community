// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.intellij.build.dependencies

import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.*
import java.time.LocalDateTime
import java.util.*
import java.util.logging.Logger
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

private val LOG = Logger.getLogger(CacheDirCleanup::class.java.name)
private val CLEANUP_EVERY_DURATION = 1.days
private const val MARKED_FOR_CLEANUP_SUFFIX = ".marked.for.cleanup"

/**
 * Clean-up local download cache in two stages:
 * 1) mark old files by writing near them a marker file (.marked.for.cleanup)
 * 2) on the second run remove both marked and original old files.
 *
 * Why two-stage removing is required: suppose you are running some build scripts after a month of vacation.
 * Older downloaded files will be marked for deletion, and then some of them will be used again.
 * Without the marking, they would be removed and re-downloaded again, which we do not want.
 */
class CacheDirCleanup(private val cacheDir: Path, private val maxAccessTimeAge: Duration = 22.days) {
  companion object {
    const val LAST_CLEANUP_MARKER_FILE_NAME: String = ".last.cleanup.marker"
  }

  private val lastCleanupMarkerFile: Path = cacheDir.resolve(LAST_CLEANUP_MARKER_FILE_NAME)

  @Throws(IOException::class)
  fun runCleanupIfRequired(): Boolean {
    if (!isTimeForCleanup()) {
      return false
    }

    // update file timestamp mostly
    cleanupCacheDir()
    Files.writeString(lastCleanupMarkerFile, LocalDateTime.now().toString())
    return true
  }

  private fun isTimeForCleanup(): Boolean {
    return Files.notExists(lastCleanupMarkerFile) ||
           Files.getLastModifiedTime(lastCleanupMarkerFile).toMillis() < (System.currentTimeMillis() - CLEANUP_EVERY_DURATION.inWholeMilliseconds)
  }

  @OptIn(ExperimentalPathApi::class)
  @Throws(IOException::class)
  private fun cleanupCacheDir() {
    val cacheFiles = try {
      Files.newDirectoryStream(cacheDir).use { stream ->
        stream.filterTo(HashSet()) { it != lastCleanupMarkerFile }
      }
    }
    catch (e: NoSuchFileException) {
      LOG.fine("Cache directory '$cacheDir' doesn't exist, skipping cleanup")
      return
    }
    catch (e: NotDirectoryException) {
      throw IllegalStateException("Cache directory '$cacheDir' is not a directory")
    }

    val maxTimeMs = maxAccessTimeAge.inWholeMilliseconds
    val currentTime = System.currentTimeMillis()
    for (file in cacheFiles) {
      val fileName = file.fileName.toString()
      if (fileName.endsWith(MARKED_FOR_CLEANUP_SUFFIX)) {
        val realFile = cacheDir.resolve(fileName.substring(0, fileName.length - MARKED_FOR_CLEANUP_SUFFIX.length))
        if (!cacheFiles.contains(realFile)) {
          LOG.info("CACHE-CLEANUP: Removing orphan marker: $file")
          Files.delete(file)
        }
        continue
      }

      val markFile = cacheDir.resolve(fileName + MARKED_FOR_CLEANUP_SUFFIX)
      val lastAccessTime = Files.getLastModifiedTime(file)
      if (lastAccessTime.toMillis() > (currentTime - maxTimeMs)) {
        if (cacheFiles.contains(markFile)) {
          // file was recently updated, un-mark it
          Files.delete(markFile)
        }
        continue
      }

      if (Files.exists(markFile)) {
        // the file is old AND already marked for cleanup - delete
        LOG.info("CACHE-CLEANUP: Deleting file/directory '$file': it's too old and marked for cleanup")

        // renaming file to a temporary name to prevent deletion of currently opened files, just in case
        val toRemove = cacheDir.resolve("$fileName.toRemove.${UUID.randomUUID()}")
        try {
          Files.move(file, toRemove)
          toRemove.deleteRecursively()
        }
        catch (e: Throwable) {
          val writer = StringWriter()
          e.printStackTrace(PrintWriter(writer))
          LOG.warning("""
  Unable to delete file '$file': ${e.message}
  $writer
  """.trimIndent())
        }
        Files.deleteIfExists(markFile)
      }
      else {
        LOG.info("CACHE-CLEANUP: Marking File '$file' for deletion, it'll be removed on the next cleanup run")
        Files.newByteChannel(markFile, EnumSet.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE)).close()
      }
    }
  }
}
