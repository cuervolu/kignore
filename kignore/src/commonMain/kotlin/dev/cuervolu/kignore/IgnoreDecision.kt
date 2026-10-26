package dev.cuervolu.kignore

/**
 * What the rules say about one path, taking its parent directories into account.
 *
 * The decision is always the effective one. A path below an excluded directory is [IGNORED] even when a negated rule
 * matches it, because Git never looks inside an excluded directory.
 *
 * The set of cases is closed. Under semantic versioning, adding or removing one is a breaking change.
 */
public enum class IgnoreDecision {
    /** A rule excludes the path, either directly or because one of its parent directories is excluded. */
    IGNORED,

    /** A negated rule matched the path itself and no parent directory is excluded. */
    INCLUDED,

    /** No rule matched the path or any of its parent directories. */
    NONE,
    ;

    /** `true` only for [IGNORED]. [INCLUDED] and [NONE] both mean the path is not ignored. */
    public val isIgnored: Boolean get() = this == IGNORED
}
