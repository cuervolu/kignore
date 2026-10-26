package dev.cuervolu.kignore.conformance.tool

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

// Scenario model, escapes and the Git oracle, shared by the fixture generator and the fuzzer.

internal val DEFAULT_CONFIG =
    linkedMapOf(
        "core.ignorecase" to "false",
        "core.precomposeunicode" to "false",
        "core.quotepath" to "false",
        "core.autocrlf" to "false",
        "core.symlinks" to "false",
    )

internal data class FileSpec(
    val path: String,
    val content: String,
    val noEol: Boolean,
)

internal data class CheckSpec(
    val path: String,
    val result: String?,
    val rule: String?,
)

internal data class Scenario(
    val id: String,
    val kind: String,
    val spec: String,
    val note: String?,
    val config: Map<String, String>,
    val files: List<FileSpec>,
    val checks: List<CheckSpec>,
)

// Escapes shared with the Kotlin fixture parser, introduced by a backtick: `` `n `r `t `s `uXXXX `u{X...}
// The backtick is used instead of the backslash so gitignore escapes can be written as they are.

internal fun unescape(s: String): String {
    val b = StringBuilder()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c != '`') {
            b.append(c)
            i++
            continue
        }
        when (val e = s[++i]) {
            '`' -> {
                b.append('`')
            }

            'n' -> {
                b.append('\n')
            }

            'r' -> {
                b.append('\r')
            }

            't' -> {
                b.append('\t')
            }

            's' -> {
                b.append(' ')
            }

            'u' -> {
                if (s[i + 1] == '{') {
                    val end = s.indexOf('}', i)
                    b.appendCodePoint(s.substring(i + 2, end).toInt(16))
                    i = end
                } else {
                    b.append(s.substring(i + 1, i + 5).toInt(16).toChar())
                    i += 4
                }
            }

            else -> {
                error("Unknown escape `$e in $s")
            }
        }
        i++
    }
    return b.toString()
}

/** Escapes what is invisible or ambiguous so the text files stay readable and stable under editors. */
internal fun escape(
    s: String,
    escapeSpaces: Boolean,
    escapeTrailingSpace: Boolean,
): String {
    val b = StringBuilder()
    var trailingStart = s.length
    while (trailingStart > 0 && (s[trailingStart - 1] == ' ' || s[trailingStart - 1] == '\t')) trailingStart--
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        when {
            cp == '`'.code -> {
                b.append("``")
            }

            cp == '\n'.code -> {
                b.append("`n")
            }

            cp == '\r'.code -> {
                b.append("`r")
            }

            cp == '\t'.code -> {
                b.append("`t")
            }

            cp == ' '.code && (escapeSpaces || (escapeTrailingSpace && i >= trailingStart)) -> {
                b.append("`s")
            }

            cp == ' '.code -> {
                b.append(' ')
            }

            isInvisible(cp) -> {
                if (cp <= 0xFFFF) b.append("`u%04X".format(cp)) else b.append("`u{%X}".format(cp))
            }

            else -> {
                b.appendCodePoint(cp)
            }
        }
        i += Character.charCount(cp)
    }
    return b.toString()
}

internal val INVISIBLE_TYPES =
    setOf(
        Character.CONTROL,
        Character.FORMAT,
        Character.NON_SPACING_MARK,
        Character.ENCLOSING_MARK,
        Character.COMBINING_SPACING_MARK,
        Character.SPACE_SEPARATOR,
        Character.LINE_SEPARATOR,
        Character.PARAGRAPH_SEPARATOR,
        Character.UNASSIGNED,
        Character.PRIVATE_USE,
        Character.SURROGATE,
    ).map { it.toInt() }.toSet()

internal fun isInvisible(cp: Int): Boolean = cp < 0x20 || cp == 0x7F || Character.getType(cp) in INVISIBLE_TYPES

// Oracle

