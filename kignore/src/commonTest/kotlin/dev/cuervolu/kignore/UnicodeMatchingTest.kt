package dev.cuervolu.kignore

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Git compares bytes, so wildcards consume UTF-8 bytes and not characters. */
class UnicodeMatchingTest {
    @Test
    fun nonAsciiLiteralsMatchThemselves() {
        assertMatches("café", true, "café", "dir/café")
        assertMatches("café", false, "cafe")
        assertMatches("日本語/", true, "日本語/")
        assertMatches("日本語/", false, "日本語")
        assertMatches("ü*", true, "über")
        assertMatches("ü*", false, "uber")
    }

    @Test
    fun charactersOutsideTheBasicPlaneMatchThemselves() {
        assertMatches("😀.txt", true, "😀.txt")
        assertMatches("😀.txt", false, "😁.txt")
        assertMatches("dir𝄞/", true, "dir𝄞/")
        assertMatches("*🚀", true, "x🚀", "a/b🚀")
        assertMatches("*🚀", false, "x🚁")
    }

    @Test
    fun questionMarkConsumesOneByte() {
        assertMatches("caf?", true, "cafe")
        assertMatches("caf?", false, "café")
        assertMatches("a??b", true, "aéb")
        assertMatches("a??b", false, "aééb", "a世b")
        assertMatches("a?b", false, "aéb")
        assertMatches("c????d", true, "c😀d")
        assertMatches("a?b", false, "a😀b")
    }

    @Test
    fun bracketExpressionsWorkOnBytes() {
        assertMatches("a[éè]", false, "aé", "aè", "ae")
        assertMatches("b[^x]", false, "bé", "bx")
        assertMatches("b[^x]", true, "by")
        assertMatches("a[éè][éè][éè][éè]", true, "aéè")
    }

    @Test
    fun starCrossesWholeCharactersAndCombiningMarks() {
        assertMatches("a*", true, "aé", "á", "a")
    }

    @Test
    fun composedAndDecomposedFormsDiffer() {
        assertMatches("café", false, "café")
        assertMatches("café", false, "café")
        assertMatches("café", true, "café")
        assertMatches("naïve", false, "naïve")
    }

    @Test
    fun invisibleAndSpecialSpacesAreOrdinary() {
        assertMatches("a​b", true, "a​b")
        assertMatches("a​b", false, "ab")
        assertMatches("c d", true, "c d")
        assertMatches("c d", false, "c d")
    }

    @Test
    fun anchoredNonAsciiDirectories() {
        assertMatches("/док/*.md", true, "док/a.md")
        assertMatches("/док/*.md", false, "док/b/a.md")
    }

    /**
     * Lone surrogates are built at run time. As literals they would not survive the trip through the JavaScript
     * toolchain, which replaces them with a question mark while writing the compiled test.
     */
    private fun lone(code: Int): String = CharArray(1) { Char(code) }.concatToString()

    @Test
    fun loneSurrogatesAreEncodedAsTheReplacementCharacter() {
        val high = lone(0xD800)
        val broken = "a${high}b"
        assertTrue(pattern("a${high}b").matches(broken))
        assertFalse(pattern("a?b").matches(broken))
        assertTrue(pattern("a???b").matches(broken))
        assertTrue(pattern("a${Char(0xFFFD)}b").matches(broken))
        assertTrue(pattern("*").matches(lone(0xDC00)))
        assertTrue(pattern("[$high]*").matches("${high}x"))
    }
}
