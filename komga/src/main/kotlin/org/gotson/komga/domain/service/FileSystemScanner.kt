package org.gotson.komga.domain.service

import mu.KotlinLogging
import org.apache.commons.io.FilenameUtils
import org.gotson.komga.domain.model.Book
import org.gotson.komga.domain.model.DirectoryNotFoundException
import org.gotson.komga.domain.model.ScanResult
import org.gotson.komga.domain.model.Series
import org.gotson.komga.domain.model.Sidecar
import org.gotson.komga.infrastructure.configuration.KomgaProperties
import org.gotson.komga.infrastructure.sidecar.SidecarBookConsumer
import org.gotson.komga.infrastructure.sidecar.SidecarSeriesConsumer
import org.springframework.stereotype.Service
import java.io.IOException
import java.net.URL
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.FileVisitor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readAttributes
import kotlin.time.measureTime

private val logger = KotlinLogging.logger {}

@Service
class FileSystemScanner(
  private val komgaProperties: KomgaProperties,
  private val sidecarBookConsumers: List<SidecarBookConsumer>,
  private val sidecarSeriesConsumers: List<SidecarSeriesConsumer>,
) {

  private val supportedExtensions = listOf("cbz", "zip", "cbr", "rar", "pdf", "epub")

  private data class TempSidecar(
    val name: String,
    val url: URL,
    val lastModifiedTime: LocalDateTime,
    val type: Sidecar.Type? = null,
  )

  private val sidecarBookPrefilter = sidecarBookConsumers.flatMap { it.getSidecarBookPrefilter() }

  fun scanRootFolder(root: Path, forceDirectoryModifiedTime: Boolean = false): ScanResult {
    logger.info { "Scanning folder: $root" }
    logger.info { "Supported extensions: $supportedExtensions" }
    logger.info { "Excluded patterns: ${komgaProperties.librariesScanDirectoryExclusions}" }
    logger.info { "Force directory modified time: $forceDirectoryModifiedTime" }

    if (!(Files.isDirectory(root) && Files.isReadable(root)))
      throw DirectoryNotFoundException("Folder is not accessible: $root", "ERR_1016")

    val scannedSeries = mutableMapOf<Series, List<Book>>()
    val scannedSidecars = mutableListOf<Sidecar>()

    measureTime {
      // path is the series directory
      val pathToSeries = mutableMapOf<Path, Series>()
      val pathToSeriesSidecars = mutableMapOf<Path, MutableList<Sidecar>>()
      // path is the book's parent directory, ie the series directory
      val pathToBooks = mutableMapOf<Path, MutableList<Book>>()
      val pathToBookSidecars = mutableMapOf<Path, MutableList<TempSidecar>>()

      Files.walkFileTree(
        root, setOf(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
        object : FileVisitor<Path> {
          override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
            logger.trace { "preVisit: $dir" }
            if (dir.fileName?.toString()?.startsWith(".") == true ||
              komgaProperties.librariesScanDirectoryExclusions.any { exclude ->
                dir.toString().contains(exclude, true)
              }
            ) return FileVisitResult.SKIP_SUBTREE

            pathToSeries[dir] = Series(
              name = dir.fileName?.toString() ?: dir.toString(),
              url = dir.toUri().toURL(),
              fileLastModified = attrs.getUpdatedTime()
            )

            return FileVisitResult.CONTINUE
          }

          override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            logger.trace { "visitFile: $file" }
            if (attrs.isRegularFile) {
              if (supportedExtensions.contains(FilenameUtils.getExtension(file.fileName.toString()).lowercase()) &&
                !file.fileName.toString().startsWith(".")
              ) {
                val book = pathToBook(file, attrs)
                file.parent.let { key ->
                  pathToBooks.merge(key, mutableListOf(book)) { prev, one -> prev.union(one).toMutableList() }
                }
              }

              sidecarSeriesConsumers.firstOrNull { consumer ->
                consumer.getSidecarSeriesFilenames().any { file.name.equals(it, ignoreCase = true) }
              }?.let {
                val sidecar = Sidecar(file.toUri().toURL(), file.parent.toUri().toURL(), attrs.getUpdatedTime(), it.getSidecarSeriesType(), Sidecar.Source.SERIES)
                pathToSeriesSidecars.merge(file.parent, mutableListOf(sidecar)) { prev, one -> prev.union(one).toMutableList() }
              }

              // book sidecars can't be exactly matched during a file visit
              // this prefilters files to reduce the candidates
              if (sidecarBookPrefilter.any { it.matches(file.name) }) {
                val sidecar = TempSidecar(file.name, file.toUri().toURL(), attrs.getUpdatedTime())
                pathToBookSidecars.merge(file.parent, mutableListOf(sidecar)) { prev, one -> prev.union(one).toMutableList() }
              }
            }

            return FileVisitResult.CONTINUE
          }

          override fun visitFileFailed(file: Path?, exc: IOException?): FileVisitResult {
            logger.warn { "Could not access: $file" }
            return FileVisitResult.SKIP_SUBTREE
          }

          override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
            logger.trace { "postVisit: $dir" }
            val books = pathToBooks[dir]
            val tempSeries = pathToSeries[dir]
            if (!books.isNullOrEmpty() && tempSeries !== null) {
              val series =
                if (forceDirectoryModifiedTime)
                  tempSeries.copy(fileLastModified = maxOf(tempSeries.fileLastModified, books.maxOf { it.fileLastModified }))
                else
                  tempSeries

              scannedSeries[series] = books

              // only add series sidecars if series has books
              pathToSeriesSidecars[dir]?.let { scannedSidecars.addAll(it) }

              // book sidecars are matched here, with the actual list of books
              books.forEach { book ->
                val sidecars = pathToBookSidecars[dir]
                  ?.mapNotNull { sidecar ->
                    sidecarBookConsumers.firstOrNull { it.isSidecarBookMatch(book.name, sidecar.name) }?.let {
                      sidecar to it.getSidecarBookType()
                    }
                  }?.toMap() ?: emptyMap()
                pathToBookSidecars[dir]?.minusAssign(sidecars.keys)

                sidecars.mapTo(scannedSidecars) { (sidecar, type) ->
                  Sidecar(sidecar.url, book.url, sidecar.lastModifiedTime, type, Sidecar.Source.BOOK)
                }
              }
            }

            return FileVisitResult.CONTINUE
          }
        }
      )
    }.also {
      val countOfBooks = scannedSeries.values.sumOf { it.size }
      logger.info { "Scanned ${scannedSeries.size} series, $countOfBooks books, and ${scannedSidecars.size} sidecars in $it" }
    }

    return ScanResult(scannedSeries, scannedSidecars)
  }

  fun scanFile(path: Path): Book? {
    if (!path.exists()) return null

    return pathToBook(path, path.readAttributes())
  }

  private fun pathToBook(path: Path, attrs: BasicFileAttributes): Book =
    Book(
      name = FilenameUtils.getBaseName(path.name),
      url = path.toUri().toURL(),
      fileLastModified = attrs.getUpdatedTime(),
      fileSize = attrs.size()
    )
}

fun BasicFileAttributes.getUpdatedTime(): LocalDateTime =
  maxOf(creationTime(), lastModifiedTime()).toLocalDateTime()

fun FileTime.toLocalDateTime(): LocalDateTime =
  LocalDateTime.ofInstant(this.toInstant(), ZoneId.systemDefault())