/** What every fast oracle run shares: a template `.git` directory and empty files for the isolated configuration. */
internal class SharedOracleState private constructor(
    val template: Path,
    val emptyFile: Path,
    val env: Map<String, String>,
) {
    companion object {
        private val instance: SharedOracleState by lazy {
            val root = Files.createTempDirectory("kignore-oracle-shared")
            Runtime.getRuntime().addShutdownHook(Thread { deleteTree(root) })
            val empty = root.resolve("empty").also { it.writeText("") }
            val home = Files.createDirectory(root.resolve("home"))
            val templateDir = Files.createDirectory(root.resolve("template"))
            val repo = Files.createDirectory(root.resolve("repo"))
            val env =
                linkedMapOf(
                    "GIT_CONFIG_GLOBAL" to empty.toString(),
                    "GIT_CONFIG_SYSTEM" to empty.toString(),
                    "GIT_CONFIG_NOSYSTEM" to "1",
                    "HOME" to home.toString(),
                    "USERPROFILE" to home.toString(),
                    "XDG_CONFIG_HOME" to home.toString(),
                )
            runGit(listOf("git", "init", "-q", "--template=$templateDir"), repo, env, null)
            SharedOracleState(repo.resolve(".git"), empty, env)
        }

        fun get(): SharedOracleState = instance
    }
}

internal fun copyTree(
    from: Path,
    to: Path,
) {
    Files.walk(from).use { walk ->
        for (source in walk) {
            val target = to.resolve(from.relativize(source).toString())
            if (Files.isDirectory(source)) Files.createDirectories(target) else Files.copy(source, target)
        }
    }
}

internal class OracleRepo(
    val repo: Path,
    val env: Map<String, String>,
    val base: Set<String>,
)

internal object OracleRun {
    /** Builds an isolated repository holding the files of [sc]. Git never sees the user's or the system's config. */
    fun prepare(
        sc: Scenario,
        tmp: Path,
    ): OracleRepo {
        val emptyConfig = tmp.resolve("empty.gitconfig").also { it.writeText("") }
        val emptyExcludes = tmp.resolve("empty.excludes").also { it.writeText("") }
        val emptyTemplate = Files.createDirectory(tmp.resolve("template"))
        val home = Files.createDirectory(tmp.resolve("home"))
        val repo = Files.createDirectory(tmp.resolve("repo"))
        val env =
            linkedMapOf(
                "GIT_CONFIG_GLOBAL" to emptyConfig.toString(),
                "GIT_CONFIG_SYSTEM" to emptyConfig.toString(),
                "GIT_CONFIG_NOSYSTEM" to "1",
                "HOME" to home.toString(),
                "USERPROFILE" to home.toString(),
                "XDG_CONFIG_HOME" to home.toString(),
            )

        runGit(listOf("git", "init", "-q", "--template=$emptyTemplate"), repo, env, null)
        val config = LinkedHashMap(DEFAULT_CONFIG)
        config["core.excludesFile"] = emptyExcludes.toString()
        config.putAll(sc.config)
        for ((k, v) in config) runGit(listOf("git", "config", "--local", k, v), repo, env, null)

        for (f in sc.files) {
            val target = repo.resolve(f.path)
            check(!target.exists()) { "${sc.id}: host filesystem collision writing ${f.path}" }
            target.parent.createDirectories()
            Files.write(target, f.content.toByteArray(Charsets.UTF_8))
        }
        val base = names(repo)
        check(base == prefixes(sc.files.map { it.path })) {
            "${sc.id}: host filesystem altered file names, found $base"
        }
        return OracleRepo(repo, env, base)
    }

    /**
     * `false` when a path is a file in one check and a directory in another, or when two names could be the same
     * entry on a host that folds case or normalizes Unicode, so no single tree holds them all.
     */
    fun canBatch(sc: Scenario): Boolean {
        val isDirectory = HashMap<String, Boolean>()
        val hostNames = HashMap<String, String>()

        fun claim(
            path: String,
            directory: Boolean,
        ): Boolean {
            val folded =
                java.text.Normalizer
                    .normalize(path, java.text.Normalizer.Form.NFD)
                    .lowercase()
            if (hostNames.getOrPut(folded) { path } != path) return false
            return isDirectory.getOrPut(path) { directory } == directory
        }

        fun claimWithParents(
            path: String,
            directory: Boolean,
        ): Boolean {
            val parts = path.split("/")
            for (i in 1 until parts.size) if (!claim(parts.take(i).joinToString("/"), true)) return false
            return claim(path, directory)
        }
        return sc.files.all { claimWithParents(it.path, false) } &&
            sc.checks.all { claimWithParents(it.path.removeSuffix("/"), it.path.endsWith("/")) }
    }

