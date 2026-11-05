package com.openminis.app.sandbox

import com.openminis.app.logging.AppLogger
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset

/**
 * [B11-zip-slip-prevention] POSIX tar stream extractor with canonical-path
 * zip-slip validation. Protects against archive path traversal (e.g. "../")
 * escaping the target directory.
 *
 * Handles regular files, directories, symlinks, and hardlinks. Pure JVM
 * without Android framework dependencies.
 */
object TarExtractor {

    private const val TAG = "TarExtractor"

    fun extractTar(input: InputStream, targetDir: File) {
        val canonicalTarget = targetDir.canonicalFile
        val header = ByteArray(512)

        while (true) {
            val bytesRead = readFully(input, header)
            if (bytesRead < 512) break

            // Check for end-of-archive (two consecutive zero blocks)
            if (header.all { it == 0.toByte() }) break

            val name = extractString(header, 0, 100)
            val modeOctal = extractString(header, 100, 8)
            val sizeOctal = extractString(header, 124, 12)
            val typeFlag = header[156].toInt().toChar()
            val linkName = extractString(header, 157, 100)
            val mode = modeOctal.trim().toIntOrNull(8) ?: 0

            // Handle GNU/POSIX long names via prefix field (bytes 345-500)
            val prefix = extractString(header, 345, 155)
            val fullName = if (prefix.isNotEmpty()) "$prefix/$name" else name

            if (fullName.isEmpty()) break

            val size = sizeOctal.trim().toLongOrNull(8) ?: 0L
            val outFile = File(targetDir, fullName)

            // [B11-zip-slip-prevention] Reject path traversal outside targetDir
            val canonicalOut = outFile.canonicalFile
            if (!canonicalOut.path.startsWith(canonicalTarget.path + File.separator) && canonicalOut != canonicalTarget) {
                throw SecurityException("Zip-slip path traversal attempt: '$fullName' escapes target directory '$targetDir'")
            }

            when (typeFlag) {
                '5', 'D' -> {
                    // Directory
                    outFile.mkdirs()
                }
                '2' -> {
                    // Symbolic link
                    outFile.parentFile?.mkdirs()
                    try {
                        java.nio.file.Files.createSymbolicLink(
                            outFile.toPath(),
                            java.nio.file.Paths.get(linkName)
                        )
                    } catch (_: Exception) {
                        AppLogger.warning(TAG, "Failed to create symlink: $fullName -> $linkName")
                    }
                }
                '0', '\u0000' -> {
                    // Regular file (type '0' or null/legacy)
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { output ->
                        var remaining = size
                        val buf = ByteArray(8192)
                        while (remaining > 0) {
                            val toRead = minOf(buf.size.toLong(), remaining).toInt()
                            val n = input.read(buf, 0, toRead)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            remaining -= n
                        }
                    }
                    // Preserve executable permission from tar header
                    if (mode and 0b001_001_001 != 0) {
                        outFile.setExecutable(true, false)
                    }
                    // Skip padding to next 512-byte boundary
                    val remainder = (size % 512).toInt()
                    if (remainder != 0) {
                        skipFully(input, (512 - remainder).toLong())
                    }
                    continue // Already consumed data + padding
                }
                '1' -> {
                    // Hard link — create a copy
                    outFile.parentFile?.mkdirs()
                    val linkTarget = File(targetDir, linkName)
                    val canonicalLinkTarget = linkTarget.canonicalFile
                    if (!canonicalLinkTarget.path.startsWith(canonicalTarget.path + File.separator) && canonicalLinkTarget != canonicalTarget) {
                        throw SecurityException("Zip-slip hardlink traversal attempt: '$linkName' escapes target directory '$targetDir'")
                    }
                    if (linkTarget.exists()) {
                        linkTarget.copyTo(outFile, overwrite = true)
                    }
                }
                else -> {
                    // Unknown type, skip data
                }
            }

            // Skip data blocks for non-file entries that we didn't consume above
            if (typeFlag != '0' && typeFlag != '\u0000' && size > 0) {
                val blocks = (size + 511) / 512 * 512
                skipFully(input, blocks)
            }
        }
    }

    internal fun extractString(header: ByteArray, offset: Int, length: Int): String {
        val end = minOf(offset + length, header.size)
        var actualEnd = offset
        for (i in offset until end) {
            if (header[i] == 0.toByte()) break
            actualEnd = i + 1
        }
        return String(header, offset, actualEnd - offset, Charset.forName("UTF-8"))
    }

    internal fun readFully(input: InputStream, buf: ByteArray): Int {
        var offset = 0
        while (offset < buf.size) {
            val n = input.read(buf, offset, buf.size - offset)
            if (n < 0) return offset
            offset += n
        }
        return offset
    }

    internal fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        val buf = ByteArray(8192)
        while (remaining > 0) {
            val toRead = minOf(buf.size.toLong(), remaining).toInt()
            val n = input.read(buf, 0, toRead)
            if (n < 0) break
            remaining -= n
        }
    }
}
