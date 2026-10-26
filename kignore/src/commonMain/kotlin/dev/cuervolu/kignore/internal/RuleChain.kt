package dev.cuervolu.kignore.internal

import dev.cuervolu.kignore.IgnoreDecision
import dev.cuervolu.kignore.IgnoreRuleSet

/**
 * The rule sets that apply inside a directory, from the deepest one to the root, as a persistent list.
 *
 * A new directory adds one link and shares the rest, so descending never copies anything. [start] is the offset in
 * an encoded path where the part relative to the directory of [rules] begins.
 */
internal class RuleChain private constructor(
    private val rules: IgnoreRuleSet,
    private val start: Int,
    private val parent: RuleChain?,
) {
    companion object {
        /** Adds the rules of the directory whose encoded path is [directoryLength] bytes long, if it has any. */
        fun add(
            chain: RuleChain?,
            rules: IgnoreRuleSet,
            directoryLength: Int,
        ): RuleChain? {
            if (rules.isEmpty) return chain
            // A directory at the root has an empty path and no separator before its entries.
            val start = if (directoryLength == 0) 0 else directoryLength + 1
            return RuleChain(rules, start, chain)
        }

        /**
         * Decision of the rules alone for `path[0, to)`, without looking at the parents of the path. The closest
         * file wins, and inside a file the last line wins.
         */
        fun decide(
            chain: RuleChain?,
            path: ByteArray,
            to: Int,
            isDirectory: Boolean,
            counter: StepCounter?,
        ): IgnoreDecision {
            var link = chain
            while (link != null) {
                val decision = link.rules.decide(path, link.start, to, isDirectory, counter)
                if (decision != IgnoreDecision.NONE) return decision
                link = link.parent
            }
            return IgnoreDecision.NONE
        }
    }
}
