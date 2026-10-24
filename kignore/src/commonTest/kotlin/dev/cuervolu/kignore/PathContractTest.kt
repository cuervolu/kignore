package dev.cuervolu.kignore

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Paths must be normalized and relative. Anything else matches nothing and never throws. */
class PathContractTest {
    private val everything =
        listOf("*", "**", "**/*", "/**", "*/**", "?*", "[!x]*", ".*", "src", "*src*", "**/src", "**/src/**")

    private fun assertNeverMatches(vararg paths: String) {
        for (line in everything) {
            val pattern = pattern(line)
            for (path in paths) {
                assertFalse(pattern.matches(path), "'$line' on '$path'")
                assertFalse(pattern.matches(path, isDirectory = true), "'$line' on directory '$path'")
            }
        }
    }

    @Test
    fun emptyPath() = assertNeverMatches("")

    @Test
    fun currentDirectorySegments() = assertNeverMatches(".", "./src", "a/./b", "src/.", "./")

    @Test
    fun parentDirectorySegments() = assertNeverMatches("..", "../src", "a/../b", "src/..", "src/../src")

    @Test
    fun absolutePaths() = assertNeverMatches("/", "/src", "/abs/path", "//src")

    @Test
    fun repeatedSeparators() = assertNeverMatches("a//b", "src//", "//", "a///b")

    @Test
    fun trailingSeparator() = assertNeverMatches("src/", "a/b/", "a/")

    @Test
    fun aDirectoryIsDescribedByTheFlagOnly() {
        val pattern = pattern("src")
        assertTrue(pattern.matches("src", isDirectory = true))
        assertFalse(pattern.matches("src/", isDirectory = true))
    }

    @Test
    fun backslashIsAnOrdinaryCharacter() {
        assertTrue(pattern("*").matches("a\\b"))
        assertTrue(pattern("a\\\\b").matches("a\\b"))
        assertFalse(pattern("a/b").matches("a\\b"))
        assertFalse(pattern("a").matches("a\\b"))
    }

    @Test
    fun dotNamesThatAreNotDotSegmentsAreFine() {
        assertTrue(pattern("*").matches("..."))
        assertTrue(pattern("*").matches(".a"))
        assertTrue(pattern("*").matches("a./b.."))
    }

    @Test
    fun spacesAndOtherBytesAreOrdinary() {
        assertTrue(pattern("*").matches("a b"))
        assertTrue(pattern("a\\ b").matches("a b"))
    }
}
