package dev.cuervolu.kignore

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Compares the matcher with a deliberately naive reference on many small random patterns and paths.
 *
 * The reference is memoized recursion over the structure the generator built, written independently of the engine and
 * of the fixtures. A disagreement is a bug in the engine or in the documented semantics, never a reason to touch the
 * reference to make the test pass.
 */
class DifferentialMatcherTest {
    private sealed interface Token {
        class Lit(
            val c: Char,
        ) : Token

        data object Any : Token

        data object Star : Token

        class Set(
            val members: String,
            val negated: Boolean,
            val text: String,
        ) : Token
    }

    private class Seg(
        val tokens: List<Token>?,
    ) {
        val isDoubleStar: Boolean get() = tokens == null
    }

    private val random = Random(20240607)

    private fun randomToken(): Token =
        when (random.nextInt(8)) {
            0 -> Token.Lit('a')
            1 -> Token.Lit('b')
            2 -> Token.Lit('c')
            3 -> Token.Any
            4 -> Token.Star
            5 -> Token.Set("ab", false, "[ab]")
            6 -> Token.Set("a", true, "[!a]")
            else -> Token.Set("ab", false, "[a-b]")
        }

    private fun randomSegment(): Seg {
        if (random.nextInt(5) == 0) return Seg(null)
        val tokens = ArrayList<Token>()
        repeat(1 + random.nextInt(3)) {
            val t = randomToken()
            if (t == Token.Star && tokens.lastOrNull() == Token.Star) return@repeat
            tokens.add(t)
        }
        return Seg(tokens)
    }

    private fun text(seg: Seg): String =
        if (seg.tokens == null) {
            "**"
        } else {
            seg.tokens.joinToString("") {
                when (it) {
                    is Token.Lit -> it.c.toString()
                    Token.Any -> "?"
                    Token.Star -> "*"
                    is Token.Set -> it.text
                }
            }
        }

    private fun segmentMatches(
        tokens: List<Token>,
        s: String,
    ): Boolean {
        // reach[i] tells whether the first i tokens can match the consumed prefix.
        var reach = BooleanArray(tokens.size + 1)
        reach[0] = true
        for (t in 1..tokens.size) if (tokens[t - 1] == Token.Star) reach[t] = reach[t - 1]
        for (ch in s) {
            val next = BooleanArray(tokens.size + 1)
            for (t in 1..tokens.size) {
                val token = tokens[t - 1]
                next[t] =
                    when (token) {
                        Token.Star -> next[t - 1] || reach[t]
                        Token.Any -> reach[t - 1]
                        is Token.Lit -> reach[t - 1] && token.c == ch
                        is Token.Set -> reach[t - 1] && ((ch in token.members) != token.negated)
                    }
            }
            reach = next
        }
        return reach[tokens.size]
    }

    private fun reference(
        segments: List<Seg>,
        path: List<String>,
    ): Boolean {
        val memo = HashMap<Int, Boolean>()

        fun go(
            i: Int,
            j: Int,
        ): Boolean =
            memo.getOrPut(i * 1000 + j) {
                if (i == segments.size) return@getOrPut j == path.size
                val seg = segments[i]
                if (seg.isDoubleStar) {
                    val min = if (i == segments.size - 1) 1 else 0
                    (j + min..path.size).any { go(i + 1, it) }
                } else {
                    j < path.size && segmentMatches(seg.tokens!!, path[j]) && go(i + 1, j + 1)
                }
            }
        return go(0, 0)
    }

    @Test
    fun engineAgreesWithTheReference() {
        var matched = 0
        var total = 0
        repeat(4000) {
            val segments = List(1 + random.nextInt(4)) { randomSegment() }
            val leadingSlash = random.nextInt(3) == 0
            val line = (if (leadingSlash) "/" else "") + segments.joinToString("/") { text(it) }
            val anchored = leadingSlash || segments.size > 1
            val effective = if (anchored) segments else listOf(Seg(null)) + segments
            val pattern = pattern(line)
            repeat(40) {
                val path =
                    List(
                        1 + random.nextInt(5),
                    ) { CharArray(1 + random.nextInt(3)) { "abc"[random.nextInt(3)] }.concatToString() }
                val expected = reference(effective, path)
                assertEquals(
                    expected,
                    pattern.matches(path.joinToString("/")),
                    "'$line' on '${path.joinToString("/")}'",
                )
                total++
                if (expected) matched++
            }
        }
        // Guards against a generator that never produces matches, which would make the comparison vacuous.
        assertTrue(matched > total / 50, "only $matched of $total comparisons matched")
    }
}
