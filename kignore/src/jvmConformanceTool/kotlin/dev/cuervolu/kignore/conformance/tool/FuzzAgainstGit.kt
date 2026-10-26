package dev.cuervolu.kignore.conformance.tool

import dev.cuervolu.kignore.IgnoreDecision
import dev.cuervolu.kignore.IgnoreMatcher
import dev.cuervolu.kignore.IgnoreRuleSet
import dev.cuervolu.kignore.IgnoreScope
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.LongAdder
import kotlin.system.exitProcess

/*
 * Development tool that compares kignore with Git on generated scenarios.
 *
 *   ./gradlew fuzzAgainstGit --args='--seed 1 --count 2000'
 *
 * Every scenario is a pure function of (seed, index), so a divergence is reproduced with the same two numbers.
 * When kignore and Git disagree, the scenario is reduced to a minimal one and printed in the format of
 * conformance/scenarios, ready to be reviewed and added there. See conformance/README.md.
 */

/** SplitMix64, written out so a seed means the same sequence on every Kotlin version. */
private class SplitMix64(
    seed: Long,
) {
    private var state = seed

    fun next(): Long {
        state += -7046029254386353131L
        var z = state
        z = (z xor (z ushr 30)) * -4658895280553007687L
        z = (z xor (z ushr 27)) * -7723592293110705685L
        return z xor (z ushr 31)
    }

    fun below(bound: Int): Int = ((next() ushr 1) % bound).toInt()

    fun chance(percent: Int): Boolean = below(100) < percent

    fun <T> pick(items: List<T>): T = items[below(items.size)]
}

/** A deliberate difference put into the kignore side, to prove the fuzzer can find and reduce a divergence. */
private enum class Fault {
    NONE,

    /** kignore ignores every negated line. */
    DROP_NEGATION,

    /** kignore ignores every line that ends with a slash. */
    DROP_DIRECTORY_ONLY,
}

private class Options(
    val seed: Long,
    val from: Int,
    val count: Int,
    val threads: Int,
    val fault: Fault,
    val maxDivergences: Int,
)

private fun parseOptions(args: Array<String>): Options {
    var seed = 1L
    var from = 0
    var count = 1000
    var threads = minOf(8, Runtime.getRuntime().availableProcessors())
    var fault = Fault.NONE
    var maxDivergences = 3
    var i = 0
    while (i < args.size) {
        val value = args.getOrNull(i + 1)

        fun need() = requireNotNull(value) { "${args[i]} needs a value" }
        when (args[i]) {
            "--seed" -> seed = need().toLong()
            "--from" -> from = need().toInt()
            "--count" -> count = need().toInt()
            "--threads" -> threads = need().toInt()
            "--inject-fault" -> fault = Fault.valueOf(need().uppercase().replace('-', '_'))
            "--max-divergences" -> maxDivergences = need().toInt()
            else -> error("Unknown option ${args[i]}")
        }
        i += 2
    }
    return Options(seed, from, count, threads, fault, maxDivergences)
}

// Generation

// A name must not start with a colon, because Git would read it as a pathspec.
private val NAMES =
    listOf(
        "a",
        "b",
        "c",
        "A",
        "B",
        "ab",
        "ba",
        "a.b",
        ".a",
        "a b",
        "x",
        "xy",
        "é",
        "ü",
        "😀",
        "a-b",
        "a*",
        "a?b",
        "[a]",
        "a\\b",
        "*",
        "build",
        "src",
        "doc",
        "log",
        "a.log",
        "b.txt",
        "keep",
        "a:b",
        "a:]",
        "[:]",
        "]",
        "a]",
        "-",
        "!a",
        "^a",
    )

private val LITERALS = listOf("a", "b", "A", "x", ".", "é", " ", "-", "😀", "ab", "log")

