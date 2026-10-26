import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.kotlin.allopen)
    alias(libs.plugins.kotlinx.benchmark)
}

kotlin {
    explicitApi()

    jvm {
        compilerOptions { jvmTarget = JvmTarget.JVM_17 }
        // Development tool that asks Git for expected results. It is not part of the published library.
        val main = compilations.getByName("main")
        compilations.create("conformanceTool") { associateWith(main) }
        // Measures the library. It is not part of the published artifact.
        compilations.create("benchmark") { associateWith(main) }
    }
    js { browser() }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs { browser() }
    macosArm64()
    linuxX64()
    mingwX64()

    sourceSets {
        commonMain.dependencies {
        }

        getByName("jvmBenchmark").dependencies {
            implementation(libs.kotlinx.benchmark.runtime)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

val conformanceTool = kotlin.jvm().compilations.getByName("conformanceTool")

fun registerConformanceTask(
    name: String,
    description: String,
    mainClassName: String = "dev.cuervolu.kignore.conformance.tool.GenerateFixturesKt",
    toolArgs: List<String> = emptyList(),
) = tasks.register<JavaExec>(name) {
    group = "conformance"
    this.description = description
    classpath(conformanceTool.output.allOutputs, conformanceTool.runtimeDependencyFiles)
    mainClass = mainClassName
    workingDir = rootProject.projectDir
    args(toolArgs)
}

// JMH needs the benchmark classes to be open.
allOpen {
    annotation("org.openjdk.jmh.annotations.State")
}

benchmark {
    targets {
        register("jvmBenchmark")
    }
    configurations {
        // The default profile, run with `benchmark`, is the one to quote results from.
        named("main") {
            warmups = 5
            iterations = 10
            iterationTime = 1
            iterationTimeUnit = "s"
            advanced("jvmForks", 3)
        }
        // Compares the two ways of walking a tree with a scope, which differ in how often a directory is evaluated.
        register("scopeWalk") {
            include("TreeBenchmark.incrementalWalk")
            param("preset", "10k-20r-d8-x0", "10k-20r-d8-x50", "2k-20r-d30-x0")
            warmups = 5
            iterations = 10
            iterationTime = 1
            iterationTimeUnit = "s"
            advanced("jvmForks", 3)
        }
        // A quick run that only shows the benchmarks still work.
        register("smoke") {
            warmups = 1
            iterations = 2
            iterationTime = 300
            iterationTimeUnit = "ms"
            advanced("jvmForks", 1)
        }
    }
}

val benchmarkCompilation = kotlin.jvm().compilations.getByName("benchmark")

tasks.register<JavaExec>("measureAllocations") {
    group = "benchmark"
    description = "Prints the bytes allocated per operation, which does not vary from run to run like time does."
    classpath(benchmarkCompilation.output.allOutputs, benchmarkCompilation.runtimeDependencyFiles)
    mainClass = "dev.cuervolu.kignore.benchmark.AllocationReportKt"
}

registerConformanceTask(
    "generateConformanceFixtures",
    "Regenerates conformance fixtures using the local Git as the oracle.",
)
registerConformanceTask(
    "checkConformanceFixtures",
    "Fails if the committed conformance fixtures differ from what the local Git produces.",
    toolArgs = listOf("--check"),
)
registerConformanceTask(
    "verifyBatchOracle",
    "Fails if asking Git about all paths at once differs from asking about one path at a time.",
    toolArgs = listOf("--verify-batch"),
)
registerConformanceTask(
    "fuzzAgainstGit",
    "Compares kignore with Git on generated scenarios. Pass options with --args, for example '--seed 1 --count 500'.",
    mainClassName = "dev.cuervolu.kignore.conformance.tool.FuzzAgainstGitKt",
)

// Publishing your Kotlin Multiplatform library to Maven Central
// https://www.jetbrains.com/help/kotlin-multiplatform-dev/multiplatform-publish-libraries.html
mavenPublishing {
    publishToMavenCentral()
    coordinates("dev.cuervolu", "kignore", "1.0.0")

    pom {
        name = "kignore"
        description = "Kotlin Multiplatform library"
        url = "github url" // todo

        licenses {
            license {
                name = "MIT"
                url = "https://opensource.org/licenses/MIT"
            }
        }

        developers {
            developer {
                id = "" // todo github nickname
                name = "" // todo full name
                email = "" // todo email
            }
        }

        scm {
            url = "github url" // todo
        }
    }
    if (project.hasProperty("signing.keyId")) signAllPublications()
}
