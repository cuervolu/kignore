import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.maven.publish)
}

kotlin {
    explicitApi()

    jvm {
        compilerOptions { jvmTarget = JvmTarget.JVM_17 }
        // Development tool that asks Git for expected results. It is not part of the published library.
        val main = compilations.getByName("main")
        compilations.create("conformanceTool") { associateWith(main) }
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
