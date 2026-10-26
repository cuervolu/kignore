package dev.cuervolu.kignore

import dev.cuervolu.kignore.internal.Utf8
import kotlin.test.Test
import kotlin.test.assertEquals

/** The ranged matcher used by rule sets must agree with the string API on every prefix and every base offset. */
class EncodedMatchingTest {
    private val lines =
        listOf("*.log", "build/", "/src/*.kt", "a/**/b", "**/gen", "doc/x", "[a-c]?z", "café", "a/**", "*", "x*y*z")

    private val paths =
        listOf(
            "a.log",
            "build",
            "src/a.kt",
            "src/deep/a.kt",
            "a/b",
            "a/x/y/b",
            "gen",
            "p/q/gen",
            "doc/x",
            "sub/doc/x",
            "bxz",
            "café",
            "a/b/c",
            "xaybz",
            "dir/xyz",
        )

    @Test
    fun rangeOverTheWholePathEqualsTheStringApi() {
        for (line in lines) {
            val pattern = pattern(line)
            for (path in paths) {
                val bytes = Utf8.encode(path)
                for (isDirectory in listOf(false, true)) {
                    assertEquals(
                        pattern.matches(path, isDirectory),
                        pattern.matchesEncoded(bytes, 0, bytes.size, isDirectory),
                        "'$line' on '$path'",
                    )
                }
            }
        }
    }

    @Test
    fun everyPrefixAtASegmentBoundaryEqualsTheStringApiOnThatAncestor() {
        for (line in lines) {
            val pattern = pattern(line)
            for (path in paths) {
                val bytes = Utf8.encode(path)
                for (end in bytes.indices) {
                    if (bytes[end] != '/'.code.toByte()) continue
                    assertEquals(
                        pattern.matches(path.substring(0, end), true),
                        pattern.matchesEncoded(bytes, 0, end, true),
                        "'$line' on the ancestor of '$path' ending at $end",
                    )
                }
            }
        }
    }

    @Test
    fun aBaseOffsetEqualsTheStringApiOnTheRelativePath() {
        for (line in lines) {
            val pattern = pattern(line)
            for (path in paths) {
                val prefixed = "base/dir/$path"
                val bytes = Utf8.encode(prefixed)
                assertEquals(
                    pattern.matches(path, false),
                    pattern.matchesEncoded(bytes, "base/dir/".length, bytes.size, false),
                    "'$line' on '$path' below a base",
                )
            }
        }
    }
}
