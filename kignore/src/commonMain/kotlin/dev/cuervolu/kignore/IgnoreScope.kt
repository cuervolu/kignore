package dev.cuervolu.kignore

import dev.cuervolu.kignore.internal.RuleChain
import dev.cuervolu.kignore.internal.StepCounter
import dev.cuervolu.kignore.internal.Utf8

/**
 * One directory of a directory walk, with everything its parents decided already folded in.
 *
 * Start with [root] and call [enter] for each subdirectory you descend into. Every directory is evaluated once, when
 * it is entered, and every entry inside it is then checked against the rules only. That makes a scan of thousands of
 * paths cost the same as the paths themselves, and no ancestor is evaluated again.
 *
 * A scope only accepts single names, never paths. A name that contained a slash would skip directories in between,
 * and the rules of those directories would be missing from the answer. Walk down one level at a time instead.
 *
 * Scopes are immutable and safe to share between threads, so different branches of a tree can be walked in parallel
 * from the same parent. Nothing is cached globally.
 *
 * Unlike [IgnorePattern.matches], which answers `false` for a path it cannot interpret, the functions here throw
 * [IllegalArgumentException] for an invalid name. An exception is safer than a silent `false`, which would let a
 * broken path through as "not ignored".
 */
public class IgnoreScope private constructor(
    private val chain: RuleChain?,
    /** UTF-8 path of this directory from the root. Never leaves this class and is never modified. */
    private val directory: ByteArray,
    /** `true` if this directory is excluded, directly or through one of its parents. */
    public val isIgnored: Boolean,
) {
    /**
     * Decision for the entry [name] of this directory.
     *
     * [name] is one path component: not empty, without `/`, and neither `.` nor `..`. Whether it is a directory is
     * given by [isDirectory]. In an ignored directory the answer is always [IgnoreDecision.IGNORED] and the rules
     * are not consulted, so a negated rule never re-includes anything below an excluded directory.
     */
    public fun decide(
        name: String,
        isDirectory: Boolean = false,
    ): IgnoreDecision = decide(name, isDirectory, null)

    /** Shortcut for `decide(name, isDirectory).isIgnored`. */
    public fun ignores(
        name: String,
        isDirectory: Boolean = false,
    ): Boolean = decide(name, isDirectory).isIgnored

    /**
     * The scope of the subdirectory [name] of this directory.
     *
     * [rules] come from the ignore file inside that subdirectory, if it has one, and apply to what it contains, never
     * to the subdirectory itself. When this directory is already ignored, the result is ignored too and [rules] are
     * dropped, because Git does not read ignore files inside an excluded directory.
     *
     * The subdirectory is evaluated here, once. To skip an excluded directory without reading its ignore file, check
     * [ignores] with `isDirectory = true` first and enter only when it is `false`. When the directory has no ignore
     * file there is nothing to avoid reading, so `enter(name)` followed by [isIgnored] does the work with a single
     * evaluation.
     */
    public fun enter(
        name: String,
        rules: IgnoreRuleSet = IgnoreRuleSet.EMPTY,
    ): IgnoreScope = enter(name, rules, null)

    internal fun decide(
        name: String,
        isDirectory: Boolean,
        counter: StepCounter?,
    ): IgnoreDecision {
        requireComponent(name)
        if (isIgnored) return IgnoreDecision.IGNORED
        val path = encodeEntry(name, counter)
        return RuleChain.decide(chain, path, path.size, isDirectory, counter)
    }

    internal fun enter(
        name: String,
        rules: IgnoreRuleSet,
        counter: StepCounter?,
    ): IgnoreScope {
        requireComponent(name)
        if (isIgnored) return IGNORED_SCOPE
        val path = encodeEntry(name, counter)
        val ignored = RuleChain.decide(chain, path, path.size, true, counter) == IgnoreDecision.IGNORED
        if (ignored) return IGNORED_SCOPE
        return IgnoreScope(RuleChain.add(chain, rules, path.size), path, false)
    }

    private fun encodeEntry(
        name: String,
        counter: StepCounter?,
    ): ByteArray {
        counter?.let { it.encodings++ }
        val separator = if (directory.isEmpty()) 0 else 1
        val path = ByteArray(directory.size + separator + Utf8.encodedLength(name))
        directory.copyInto(path)
        if (separator == 1) path[directory.size] = SLASH
        Utf8.encodeInto(name, path, directory.size + separator)
        return path
    }

    override fun toString(): String = if (isIgnored) "IgnoreScope(ignored)" else "IgnoreScope"

    public companion object {
        private const val SLASH = '/'.code.toByte()

        /** Nothing below an ignored directory is evaluated, so it needs no path and no rules. */
        private val IGNORED_SCOPE = IgnoreScope(null, ByteArray(0), true)

        /** The scope of the root directory. [rules] come from the ignore file in the root, if there is one. */
        public fun root(rules: IgnoreRuleSet = IgnoreRuleSet.EMPTY): IgnoreScope =
            IgnoreScope(RuleChain.add(null, rules, 0), ByteArray(0), false)

        private fun requireComponent(name: String) {
            require(name.isNotEmpty() && name != "." && name != ".." && '/' !in name) {
                "A scope takes one path component, not \"$name\". Use enter() for each directory on the way."
            }
        }
    }
}