    /**
     * Asks Git about every check at once, with all the paths present in the repository. Much faster than [resolve]:
     * the repository comes from a template and the configuration travels in the environment, so a scenario costs one
     * Git process. It is only trusted for the fuzzer because `verifyBatchOracle` shows it gives the same answers as
     * [resolve] on every conformance scenario that fits in one tree.
     */
    fun resolveBatch(sc: Scenario): Scenario {
        val tmp = Files.createTempDirectory("kignore-oracle")
        try {
            val shared = SharedOracleState.get()
            val repo = Files.createDirectory(tmp.resolve("repo"))
            copyTree(shared.template, repo.resolve(".git"))
            val config = LinkedHashMap(DEFAULT_CONFIG)
            config["core.excludesFile"] = shared.emptyFile.toString()
            config.putAll(sc.config)
            val env = LinkedHashMap(shared.env)
            env["GIT_CONFIG_COUNT"] = config.size.toString()
            config.entries.forEachIndexed { i, (k, v) ->
                env["GIT_CONFIG_KEY_$i"] = k
                env["GIT_CONFIG_VALUE_$i"] = v
            }
            for (f in sc.files) {
                val target = repo.resolve(f.path)
                check(!target.exists()) { "${sc.id}: host filesystem collision writing ${f.path}" }
                target.parent.createDirectories()
                Files.write(target, f.content.toByteArray(Charsets.UTF_8))
            }
            for (c in sc.checks) {
                val leaf = c.path.removeSuffix("/")
                val target = repo.resolve(leaf)
                if (c.path.endsWith("/")) {
                    check(!target.exists() || Files.isDirectory(target)) { "${sc.id}: '${c.path}' is a file" }
                    target.createDirectories()
                } else {
                    check(!target.exists() || !Files.isDirectory(target)) { "${sc.id}: '${c.path}' is a directory" }
                    target.parent.createDirectories()
                    if (!target.exists()) Files.write(target, ByteArray(0))
                }
            }
            val expected = prefixes(sc.files.map { it.path } + sc.checks.map { it.path.removeSuffix("/") })
            val found = names(repo)
            check(found == expected) { "${sc.id}: host filesystem altered names, expected $expected but found $found" }
            val leaves = sc.checks.map { it.path.removeSuffix("/") }
            val output =
                runGit(
                    listOf("git", "check-ignore", "--no-index", "-v", "-n", "-z", "--stdin"),
                    repo,
                    env,
                    leaves.joinToString("") { it + "\u0000" },
                    allowOne = true,
                )
            val fields = output.split("\u0000").dropLast(1)
            check(fields.size == 4 * leaves.size) { "${sc.id}: unexpected check-ignore output" }
            val resolved =
                sc.checks.mapIndexed { i, c ->
                    val r = fields.subList(4 * i, 4 * i + 4)
                    check(r[3] == leaves[i]) { "${sc.id}: Git answered for '${r[3]}' instead of '${leaves[i]}'" }
                    if (r[0].isEmpty()) {
                        CheckSpec(c.path, "NONE", null)
                    } else {
                        val result = if (r[2].startsWith("!")) "INCLUDED" else "IGNORED"
                        CheckSpec(c.path, result, "${r[0]}:${r[1]}:${r[2]}")
                    }
                }
            return sc.copy(checks = resolved)
        } finally {
            deleteTree(tmp)
        }
    }

