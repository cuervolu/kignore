package dev.cuervolu.kignore.benchmark

/** SplitMix64, so a workload is the same on every machine and Kotlin version. */
internal class Rng(
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
}

internal class Dir(
    val name: String,
    val dirs: MutableMap<String, Dir> = LinkedHashMap(),
    val files: MutableList<String> = ArrayList(),
)

internal class Entry(
    val path: String,
    val isDirectory: Boolean,
)

/**
 * A directory tree and the ignore rules that apply to it, built from a seed.
 *
 * [entries] lists every directory and file for independent queries, and [root] has the same tree for a walk that
 * enters each directory once. The top level has twenty directories. With a share of excluded paths, that share of the
 * top level directories is named `ex0`, `ex1` and so on, and the rules exclude them.
 */
internal class Workload(
    val rulesText: String,
    val root: Dir,
    val entries: List<Entry>,
) {
    companion object {
        private const val TOP_LEVEL = 20
        private val EXTENSIONS = listOf("kt", "java", "log", "tmp", "md", "o", "txt", "c", "h", "json")

        /** Parses a preset such as `10k-500r-d8-x50`: paths, rules, depth and percent of excluded paths. */
        fun preset(name: String): Workload {
            val (paths, rules, depth, excluded) = name.split('-')
            val count = paths.removeSuffix("k").toInt() * if (paths.endsWith("k")) 1000 else 1
            return create(
                count,
                rules.removeSuffix("r").toInt(),
                depth.removePrefix("d").toInt(),
                excluded.removePrefix("x").toInt(),
            )
        }

        fun create(
            paths: Int,
            rules: Int,
            depth: Int,
            excludedPercent: Int,
            seed: Long = 42,
        ): Workload {
            val rng = Rng(seed)
            val excludedDirectories = TOP_LEVEL * excludedPercent / 100
            val root = Dir("")
            val entries = ArrayList<Entry>()
            repeat(paths) { index ->
                val top = rng.below(TOP_LEVEL)
                var current = root
                var path = ""
                for (level in 0 until depth) {
                    val name =
                        if (level ==
                            0
                        ) {
                            (if (top < excludedDirectories) "ex$top" else "top$top")
                        } else {
                            "d${rng.below(10)}"
                        }
                    path = if (path.isEmpty()) name else "$path/$name"
                    current =
                        current.dirs.getOrPut(name) {
                            entries.add(Entry(path, true))
                            Dir(name)
                        }
                }
                val file = "f$index.${EXTENSIONS[rng.below(EXTENSIONS.size)]}"
                current.files.add(file)
                entries.add(Entry("$path/$file", false))
            }
            return Workload(rulesText(rules, excludedDirectories, rng), root, entries)
        }

        /** About a tenth of the files match a rule. The rest of the rules are realistic noise the matcher must rule out. */
        private fun rulesText(
            count: Int,
            excludedDirectories: Int,
            rng: Rng,
        ): String {
            val lines = ArrayList<String>()
            for (i in 0 until excludedDirectories) lines.add("ex$i/")
            lines.addAll(listOf("*.log", "*.tmp", "!keep.log", "build/", "node_modules/", ".idea/", "*.o"))
            var i = 0
            while (lines.size < count) {
                lines.add(
                    when (i % 10) {
                        0, 1, 2 -> "*.ext$i"
                        3 -> "cache$i/"
                        4 -> "/top${rng.below(TOP_LEVEL)}/d${rng.below(10)}/*.gen$i"
                        5 -> "**/generated$i/**"
                        6 -> "!important$i.txt"
                        7 -> "f$i?.[ch]"
                        8 -> "build$i*"
                        else -> "[a-c]*/tmp$i"
                    },
                )
                i++
            }
            return lines.take(maxOf(count, excludedDirectories)).joinToString("\n") + "\n"
        }
    }
}
