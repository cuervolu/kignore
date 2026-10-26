plugins {
    alias(libs.plugins.kotlin.multiplatform).apply(false)
    alias(libs.plugins.maven.publish).apply(false)
    alias(libs.plugins.kotlin.allopen).apply(false)
    alias(libs.plugins.kotlinx.benchmark).apply(false)
    alias(libs.plugins.spotless)
}

// The generated conformance sources and the fixture text files are not formatted. Trimming whitespace or
// rewriting line endings there would change the meaning of the cases and break reproducibility.
spotless {
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**/*.kt", "**/.kotlin/**", "**/conformance/ConformanceData.kt")
        ktlint()
        trimTrailingWhitespace()
        endWithNewline()
    }

    kotlinGradle {
        target("**/*.kts")
        targetExclude("**/build/**/*.kts", "**/.kotlin/**")
        ktlint()
        trimTrailingWhitespace()
        endWithNewline()
    }
}