    fun resolve(sc: Scenario): Scenario {
        val tmp = Files.createTempDirectory("kignore-oracle")
        try {
            val (repo, env, base) = prepare(sc, tmp).let { Triple(it.repo, it.env, it.base) }

            // Git decides whether a path is a directory by looking at the filesystem, and a trailing slash in the
            // argument is not enough for negated or directory-only rules; it can even make "*" match the empty
            // remainder. The slash is therefore removed from the argument. Each path is therefore materialized on its
            // own (directory when it ends in a slash, empty file otherwise), which also keeps names that differ only
            // by case or normalization from colliding on case-insensitive hosts.
            val resolved =
                sc.checks.map { c ->
                    val leaf = c.path.removeSuffix("/")
                    val target = repo.resolve(leaf)
                    if (c.path.endsWith("/")) {
                        check(
                            !target.exists() || Files.isDirectory(target),
                        ) { "${sc.id}: '${c.path}' is a file in the scenario" }
                        target.createDirectories()
                    } else {
                        check(!target.exists() || !Files.isDirectory(target)) {
                            "${sc.id}: '${c.path}' needs a trailing slash, it is a directory in the scenario"
                        }
                        target.parent.createDirectories()
                        if (!target.exists()) Files.write(target, ByteArray(0))
                    }
                    try {
                        val expected = prefixes(sc.files.map { it.path } + leaf)
                        val found = names(repo)
                        check(found == expected) {
                            "${sc.id}: host filesystem altered names for '${c.path}', expected $expected but found $found"
                        }
                        // Each record is source NUL line NUL pattern NUL path NUL, with empty fields for no match.
                        val output =
                            runGit(
                                listOf("git", "check-ignore", "--no-index", "-v", "-n", "-z", "--stdin"),
                                repo,
                                env,
                                leaf + "\u0000",
                                allowOne = true,
                            )
                        val fields = output.split("\u0000")
                        check(fields.size == 5) {
                            "${sc.id}: unexpected check-ignore output ${output.replace('\u0000', '|')}"
                        }
                        check(fields[3] == leaf) { "${sc.id}: Git answered for '${fields[3]}' instead of '$leaf'" }
                        if (fields[0].isEmpty()) {
                            CheckSpec(c.path, "NONE", null)
                        } else {
                            val result = if (fields[2].startsWith("!")) "INCLUDED" else "IGNORED"
                            CheckSpec(c.path, result, "${fields[0]}:${fields[1]}:${fields[2]}")
                        }
                    } finally {
                        removeNewEntries(repo, base)
                    }
                }
            return sc.copy(checks = resolved)
        } finally {
            deleteTree(tmp)
        }
    }
}

internal fun names(repo: Path): Set<String> =
    Files.walk(repo).use { walk ->
        walk
            .map { entry -> repo.relativize(entry).joinToString("/") { it.toString() } }
            .filter { it.isNotEmpty() && it != ".git" && !it.startsWith(".git/") }
            .toList()
            .toSortedSet()
    }

internal fun prefixes(paths: List<String>): Set<String> =
    paths
        .flatMap { path ->
            val parts = path.split("/")
            parts.indices.map { parts.take(it + 1).joinToString("/") }
        }.toSortedSet()

internal fun removeNewEntries(
    repo: Path,
    keep: Set<String>,
) {
    val extra = names(repo) - keep
    for (name in extra.sortedDescending()) Files.deleteIfExists(repo.resolve(name))
}

internal fun runGit(
    cmd: List<String>,
    dir: Path?,
    env: Map<String, String>,
    stdin: String?,
    allowOne: Boolean = false,
): String {
    val pb = ProcessBuilder(cmd)
    if (dir != null) pb.directory(dir.toFile())
    val pbEnv = pb.environment()
    val path = pbEnv["PATH"] ?: pbEnv["Path"] ?: ""
    val systemRoot = pbEnv["SystemRoot"]
    pbEnv.clear()
    pbEnv["PATH"] = path
    if (systemRoot != null) pbEnv["SystemRoot"] = systemRoot
    pbEnv["LC_ALL"] = "C"
    pbEnv.putAll(env)
    val err = Files.createTempFile("kignore-git-stderr", ".txt")
    try {
        pb.redirectError(err.toFile())
        val process = pb.start()
        process.outputStream.use { os -> if (stdin != null) os.write(stdin.toByteArray(Charsets.UTF_8)) }
        val out = String(process.inputStream.readAllBytes(), Charsets.UTF_8)
        val code = process.waitFor()
        // check-ignore exits 1 when nothing matched, which is a valid answer.
        check(code == 0 || (allowOne && code == 1)) {
            "${cmd.joinToString(" ")} failed with $code: ${err.readText()}"
        }
        return out
    } finally {
        Files.deleteIfExists(err)
    }
}

internal fun deleteTree(root: Path) {
    Files.walk(root).use { walk ->
        for (p in walk.sorted(Comparator.reverseOrder()).toList()) {
            p.toFile().setWritable(true)
            Files.deleteIfExists(p)
        }
    }
}
