package dev.cuervolu.kignore

import dev.cuervolu.kignore.internal.PathBytes
import dev.cuervolu.kignore.internal.RuleChain
import dev.cuervolu.kignore.internal.StepCounter
import dev.cuervolu.kignore.internal.Utf8

/**
 * Answers whether a path is ignored, given the rule sets of every directory that has an ignore file.
 *
 * A matcher works only with the [IgnoreRuleSet]s it was built with. It never looks for ignore files, never reads
 * anything and never touches the file system. A directory that is not in the map has no rules, so leaving out the
 * ignore file of an intermediate directory gives answers that do not include its rules. To discover and load ignore
 * files while walking a tree, use [IgnoreScope].
 *
 * Each question evaluates every parent directory of the path, from the top, and stops at the first one that is
 * excluded. That is the right tool for isolated checks, such as a file watcher event. For scanning many paths of one
 * tree prefer [IgnoreScope], which evaluates each directory once instead of once per file.
 *
 * Paths are relative to the root, use `/` as the only separator and must be normalized. A path that is empty,
 * absolute, has `.`, `..` or empty segments, or ends in `/` makes the functions throw [IllegalArgumentException].
 * [IgnorePattern.matches] is more lenient and answers `false` for the same input. A matcher throws because answering
 * "not ignored" for a broken path would silently let it through. Whether the path is a directory is given by
 * `isDirectory` and never by a trailing slash.
 *
 * Matchers are immutable and safe to share between threads.
 */
public class IgnoreMatcher private constructor(
    private val root: Node,
) {
    /**
     * Decision for [path], taking its parent directories into account.
     *
     * A parent directory that is excluded makes the answer [IgnoreDecision.IGNORED], even if a negated rule matches
     * the path itself, because Git does not look inside an excluded directory.
     */
    public fun decide(
        path: String,
        isDirectory: Boolean = false,
    ): IgnoreDecision = decide(path, isDirectory, null)

    /** Shortcut for `decide(path, isDirectory).isIgnored`. */
    public fun ignores(
        path: String,
        isDirectory: Boolean = false,
    ): Boolean = decide(path, isDirectory).isIgnored

    internal fun decide(
        path: String,
        isDirectory: Boolean,
        counter: StepCounter?,
    ): IgnoreDecision {
        val bytes = Utf8.encode(path)
        counter?.let { it.encodings++ }
        require(PathBytes.isNormalized(bytes, 0, bytes.size)) { "Not a normalized relative path: \"$path\"" }
        var node: Node? = root
        var chain = RuleChain.add(null, root.rules, 0)
        // `slash` walks the string and `end` the bytes. They stay in step because `/` is one unit in both.
        var slash = path.indexOf('/')
        var end = 0
        while (slash >= 0) {
            while (bytes[end] != SLASH) end++
            val ancestor = RuleChain.decide(chain, bytes, end, true, counter)
            if (ancestor == IgnoreDecision.IGNORED) return IgnoreDecision.IGNORED
            val child = node?.children?.get(path.substring(segmentStart(path, slash), slash))
            node = child
            if (child != null) chain = RuleChain.add(chain, child.rules, end)
            slash = path.indexOf('/', slash + 1)
            end++
        }
        return RuleChain.decide(chain, bytes, bytes.size, isDirectory, counter)
    }

    private fun segmentStart(
        path: String,
        slash: Int,
    ): Int = path.lastIndexOf('/', slash - 1) + 1

    private class Node(
        var rules: IgnoreRuleSet,
    ) {
        val children = HashMap<String, Node>()
    }

    public companion object {
        private const val SLASH = '/'.code.toByte()

        /** A matcher with the rules of the root directory only. */
        public fun of(rules: IgnoreRuleSet): IgnoreMatcher = of(mapOf("" to rules))

        /**
         * A matcher over the rule sets of several directories.
         *
         * Each key is the path of a directory, relative to the root, in the same normalized form as the paths that
         * are checked: no leading or trailing slash, and the empty string for the root. A key in any other form
         * throws [IllegalArgumentException]. Keys are compared exactly, also for rule sets parsed with `ignoreCase`,
         * because the directories are named by the path of the question and not by a pattern. The rules of `sub`
         * apply to `sub/a.txt` and not to `SUB/a.txt`.
         */
        public fun of(rulesByDirectory: Map<String, IgnoreRuleSet>): IgnoreMatcher {
            val root = Node(IgnoreRuleSet.EMPTY)
            for ((directory, rules) in rulesByDirectory) {
                if (directory.isEmpty()) {
                    root.rules = rules
                    continue
                }
                require(PathBytes.isNormalized(Utf8.encode(directory), 0, Utf8.encodedLength(directory))) {
                    "Not a normalized directory path: \"$directory\""
                }
                var node = root
                for (segment in directory.split('/')) {
                    node =
                        node.children.getOrPut(segment) { Node(IgnoreRuleSet.EMPTY) }
                }
                node.rules = rules
            }
            return IgnoreMatcher(root)
        }
    }
}