private val CLASSES =
    listOf(
        "[ab]",
        "[!a]",
        "[^a]",
        "[a-b]",
        "[b-a]",
        "[]a]",
        "[a-]",
        "[-a]",
        "[[:alpha:]]",
        "[[:digit:]]",
        "[[:upper:]]",
        "[[:lower:]]",
        "[[:alpha:][:digit:]]",
        "[[:bad:]]",
        "[é]",
        "[a-é]",
        "[\\]]",
        "[\\a]",
        "[a-c]",
        "[!]a]",
        "[A-Z]",
        "[^[:upper:]]",
        "[[]",
        "[.-b]",
        "[\\\\]",
        "[!a-b]",
        "[[:space:]]",
        "[[:punct:]]",
        "[[:a]:]",
        "[[:]",
        "[[::]",
        "[[:alpha]",
        "[[:alpha:]",
        "[[:alpha:]-z]",
        "[a-c-e]",
        "[[:alpha:]-]",
        "[:alpha:]",
        "[[:alpha:]]]",
        "[\\[:alpha:]]",
        "[a\\-c]",
        "[!-]",
        "[--/]",
        "[]-a]",
        "[]]",
        "[!]]",
        "[^]]",
        "[a-a]",
        "[\\-a]",
        "[a-\\]]",
        "[[:digit:]-a]",
        "[a-[:digit:]]",
        "[[.a.]]",
        "[[=a=]]",
        "[a-z-9]",
        "[!!]",
        "[^^]",
        "[\\\\-a]",
        "[[:ALPHA:]]",
        "[[: alpha:]]",
        "[[:alpha :]]",
    )

private val ESCAPES = listOf("\\*", "\\?", "\\[", "\\\\", "\\a", "\\ ", "\\!", "\\#", "\\]")

/** Pieces that, put together at random, give odd bracket expressions, POSIX classes and escapes. */
private val SOUP = listOf("[", "]", ":", "a", "-", "!", "^", "\\", "[:", ":]", "alpha", "\u00E9", "b", "[[:", "/")

private val MALFORMED = listOf("[abc", "a\\", "[[:foo:]]", "[", "\\", "a[", "[a-", "[[:alpha:]", "![", "a/[b", "**/[")

private val MISC = listOf("# note", "", "   ", "\\#a", "\\!a", "#", "!", "/", "!/", "//", "\\", " ")

private class Tree(
    val nodes: LinkedHashMap<String, Boolean>,
    val ignoreDirectories: List<String>,
)

private fun generateTree(rng: SplitMix64): Tree {
    val nodes = LinkedHashMap<String, Boolean>()

    fun add(
        segments: List<String>,
        directory: Boolean,
    ): Boolean {
        val prefixes = (1 until segments.size).map { segments.take(it).joinToString("/") }
        if (prefixes.any { nodes[it] == false }) return false
        val path = segments.joinToString("/")
        if (nodes[path]?.let { it != directory } == true) return false
        for (prefix in prefixes) nodes[prefix] = true
        nodes[path] = directory
        return true
    }
    val deep = rng.chance(8)
    repeat(2 + rng.below(10)) {
        val depth = if (deep) 3 + rng.below(6) else 1 + rng.below(4)
        add(List(depth) { rng.pick(NAMES) }, rng.chance(35))
    }
    val directories = nodes.filterValues { it }.keys.toList()
    val ignoreDirectories = LinkedHashSet<String>()
    ignoreDirectories.add("")
    repeat(rng.below(3)) { if (directories.isNotEmpty()) ignoreDirectories.add(rng.pick(directories)) }
    for (directory in ignoreDirectories) {
        val path = if (directory.isEmpty()) ".gitignore" else "$directory/.gitignore"
        if (nodes[path] == true) continue
        nodes[path] = false
    }
    return Tree(nodes, ignoreDirectories.toList())
}

private fun mutateSegment(
    rng: SplitMix64,
    segment: String,
): String {
    val points = segment.codePoints().toArray()
    if (points.isEmpty()) return segment

    fun text(
        from: Int,
        to: Int,
    ) = String(points, from, to - from)
    val at = rng.below(points.size)
    return when (rng.below(100)) {
        in 0..29 -> {
            segment
        }

        in 30..37 -> {
            "*"
        }

        in 38..47 -> {
            text(0, at) + "?" + text(at + 1, points.size)
        }

        in 48..57 -> {
            text(0, at) + "*"
        }

        in 58..67 -> {
            "*" + text(at, points.size)
        }

        in 68..75 -> {
            text(0, at) + rng.pick(CLASSES) + text(at + 1, points.size)
        }

        in 76..80 -> {
            "**"
        }

        in 81..84 -> {
            segment.uppercase()
        }

        in 85..92 -> {
            segment
                .replace("*", "\\*")
                .replace("?", "\\?")
                .replace("[", "\\[")
                .replace("\\\\", "\\\\\\\\")
        }

        in 93..96 -> {
            text(0, at) + "*" + text(at, points.size)
        }

        else -> {
            "[" + text(at, at + 1) + "]"
        }
    }
}

