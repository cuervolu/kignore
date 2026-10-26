package dev.cuervolu.kignore.benchmark

import dev.cuervolu.kignore.IgnoreMatcher
import dev.cuervolu.kignore.IgnorePattern
import dev.cuervolu.kignore.IgnoreRuleSet
import dev.cuervolu.kignore.IgnoreScope
import dev.cuervolu.kignore.PatternParseResult
import dev.cuervolu.kignore.internal.Utf8
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State

/** Time to compile the text of an ignore file. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MILLISECONDS)
class ParseBenchmark {
    @Param("20", "500", "5000")
    var rules: Int = 0

    private lateinit var text: String

    @Setup
    fun prepare() {
        text = Workload.create(10, rules, 3, 0).rulesText
    }

    @Benchmark
    fun parse(): IgnoreRuleSet = IgnoreRuleSet.parse(text)
}

/** One pattern against a prepared set of paths, half of which match. The result is time for the whole set. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
class PatternBenchmark {
    @Param("*.log", "build/", "/src/gen/*.o", "**/gen/**/*.o", "f[a-c]??.[ch]", "**/a/**/b/**/c/**/d")
    var line: String = ""

    private lateinit var pattern: IgnorePattern
    private lateinit var paths: List<String>

    @Setup
    fun prepare() {
        pattern = (IgnorePattern.parse(line) as PatternParseResult.Parsed).pattern
        val rng = Rng(7)
        paths =
            List(1000) { i ->
                val depth = 2 + rng.below(8)
                val dirs = List(depth) { if (rng.below(3) == 0) "gen" else "d${rng.below(10)}" }
                val base = if (i % 2 == 0) "src/gen" else dirs.joinToString("/")
                "$base/fa${i % 7}b${i % 3}.${if (i % 4 == 0) "log" else "o"}"
            }
    }

    @Benchmark
    fun matchAll(): Int {
        var hits = 0
        for (path in paths) if (pattern.matches(path)) hits++
        return hits
    }
}

/** The same tree and rules, asked about every entry one at a time or walked once with a scope. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MILLISECONDS)
class TreeBenchmark {
    /** Paths, rules, depth and percent of excluded paths. Each preset moves one axis away from `10k-20r-d8-x0`. */
    @Param(
        "1k-20r-d8-x0",
        "10k-20r-d8-x0",
        "100k-20r-d8-x0",
        "2k-20r-d8-x0",
        "2k-500r-d8-x0",
        "2k-5000r-d8-x0",
        "10k-20r-d3-x0",
        "2k-20r-d30-x0",
        "10k-20r-d8-x50",
        "10k-20r-d8-x90",
    )
    var preset: String = ""

    private lateinit var workload: Workload
    private lateinit var rules: IgnoreRuleSet
    private lateinit var matcher: IgnoreMatcher

    @Setup
    fun prepare() {
        workload = Workload.preset(preset)
        rules = IgnoreRuleSet.parse(workload.rulesText)
        matcher = IgnoreMatcher.of(rules)
    }

    @Benchmark
    fun independentQueries(): Int {
        var ignored = 0
        for (entry in workload.entries) if (matcher.ignores(entry.path, entry.isDirectory)) ignored++
        return ignored
    }

    @Benchmark
    fun incrementalWalk(): Int = walk(workload.root, IgnoreScope.root(rules))

    /** Enters first and looks at `isIgnored`, which evaluates each directory once. It is only possible without an ignore file to read. */
    @Benchmark
    fun incrementalWalkEnteringFirst(): Int = walkEnteringFirst(workload.root, IgnoreScope.root(rules))

    private fun walkEnteringFirst(
        directory: Dir,
        scope: IgnoreScope,
    ): Int {
        var visited = 0
        for ((name, child) in directory.dirs) {
            val entered = scope.enter(name)
            if (entered.isIgnored) continue
            visited += walkEnteringFirst(child, entered)
        }
        for (file in directory.files) if (!scope.ignores(file)) visited++
        return visited
    }

    private fun walk(
        directory: Dir,
        scope: IgnoreScope,
    ): Int {
        var visited = 0
        for ((name, child) in directory.dirs) {
            if (scope.ignores(name, isDirectory = true)) continue
            visited += walk(child, scope.enter(name))
        }
        for (file in directory.files) if (!scope.ignores(file)) visited++
        return visited
    }
}

/** Cost of turning paths into UTF-8, alone and as part of a query, for one to four bytes per character. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
class Utf8Benchmark {
    @Param("ascii", "two-byte", "three-byte", "four-byte")
    var width: String = ""

    private lateinit var paths: List<String>
    private val matcher = IgnoreMatcher.of(IgnoreRuleSet.parse(Workload.create(10, 20, 3, 0).rulesText))

    @Setup
    fun prepare() {
        val symbols =
            when (width) {
                "ascii" -> "abcdefghij".map { it.toString() }
                "two-byte" -> "\u00E9\u00E8\u00FC\u00F1\u00E7\u00E0\u00F6\u00E4\u00E5\u00F8".map { it.toString() }
                "three-byte" -> "\u4E2D\u6587\u5B57\u7B26\u6D4B\u8BD5\u6570\u636E\u6587\u4EF6".map { it.toString() }
                else -> (0 until 10).map { String(Character.toChars(0x1F600 + it)) }
            }
        val rng = Rng(11)
        paths =
            List(1000) { List(6) { List(8) { symbols[rng.below(symbols.size)] }.joinToString("") }.joinToString("/") }
    }

    @Benchmark
    fun encodeOnly(): Int {
        var bytes = 0
        for (path in paths) bytes += Utf8.encode(path).size
        return bytes
    }

    @Benchmark
    fun query(): Int {
        var ignored = 0
        for (path in paths) if (matcher.ignores(path)) ignored++
        return ignored
    }
}
