package dev.cuervolu.kignore.conformance

/** Reads the text fixtures written by the generator. See conformance/README.md for the format. */
internal object FixtureParser {
    fun parse(
        name: String,
        text: String,
    ): FixtureFile {
        var gitVersion: String? = null
        var command: String? = null
        var defaults: Map<String, String> = emptyMap()
        val scenarios = ArrayList<Scenario>()
        val builder = ScenarioBuilder(name)

        fun flush() {
            builder.build()?.let(scenarios::add)
            builder.reset()
        }

        text.split("\n").forEachIndexed { index, line ->
            val where = "$name:${index + 1}"
            if (line.startsWith("|")) {
                builder.addContentLine(line, where)
                return@forEachIndexed
            }
            builder.closeFile()
            if (line.startsWith("# git: ")) gitVersion = line.removePrefix("# git: ")
            if (line.startsWith("# command: ")) command = line.removePrefix("# command: ")
            if (line.startsWith("# defaults:")) defaults = parseDefaults(line.removePrefix("# defaults:"))
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val key = line.substringBefore(' ')
            val rest = line.substringAfter(' ', "")
            if (key == "scenario") flush()
            builder.accept(key, rest, where)
        }
        flush()
        return FixtureFile(
            name = name,
            gitVersion = requireNotNull(gitVersion) { "$name: missing '# git:' header" },
            command = requireNotNull(command) { "$name: missing '# command:' header" },
            defaults = defaults,
            scenarios = scenarios,
        )
    }

    private fun parseDefaults(text: String): Map<String, String> =
        text
            .trim()
            .split(
                ' ',
            ).filter { it.isNotEmpty() }
            .associate { it.substringBefore('=') to it.substringAfter('=') }

    private class ScenarioBuilder(
        private val source: String,
    ) {
        private var id: String? = null
        private var kind: ScenarioKind? = null
        private var spec: String? = null
        private var note: String? = null
        private var config = LinkedHashMap<String, String>()
        private var files = ArrayList<IgnoreFile>()
        private var cases = ArrayList<ExpectedCase>()
        private var filePath: String? = null
        private var noEol = false
        private var fileLines: ArrayList<String>? = null

        fun reset() {
            id = null
            kind = null
            spec = null
            note = null
            config = LinkedHashMap()
            files = ArrayList()
            cases = ArrayList()
            filePath = null
            fileLines = null
        }

        fun accept(
            key: String,
            rest: String,
            where: String,
        ) {
            when (key) {
                "scenario" -> {
                    id = rest
                }

                "kind" -> {
                    kind =
                        ScenarioKind.entries.firstOrNull { it.label == rest } ?: error("$where: bad kind '$rest'")
                }

                "spec" -> {
                    spec = rest
                }

                "note" -> {
                    note = rest
                }

                "config" -> {
                    config[rest.substringBefore('=')] = rest.substringAfter('=')
                }

                "file" -> {
                    val parts = rest.split(' ')
                    filePath = unescape(parts[0])
                    noEol = parts.getOrNull(1) == "noeol"
                    fileLines = ArrayList()
                }

                "path" -> {
                    cases.add(parseCase(rest, where))
                }

                else -> {
                    error("$where: unknown directive '$key'")
                }
            }
        }

        fun addContentLine(
            line: String,
            where: String,
        ) {
            val lines = checkNotNull(fileLines) { "$where: content line outside a file" }
            check(line.length == 1 || line[1] == ' ') { "$where: expected '| '" }
            lines.add(if (line.length > 2) unescape(line.substring(2)) else "")
        }

        fun closeFile() {
            val lines = fileLines ?: return
            val path = checkNotNull(filePath)
            val content = StringBuilder()
            lines.forEachIndexed { i, l ->
                content.append(l)
                if (i < lines.size - 1 || !noEol) content.append('\n')
            }
            files.add(IgnoreFile(path, content.toString()))
            fileLines = null
        }

        fun build(): Scenario? {
            closeFile()
            val currentId = id ?: return null
            return Scenario(
                id = currentId,
                kind = checkNotNull(kind) { "$source: scenario $currentId has no kind" },
                spec = checkNotNull(spec) { "$source: scenario $currentId has no spec" },
                note = note,
                config = config,
                files = files,
                cases = cases,
                source = source,
            )
        }

        private fun parseCase(
            rest: String,
            where: String,
        ): ExpectedCase {
            val arrow = rest.indexOf(" => ")
            check(arrow >= 0) { "$where: missing ' => '" }
            val path = unescape(rest.substring(0, arrow))
            val answer = rest.substring(arrow + 4)
            val resultText = answer.substringBefore(" # ")
            val result =
                ExpectedResult.entries.firstOrNull { it.name == resultText }
                    ?: error("$where: bad result '$resultText'")
            val rule = if (answer.contains(" # ")) unescape(answer.substringAfter(" # ")) else null
            return ExpectedCase(path, result, rule)
        }
    }

    /** Escapes are introduced by a backtick so that gitignore backslashes can be written as they are. */
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
                        appendCodePoint(b, s.substring(i + 2, end).toInt(16))
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

    private fun appendCodePoint(
        b: StringBuilder,
        cp: Int,
    ) {
        if (cp < 0x10000) {
            b.append(cp.toChar())
        } else {
            val offset = cp - 0x10000
            b.append((0xD800 + (offset shr 10)).toChar())
            b.append((0xDC00 + (offset and 0x3FF)).toChar())
        }
    }
}
