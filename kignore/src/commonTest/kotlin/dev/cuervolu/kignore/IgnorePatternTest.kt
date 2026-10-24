package dev.cuervolu.kignore

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IgnorePatternTest {
    @Test
    fun linesWithoutContentAreSkipped() {
        listOf("", " ", "   ", "# comment", "#", "!", "/", "!/", "//", "! ").forEach {
            assertEquals(PatternParseResult.Skipped, IgnorePattern.parse(it), "'$it'")
            assertNull(IgnorePattern.parse(it).patternOrNull)
        }
    }

    @Test
    fun hashOnlyStartsACommentInTheFirstColumn() {
        assertMatches(" #x", true, " #x")
        assertMatches("\\#x", true, "#x")
        assertMatches("a#b", true, "a#b")
        assertMatches("\\#x", false, "x")
    }

    @Test
    fun exposesWhatTheLineSaid() {
        val negated = pattern("!build/")
        assertTrue(negated.isNegated)
        assertTrue(negated.isDirectoryOnly)
        assertEquals("!build/", negated.source)
        val plain = pattern("*.log")
        assertFalse(plain.isNegated)
        assertFalse(plain.isDirectoryOnly)
    }

    @Test
    fun escapedBangIsNotANegation() {
        val pattern = pattern("\\!important")
        assertFalse(pattern.isNegated)
        assertTrue(pattern.matches("!important"))
    }

    @Test
    fun trailingSpacesAreTrimmedUnlessEscaped() {
        assertMatches("foo   ", true, "foo")
        assertMatches("foo   ", false, "foo ")
        assertMatches("foo\\ ", true, "foo ")
        assertMatches("foo\\ ", false, "foo")
        assertMatches("a\\   ", true, "a ")
        assertMatches("b \\  ", true, "b  ")
        assertMatches("a\\\\ ", true, "a\\")
        assertMatches("tab\t", true, "tab\t")
        assertMatches("  lead", true, "  lead")
    }

    @Test
    fun literalsMatchWholeNames() {
        assertMatches("foo", true, "foo", "a/foo", "a/b/foo", "foo/")
        assertMatches("foo", false, "foobar", "xfoo", "foo/bar", "fo")
    }

    @Test
    fun starStaysInsideASegment() {
        assertMatches("*.log", true, "a.log", ".log", "dir/a.log")
        assertMatches("*.log", false, "a.log.bak", "a/log")
        assertMatches("a*z", true, "az", "abz", "abcz/")
        assertMatches("a*z", false, "a/z", "a/bz")
        assertMatches("*", true, "a", ".hidden", "dir/", "x/y")
        assertMatches("doc/*.md", true, "doc/a.md", "doc/.md")
        assertMatches("doc/*.md", false, "doc/sub/a.md", "other/doc/a.md")
    }

    @Test
    fun questionMarkMatchesOneNonSeparatorByte() {
        assertMatches("file?.txt", true, "file1.txt")
        assertMatches("file?.txt", false, "file.txt", "file12.txt", "file/.txt")
        assertMatches("a?b", false, "a/b", "ab")
    }

    @Test
    fun leadingSlashAnchors() {
        assertMatches("/foo", true, "foo", "foo/")
        assertMatches("/foo", false, "a/foo", "a/b/foo")
        assertMatches("/*.c", true, "cat-file.c", "a.c/")
        assertMatches("/*.c", false, "mozilla-sha1/sha1.c")
    }

    @Test
    fun middleSlashAnchors() {
        assertMatches("doc/frotz", true, "doc/frotz")
        assertMatches("doc/frotz", false, "a/doc/frotz", "doc", "doc/frotz/x")
        assertMatches("a/*", true, "a/b")
        assertMatches("a/*", false, "x/a/b", "a")
    }

    @Test
    fun trailingSlashRestrictsToDirectoriesAndDoesNotAnchor() {
        assertMatches("build/", true, "build/", "src/build/", "a/b/build/")
        assertMatches("build/", false, "build", "src/build")
        assertMatches("doc/frotz/", true, "doc/frotz/")
        assertMatches("doc/frotz/", false, "doc/frotz", "a/doc/frotz/")
    }

    @Test
    fun onlyTheLastTrailingSlashIsRemoved() {
        assertMatches("a//", false, "a", "a/")
        assertMatches("b//c", false, "b/c", "b/c/")
    }

    @Test
    fun leadingDoubleStarMatchesInAllDirectories() {
        assertMatches("**/foo", true, "foo", "a/foo", "a/b/foo")
        assertMatches("**/foo", false, "foo/bar", "xfoo", "a/xfoo")
        assertMatches("**/foo/bar", true, "foo/bar", "a/foo/bar", "a/b/foo/bar")
        assertMatches("**/foo/bar", false, "foo/baz/bar", "bar")
    }

    @Test
    fun trailingDoubleStarMatchesWhatIsInside() {
        assertMatches("abc/**", true, "abc/x", "abc/x/y", "abc/x/")
        assertMatches("abc/**", false, "abc", "abc/", "other/abc/x", "abcd/x")
        assertMatches("/**", true, "a", "a/b", "a/")
        assertMatches("*/**", true, "a/b", "a/b/c")
        assertMatches("*/**", false, "a", "a/")
    }

    @Test
    fun middleDoubleStarMatchesZeroOrMoreDirectories() {
        assertMatches("a/**/b", true, "a/b", "a/x/b", "a/x/y/b", "a/x/y/z/b")
        assertMatches("a/**/b", false, "a/xb", "ab", "x/a/b", "a/b/c")
        assertMatches("src/**/*.gen.kt", true, "src/A.gen.kt", "src/a/b/A.gen.kt")
        assertMatches("src/**/*.gen.kt", false, "src/A.kt", "other/src/A.gen.kt", "src/gen.kt")
    }

    @Test
    fun doubleStarAtBothEnds() {
        assertMatches("**/cache/**", true, "cache/a", "x/cache/a", "x/y/cache/a/b")
        assertMatches("**/cache/**", false, "cache", "x/cache", "mycache/a")
    }

    @Test
    fun loneDoubleStarMatchesEveryName() {
        assertMatches("**", true, "a", "a/b", "a/b/c", ".x")
        assertMatches("**/**", true, "a", "a/b", "a/")
        assertMatches("**/", true, "a/", "a/b/")
        assertMatches("**/", false, "a")
    }

    @Test
    fun doubleStarNextToTextIsAnOrdinaryStar() {
        assertMatches("a**b", true, "ab", "a-b", "axxb")
        assertMatches("a**b", false, "a/b")
        assertMatches("c/x**", true, "c/x", "c/xyz")
        assertMatches("c/x**", false, "c/x/y")
        assertMatches("**y/d", true, "zy/d", "y/d")
        assertMatches("**y/d", false, "z/y/d", "a/zy/d")
    }

    @Test
    fun threeOrMoreStarsMakeADoubleStarSegment() {
        assertMatches("a/***/b", true, "a/b", "a/x/b", "a/x/y/b")
        assertMatches("***.tmp", true, "x.tmp", "d/x.tmp")
    }

    @Test
    fun bracketExpressions() {
        assertMatches("file[0-9].txt", true, "file0.txt", "file9.txt")
        assertMatches("file[0-9].txt", false, "filea.txt", "file10.txt", "file.txt")
        assertMatches("b[!xyz]", true, "bw")
        assertMatches("b[!xyz]", false, "bx")
        assertMatches("c[^xyz]", true, "cw")
        assertMatches("c[^xyz]", false, "cx", "c/")
        assertMatches("a[]]b", true, "a]b")
        assertMatches("a[]]b", false, "ab", "a[b")
        assertMatches("c[!]]d", true, "c-d")
        assertMatches("c[!]]d", false, "c]d")
        assertMatches("a[-x]", true, "a-", "ax")
        assertMatches("b[x-]", true, "b-", "bx")
        assertMatches("c[a-]", true, "c-", "ca")
        assertMatches("a[*?]b", true, "a*b", "a?b")
        assertMatches("a[*?]b", false, "acb")
    }

    @Test
    fun reversedRangeKeepsOnlyItsFirstCharacter() {
        assertMatches("a[z-a]", true, "az")
        assertMatches("a[z-a]", false, "aa", "am")
    }

    @Test
    fun bracketExpressionsNeverMatchASlash() {
        assertMatches("a[!x]b", true, "a-b")
        assertMatches("a[!x]b", false, "a/b")
        assertMatches("a[/]b", false, "a/b", "ab")
        assertMatches("c[a/]d", true, "cad")
        assertMatches("c[a/]d", false, "c/d")
    }

    @Test
    fun posixCharacterClasses() {
        assertMatches("a[[:digit:]]", true, "a5")
        assertMatches("a[[:digit:]]", false, "aa")
        assertMatches("b[[:upper:]]", true, "bA")
        assertMatches("c[[:alpha:][:digit:]]", true, "cx", "c5")
        assertMatches("c[[:alpha:][:digit:]]", false, "c-")
        assertMatches("d[[:space:]]", true, "d ")
        assertMatches("e[![:alnum:]]", true, "e-")
        assertMatches("e[![:alnum:]]", false, "ex", "e5")
        assertMatches("f[[:xdigit:]]", true, "fa", "fF", "f9")
        assertMatches("g[[:punct:]]", true, "g!", "g~")
        assertMatches("g[[:punct:]]", false, "ga")
    }

    @Test
    fun backslashEscapes() {
        assertMatches("a\\*b", true, "a*b")
        assertMatches("a\\*b", false, "aXb")
        assertMatches("c\\?d", true, "c?d")
        assertMatches("e\\[f\\]", true, "e[f]")
        assertMatches("\\a\\b\\c", true, "abc")
        assertMatches("\\a\\b\\c", false, "\\a\\b\\c")
        assertMatches("a\\\\b", true, "a\\b")
        assertMatches("c\\\\*", true, "c\\", "c\\x")
        assertMatches("c\\\\*", false, "cx")
        assertMatches("a[\\]]b", true, "a]b")
        assertMatches("a[\\]]b", false, "a\\b")
        assertMatches("c[\\-x]", true, "c-", "cx")
        assertMatches("c[\\-x]", false, "c\\")
        assertMatches("def\\\\", true, "def\\")
    }

    @Test
    fun escapedSlashSeparatesSegments() {
        assertMatches("a\\/b", true, "a/b")
        assertMatches("a\\/b", false, "a\\/b", "x/a/b")
    }

    @Test
    fun dotSegmentsInThePatternNeverMatchNormalizedPaths() {
        assertMatches("./foo", false, "foo", "./foo")
        assertMatches("bar/./baz", false, "bar/baz")
        assertMatches("...", true, "...", "a/...")
        assertMatches(".", false, ".hidden")
    }

    @Test
    fun emptySegmentsInThePatternNeverMatch() {
        assertMatches("//foo", false, "foo", "a/foo", "foo/")
        assertMatches("a//b", false, "a/b")
    }

    @Test
    fun regexMetacharactersAreLiteral() {
        assertMatches("a.b", true, "a.b")
        assertMatches("a.b", false, "axb")
        assertMatches("c+d", true, "c+d")
        assertMatches("f{1,2}", true, "f{1,2}")
        assertMatches("g|h", true, "g|h")
        assertMatches("i^j", true, "i^j")
        assertMatches("k\$l", true, "k\$l")
        assertMatches("(e)", true, "(e)")
    }

    @Test
    fun negationDoesNotChangeWhatMatches() {
        val pattern = pattern("!keep.txt")
        assertTrue(pattern.matches("keep.txt"))
        assertTrue(pattern.matches("a/keep.txt"))
        assertFalse(pattern.matches("other.txt"))
    }

    @Test
    fun directoryOnlyPatternsNeedADirectory() {
        val pattern = pattern("build/")
        assertFalse(pattern.matches("build"))
        assertFalse(pattern.matches("build", isDirectory = false))
        assertTrue(pattern.matches("build", isDirectory = true))
    }

    @Test
    fun aCompiledPatternIsReusableAndStable() {
        val pattern = pattern("src/**/*.kt")
        repeat(1000) { i ->
            assertTrue(pattern.matches("src/a$i/B.kt"))
            assertFalse(pattern.matches("src/a$i/B.java"))
        }
    }

    @Test
    fun parsingTheSameLineTwiceGivesEqualResults() {
        assertEquals(IgnorePattern.parse("[abc"), IgnorePattern.parse("[abc"))
        assertEquals(PatternParseResult.Skipped, IgnorePattern.parse("# x"))
    }
}