private fun randomSegment(rng: SplitMix64): String {
    val b = StringBuilder()
    repeat(1 + rng.below(4)) {
        b.append(
            when (rng.below(100)) {
                in 0..34 -> rng.pick(LITERALS)
                in 35..49 -> "*"
                in 50..59 -> "?"
                in 60..79 -> rng.pick(CLASSES)
                in 80..86 -> rng.pick(ESCAPES)
                in 87..96 -> "[" + List(1 + rng.below(7)) { rng.pick(SOUP) }.joinToString("")
                else -> "**"
            },
        )
    }
    return b.toString()
}

private fun generateLine(
    rng: SplitMix64,
    tree: Tree,
    directory: String,
): String {
    val kind = rng.below(100)
    if (kind < 3) return rng.pick(MISC)
    if (kind < 8) return rng.pick(MALFORMED)
    val below = tree.nodes.keys.filter { directory.isEmpty() || it.startsWith("$directory/") }
    val relative = below.map { if (directory.isEmpty()) it else it.removePrefix("$directory/") }
    val segments =
        if (relative.isNotEmpty() && rng.chance(60)) {
            val parts = rng.pick(relative).split("/")
            val start = if (rng.chance(50)) 0 else rng.below(parts.size)
            val end = start + 1 + rng.below(parts.size - start)
            parts.subList(start, end).map { mutateSegment(rng, it) }
        } else {
            List(1 + rng.below(3)) { if (rng.chance(8)) "**" else randomSegment(rng) }
        }
    val b = StringBuilder()
    if (rng.chance(22)) b.append('!')
    when {
        rng.chance(20) -> b.append('/')
        rng.chance(8) -> b.append("**/")
    }
    b.append(segments.joinToString("/"))
    when {
        rng.chance(22) -> b.append('/')
        rng.chance(6) -> b.append("/**")
        rng.chance(3) -> b.append(' ')
        rng.chance(2) -> b.append("\\ ")
    }
    return b.toString()
}

private fun generate(
    seed: Long,
    index: Int,
): Scenario {
    val rng = SplitMix64(seed xor (index.toLong() * -7046029254386353131L))
    val tree = generateTree(rng)
    val files =
        tree.ignoreDirectories.map { directory ->
            val lines = List(1 + rng.below(7)) { generateLine(rng, tree, directory) }
            val path = if (directory.isEmpty()) ".gitignore" else "$directory/.gitignore"
            FileSpec(path, lines.joinToString("\n") + "\n", false)
        }
    val checks = tree.nodes.map { (path, directory) -> CheckSpec(if (directory) "$path/" else path, null, null) }
    val config = if (rng.chance(25)) linkedMapOf("core.ignorecase" to "true") else linkedMapOf()
    return Scenario(
        "fuzz/seed-$seed-index-$index",
        "git-quirk",
        "Found by fuzzAgainstGit with seed $seed and index $index.",
        null,
        config,
        files,
        checks,
    )
}

// Evaluation

private class Diff(
    val path: String,
    val git: String,
    val kignore: String,
)

private class Evaluation(
    val resolved: Scenario,
    val diffs: List<Diff>,
)

private fun linesOf(file: FileSpec): List<String> =
    if (file.content.isEmpty()) emptyList() else file.content.removeSuffix("\n").split("\n")

