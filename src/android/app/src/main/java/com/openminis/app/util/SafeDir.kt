package com.openminis.app.util

import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Directory listing that never goes through [File.listFiles] or [File.list].
 *
 * Why this exists
 * ---------------
 * On Android 15 the platform implementation of `java.io.File.list()` is a
 * native method (`java_io_UnixFileSystem_list0`) that turns every directory
 * entry into a Java String with `NewStringUTF`. `NewStringUTF` requires
 * Modified UTF-8, which by construction cannot encode a character outside the
 * BMP - such a character is a surrogate pair written as two 3-byte sequences.
 * The platform passes it the raw bytes of the file name, so a name holding an
 * emoji yields a 4-byte sequence and ART aborts the entire process under
 * CheckJNI:
 *
 *     JNI DETECTED ERROR IN APPLICATION: input is not valid Modified UTF-8:
 *     illegal continuation byte 0xfc
 *       in call to NewStringUTF
 *       from java.lang.String[] java.io.UnixFileSystem.list0(java.io.File)
 *
 * The abort cannot be caught, retried or recovered from - the process is
 * gone. It was observed on this tree on a file named
 * `2_Checkout (... root-...).txt`, a name an agent had written mixing
 * Cyrillic with an emoji, and it recurred every time that directory was
 * listed.
 *
 * `java.nio.file.Files.newDirectoryStream` avoids the platform's native path
 * completely: `sun.nio.fs.UnixDirectoryStream` is plain Java, reads entry
 * names from `UnixNativeDispatcher.readdir` as raw bytes and decodes them
 * with a `CharsetDecoder` that replaces malformed input. No JNI string is
 * ever built from those bytes, so a valid UTF-8 name survives intact and an
 * invalid one degrades to U+FFFD instead of killing the process.
 *
 * [File.listFiles] and [File.list] must not be used anywhere in this app.
 * Use [listFiles], [listNames] and [walk] instead.
 *
 * The contract is deliberately identical to the stdlib it replaces:
 * a directory that cannot be read yields `null`, not an empty list, so
 * existing null checks keep their meaning.
 */
object SafeDir {

    /**
     * Names of the entries directly inside [dir], decoded in Java.
     *
     * @return the names, or `null` when [dir] is not a directory or cannot be
     *   read - the same condition under which [File.listFiles] returns null.
     */
    fun listNames(dir: File): List<String>? {
        val out = ArrayList<String>()
        val stream = try {
            Files.newDirectoryStream(dir.toPath())
        } catch (_: IOException) {
            return null
        } catch (_: SecurityException) {
            return null
        } catch (_: UnsupportedOperationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        stream.use { s ->
            for (p in s) {
                val name = p.fileName?.toString() ?: continue
                out.add(name)
            }
        }
        return out
    }

    /**
     * Direct children of [dir] as [File] objects.
     *
     * The names come from [listNames], so they are decoded in Java and may
     * contain any character the file system allows. The returned [File]
     * objects address the same entries; a name that was not valid UTF-8 is
     * replaced by U+FFFD and therefore no longer resolves, which is a
     * tolerable loss next to losing the process.
     */
    fun listFiles(dir: File): List<File>? {
        val names = listNames(dir) ?: return null
        val out = ArrayList<File>(names.size)
        for (name in names) {
            out.add(File(dir, name))
        }
        return out
    }

    /** Direct children of [dir] that satisfy [filter]. */
    fun listFiles(dir: File, filter: (File) -> Boolean): List<File>? {
        val names = listNames(dir) ?: return null
        val out = ArrayList<File>(names.size)
        for (name in names) {
            val f = File(dir, name)
            if (filter(f)) out.add(f)
        }
        return out
    }

    /**
     * Depth-first walk of [root], yielding the root first and then every
     * entry below it.
     *
     * Equivalent to `File.walkTopDown()`, which is itself implemented on top
     * of `File.listFiles()` and therefore just as fatal. The order matches
     * the stdlib exactly - a parent is immediately followed by its own
     * children - because [Sequence.maxDepth] derives the depth of an element
     * from its position in the sequence and would miscount otherwise.
     *
     * Unlike the stdlib version this one refuses to descend into a directory
     * it has already visited, so a symlink cycle terminates instead of
     * spinning forever.
     */
    fun walk(root: File): Sequence<File> = sequence {
        val visited = HashSet<String>()
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val f = stack.removeLast()
            yield(f)
            if (!f.isDirectory) continue
            if (!visited.add(canonicalOrAbsolute(f))) continue
            val children = listFiles(f).orEmpty()
            // Reversed, so the first child is popped first and the traversal
            // keeps the stdlib's sibling order.
            for (i in children.indices.reversed()) stack.addLast(children[i])
        }
    }

    /**
     * Depth-first walk of [root] that yields every entry before the
     * directory that contains it.
     *
     * Equivalent to `File.walkBottomUp()`, which is built on
     * `File.listFiles()` and therefore just as fatal. Used when entries have
     * to be removed before their parent directory can go.
     */
    fun walkBottomUp(root: File): Sequence<File> = sequence {
        val visited = HashSet<String>()
        val ordered = ArrayList<File>()
        // (node, expanded) - a node is re-visited after its children so that
        // it can be emitted last.
        val stack = ArrayDeque<Pair<File, Boolean>>()
        stack.addLast(root to false)
        while (stack.isNotEmpty()) {
            val (f, expanded) = stack.removeLast()
            if (expanded) {
                ordered.add(f)
                continue
            }
            if (f.isDirectory) {
                if (!visited.add(canonicalOrAbsolute(f))) {
                    ordered.add(f)
                    continue
                }
                stack.addLast(f to true)
                val children = listFiles(f).orEmpty()
                for (i in children.indices.reversed()) stack.addLast(children[i] to false)
            } else {
                ordered.add(f)
            }
        }
        for (f in ordered) yield(f)
    }

    /**
     * Best-effort identity of a directory for cycle detection.
     *
     * `canonicalPath` resolves symlinks and throws when the path cannot be
     * resolved, so the absolute path is the fallback.
     */
    private fun canonicalOrAbsolute(file: File): String = try {
        file.canonicalPath
    } catch (_: IOException) {
        file.absolutePath
    }
}