private fun withLines(
    file: FileSpec,
    lines: List<String>,
): FileSpec = file.copy(content = if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n")

private fun ruleSets(
    sc: Scenario,
    fault: Fault,
): Map<String, IgnoreRuleSet> {
    val ignoreCase = sc.config["core.ignorecase"] == "true"
    val result = HashMap<String, IgnoreRuleSet>()
    for (file in sc.files) {
        if (file.path != ".gitignore" && !file.path.endsWith("/.gitignore")) continue
        val directory = file.path.removeSuffix(".gitignore").removeSuffix("/")
        val kept =
            linesOf(file).filter {
                when (fault) {
                    Fault.DROP_NEGATION -> !it.startsWith("!")
                    Fault.DROP_DIRECTORY_ONLY -> !it.trimEnd().endsWith("/")
                    Fault.NONE -> true
                }
            }
        result[directory] = IgnoreRuleSet.parse(kept.joinToString("\n"), file.path, ignoreCase)
    }
    return result
}

private fun scopeDecision(
    path: String,
    isDirectory: Boolean,
    rules: Map<String, IgnoreRuleSet>,
): IgnoreDecision {
    val parts = path.split("/")
    var scope = IgnoreScope.root(rules[""] ?: IgnoreRuleSet.EMPTY)
    var directory = ""
    for (i in 0 until parts.size - 1) {
        directory = if (directory.isEmpty()) parts[i] else "$directory/${parts[i]}"
        scope = scope.enter(parts[i], rules[directory] ?: IgnoreRuleSet.EMPTY)
    }
    return scope.decide(parts.last(), isDirectory)
}

/** Asks Git, or `null` when the host cannot represent the scenario, for example because of case folding. */
private fun resolveWithGit(sc: Scenario): Scenario? =
    try {
        if (OracleRun.canBatch(sc)) OracleRun.resolveBatch(sc) else OracleRun.resolve(sc)
    } catch (_: IllegalStateException) {
        null
    }

private fun evaluate(
    sc: Scenario,
    fault: Fault,
): Evaluation? {
    val resolved = resolveWithGit(sc) ?: return null
    val rules = ruleSets(sc, fault)
    val matcher = IgnoreMatcher.of(rules)
    val diffs = ArrayList<Diff>()
    for (check in resolved.checks) {
        val isDirectory = check.path.endsWith("/")
        val leaf = check.path.removeSuffix("/")
        val ours =
            try {
                val viaMatcher = matcher.decide(leaf, isDirectory)
                val viaScope = scopeDecision(leaf, isDirectory, rules)
                if (viaMatcher == viaScope) viaMatcher.name else "matcher=$viaMatcher scope=$viaScope"
            } catch (e: RuntimeException) {
                "EXCEPTION ${e::class.simpleName}: ${e.message}"
            }
        if (ours != check.result) diffs.add(Diff(check.path, check.result.orEmpty(), ours))
    }
    return Evaluation(resolved, diffs)
}

// Reduction

private class Reducer(
    private val fault: Fault,
) {
    private fun diverges(sc: Scenario): Boolean = evaluate(sc, fault)?.diffs?.isNotEmpty() == true

    fun reduce(original: Scenario): Scenario {
        var current = original.copy(checks = original.checks.map { CheckSpec(it.path, null, null) })
        val first =
            evaluate(current, fault)
                ?.diffs
                ?.map { it.path }
                ?.toSet()
                .orEmpty()
        current = current.copy(checks = current.checks.filter { it.path in first })
        var progress = true
        var rounds = 0
        while (progress && rounds++ < 6) {
            progress = false
            for (step in listOf(::dropChecks, ::dropConfig, ::dropFiles, ::dropLines, ::shrinkLines, ::shortenPaths)) {
                val next = step(current)
                if (next !== current) {
                    current = next
                    progress = true
                }
            }
        }
        return current
    }

    private inline fun <T> tryRemoving(
        sc: Scenario,
        items: List<T>,
        build: (Scenario, List<T>) -> Scenario,
    ): Scenario {
        var current = sc
        var remaining = items
        var i = remaining.size - 1
        while (i >= 0) {
            val candidate = remaining.filterIndexed { index, _ -> index != i }
            val scenario = build(current, candidate)
            if (diverges(scenario)) {
                current = scenario
                remaining = candidate
            }
            i--
        }
        return current
    }

    private fun dropChecks(sc: Scenario): Scenario =
        if (sc.checks.size < 2) sc else tryRemoving(sc, sc.checks) { s, c -> s.copy(checks = c) }

    private fun dropConfig(sc: Scenario): Scenario {
        if (sc.config.isEmpty()) return sc
        val candidate = sc.copy(config = linkedMapOf())
        return if (diverges(candidate)) candidate else sc
    }

    private fun dropFiles(sc: Scenario): Scenario =
        if (sc.files.size < 2) sc else tryRemoving(sc, sc.files) { s, f -> s.copy(files = f) }

    private fun dropLines(sc: Scenario): Scenario {
        var current = sc
        for (fileIndex in sc.files.indices) {
            val file = current.files.getOrNull(fileIndex) ?: break
            val lines = linesOf(file)
            current =
                tryRemoving(current, lines) { s, remaining ->
                    s.copy(files = s.files.mapIndexed { i, f -> if (i == fileIndex) withLines(f, remaining) else f })
                }
        }
        return current
    }

    /** Removes directory levels from the checked paths and from the places of the ignore files. */
    private fun shortenPaths(sc: Scenario): Scenario {
        var current = sc
        for (index in sc.checks.indices) {
            var path = current.checks[index].path
            var segment = path.removeSuffix("/").count { it == '/' } - 1
            while (segment >= 0) {
                val trailing = if (path.endsWith("/")) "/" else ""
                val parts = path.removeSuffix("/").split("/").toMutableList()
                if (segment < parts.size - 1) {
                    parts.removeAt(segment)
                    val shorter = parts.joinToString("/") + trailing
                    val checks =
                        current.checks.mapIndexed { i, c ->
                            if (i ==
                                index
                            ) {
                                CheckSpec(shorter, null, null)
                            } else {
                                c
                            }
                        }
                    val candidate = current.copy(checks = checks)
                    if (diverges(candidate)) {
                        current = candidate
                        path = shorter
                    }
                }
                segment--
            }
        }
        for (index in sc.files.indices) {
            var path = current.files[index].path
            var segment = path.count { it == '/' } - 1
            while (segment >= 0) {
                val parts = path.split("/").toMutableList()
                if (segment < parts.size - 1) {
                    parts.removeAt(segment)
                    val shorter = parts.joinToString("/")
                    val files = current.files.mapIndexed { i, f -> if (i == index) f.copy(path = shorter) else f }
                    val candidate = current.copy(files = files)
                    if (diverges(candidate)) {
                        current = candidate
                        path = shorter
                    }
                }
                segment--
            }
        }
        return current
    }

    private fun shrinkLines(sc: Scenario): Scenario {
        var current = sc
        for (fileIndex in sc.files.indices) {
            val file = current.files.getOrNull(fileIndex) ?: break
            var lines = linesOf(file)
            for (lineIndex in lines.indices) {
                var line = lines[lineIndex]
                var position = line.length
                while (position > 0) {
                    position = line.offsetByCodePoints(position, -1)
                    val shorter = line.removeRange(position, line.offsetByCodePoints(position, 1))
                    val candidateLines = lines.mapIndexed { i, l -> if (i == lineIndex) shorter else l }
                    val files =
                        current.files.mapIndexed { i, f ->
                            if (i ==
                                fileIndex
                            ) {
                                withLines(f, candidateLines)
                            } else {
                                f
                            }
                        }
                    val candidate = current.copy(files = files)
                    if (diverges(candidate)) {
                        line = shorter
                        lines = candidateLines
                        current = candidate
                    }
                }
            }
        }
        return current
    }
}

private fun render(
    sc: Scenario,
    evaluation: Evaluation?,
): String {
    val out = StringBuilder()
    out.append("scenario ").append(sc.id).append('\n')
    out.append("kind ").append(sc.kind).append('\n')
    out.append("spec ").append(sc.spec).append('\n')
    sc.config.forEach { (k, v) ->
        out
            .append("config ")
            .append(k)
            .append('=')
            .append(v)
            .append('\n')
    }
    for (file in sc.files) {
        out.append("file ").append(escape(file.path, true, false)).append('\n')
        for (line in linesOf(file)) {
            val escaped = escape(line, false, true)
            out.append(if (escaped.isEmpty()) "|" else "| $escaped").append('\n')
        }
    }
    for (check in sc.checks) out.append("path ").append(escape(check.path, true, false)).append('\n')
    evaluation?.diffs?.forEach { out.append("# ${it.path}: git ${it.git}, kignore ${it.kignore}\n") }
    return out.toString()
}

// Coverage

private val FEATURES =
    linkedMapOf(
        "negation" to Regex("^!"),
        "directory-only" to Regex("/ *$"),
        "leading-slash" to Regex("^!?/"),
        "inner-slash" to Regex("[^/]/[^/ ]"),
        "double-star-segment" to Regex("(^|/)\\*\\*(/|$)"),
        "star" to Regex("\\*"),
        "question" to Regex("\\?"),
        "bracket" to Regex("\\["),
        "posix-class" to Regex("\\[:"),
        "range" to Regex("\\[[^]]*[^\\[]-[^]]"),
        "negated-class" to Regex("\\[[!^]"),
        "escape" to Regex("\\\\"),
        "non-ascii" to Regex("[^\\x00-\\x7F]"),
        "trailing-space" to Regex(" $"),
        "comment-or-blank" to Regex("^(#|\\s*$)"),
    )

private class Stats {
    val scenarios = AtomicInteger()
    val skipped = AtomicInteger()
    val ignoreCase = AtomicInteger()
    val nested = AtomicInteger()
    val cases = LongAdder()
    val decisions = ConcurrentHashMap<String, LongAdder>()
    val features = ConcurrentHashMap<String, LongAdder>()
    val lines = LongAdder()
    val invalid = LongAdder()
    val distinctLines = ConcurrentHashMap.newKeySet<String>()
    val shapes = ConcurrentHashMap.newKeySet<String>()
    val diverged = AtomicInteger()

    fun count(
        map: ConcurrentHashMap<String, LongAdder>,
        key: String,
    ) = map.computeIfAbsent(key) { LongAdder() }.increment()

    fun record(evaluation: Evaluation) {
        val sc = evaluation.resolved
        scenarios.incrementAndGet()
        if (sc.config["core.ignorecase"] == "true") ignoreCase.incrementAndGet()
        if (sc.files.size > 1) nested.incrementAndGet()
        for (check in sc.checks) {
            cases.increment()
            count(decisions, check.result.orEmpty())
        }
        for (file in sc.files) {
            val set = IgnoreRuleSet.parse(file.content, file.path)
            invalid.add(set.diagnostics.size.toLong())
            for (line in linesOf(file)) {
                lines.increment()
                distinctLines.add(line)
                shapes.add(line.replace(Regex("[A-Za-z0-9\\u0080-\\uFFFF]+"), "l"))
                for ((name, regex) in FEATURES) if (regex.containsMatchIn(line)) count(features, name)
            }
        }
    }
}

private fun report(
    options: Options,
    stats: Stats,
    gitVersion: String,
    seconds: Double,
) {
    val cases = stats.cases.sum().coerceAtLeast(1)
    val matched = (stats.decisions["IGNORED"]?.sum() ?: 0) + (stats.decisions["INCLUDED"]?.sum() ?: 0)
    println("Git: $gitVersion")
    val last = options.from + options.count - 1
    println("Seed ${options.seed}, indices ${options.from} to $last, fault ${options.fault}")
    println(
        "Scenarios compared: ${stats.scenarios.get()}, skipped by the host: ${stats.skipped.get()}, " +
            "with core.ignorecase: ${stats.ignoreCase.get()}, with nested ignore files: ${stats.nested.get()}",
    )
    println(
        "Cases: ${stats.cases.sum()}, ignored ${stats.decisions["IGNORED"]?.sum() ?: 0}, " +
            "included ${stats.decisions["INCLUDED"]?.sum() ?: 0}, none ${stats.decisions["NONE"]?.sum() ?: 0}, " +
            "matched by a rule: ${"%.1f".format(100.0 * matched / cases)} percent",
    )
    println(
        "Rule lines: ${stats.lines.sum()}, distinct ${stats.distinctLines.size}, " +
            "distinct shapes ${stats.shapes.size}, rejected by kignore as malformed ${stats.invalid.sum()}",
    )
    println(
        "Lines using: " +
            FEATURES.keys.joinToString(", ") { "$it ${stats.features[it]?.sum() ?: 0}" },
    )
    println("Divergent scenarios: ${stats.diverged.get()} in ${"%.1f".format(seconds)} seconds")
}

fun main(args: Array<String>) {
    val options = parseOptions(args)
    val gitVersion = runGit(listOf("git", "--version"), null, emptyMap(), null).trim()
    val stats = Stats()
    val reports = ConcurrentHashMap<Int, String>()
    val started = System.nanoTime()
    val pool = Executors.newFixedThreadPool(options.threads)
    for (index in options.from until options.from + options.count) {
        pool.execute {
            if (stats.diverged.get() >= options.maxDivergences) return@execute
            try {
                val scenario = generate(options.seed, index)
                val evaluation = evaluate(scenario, options.fault)
                if (evaluation == null) {
                    stats.skipped.incrementAndGet()
                    return@execute
                }
                stats.record(evaluation)
                if (evaluation.diffs.isNotEmpty() && stats.diverged.incrementAndGet() <= options.maxDivergences) {
                    val reduced = Reducer(options.fault).reduce(scenario)
                    reports[index] = render(reduced, evaluate(reduced, options.fault))
                }
            } catch (e: Throwable) {
                System.err.println("Index $index failed: $e")
                stats.diverged.incrementAndGet()
                reports[index] = "# index $index crashed the fuzzer: ${e.stackTraceToString()}"
            }
        }
    }
    pool.shutdown()
    pool.awaitTermination(7, TimeUnit.DAYS)
    for ((index, text) in reports.toSortedMap()) {
        println("\n# Reduced divergence at seed ${options.seed}, index $index\n$text")
    }
    report(options, stats, gitVersion, (System.nanoTime() - started) / 1e9)
    if (stats.diverged.get() > 0) exitProcess(1)
}
