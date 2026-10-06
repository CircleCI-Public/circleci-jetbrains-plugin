import org.gradle.process.CommandLineArgumentProvider
import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import java.util.Base64

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    // Compiles the resource usage charts' @Composable functions.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
    id("org.jetbrains.kotlinx.kover") version "0.9.11"
    // Reads this version's notes from CHANGELOG.md, for the Marketplace's change notes.
    id("org.jetbrains.changelog") version "2.5.0"

    // Static Analysis Tools
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    id("org.owasp.dependencycheck") version "13.0.0"
}

group = "com.circleci"
version = "1.10.0"

repositories {
    mavenCentral()

    // The expression language contexts are restricted with, which CircleCI publishes on Clojars.
    maven("https://repo.clojars.org") {
        content { includeGroup("com.circleci") }
    }

    // IntelliJ Platform repositories (includes marketplace and dependencies)
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    constraints {
        add("implementation", "com.fasterxml.jackson:jackson-bom:2.16.1")

        // org.owasp.dependencycheck needs these versions. Other plugins pull in older versions..
        add("implementation", "org.apache.commons:commons-lang3:3.14.0")
        add("implementation", "org.apache.commons:commons-text:1.11.0")
    }
    // JSON Parsing
    implementation("com.google.code.gson:gson:2.10.1")

    // Charts. Only the jar: the IDE provides Compose (see composeUI() below), and
    // KoalaPlot's material3 dependency is only used by chart types we don't draw.
    implementation("io.github.koalaplot:koalaplot-core-desktop:0.12.1") { isTransitive = false }

    // Checks a context's expression restrictions as they're typed.
    implementation("com.circleci:expr:1.0.26-21f79fc")

    // Note: Kotlin stdlib and coroutines are provided by IntelliJ Platform

    // Testing - IntelliJ Platform tests use JUnit 4
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0") // Required by BasePlatformTestCase
    testImplementation("org.mockito:mockito-core:5.8.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")

    // UI Testing - Remote Robot for E2E tests
    testImplementation("com.intellij.remoterobot:remote-robot:0.11.23")
    testImplementation("com.intellij.remoterobot:remote-fixtures:0.11.23")

    // Static Analysis
    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.23.8")

    // IntelliJ Platform Dependencies (replaces intellij {} block)
    intellijPlatform {
        // Unified IntelliJ IDEA distribution. The native LSP API (com.intellij.modules.lsp)
        // isn't in open-source builds, so the plugin can't target them.
        intellijIdea("2026.2") {
            // Use the multi-platform archive from the IntelliJ Maven repository instead of
            // the OS installer (avoids mounting a .dmg on macOS, and is what CI downloads too).
            useInstaller = false
        }
        // The platform's VCS repository API, for the current branch of each project's repository.
        bundledModule("intellij.platform.vcs.dvcs")
        bundledModule("intellij.platform.vcs.dvcs.impl")
        // The review-list building blocks the Pull Requests tool is made of: filter drop-downs, avatars.
        bundledModule("intellij.platform.collaborationTools")
        // The OAuth login flow (the service base, callback handler and PKCE) the GitHub plugin uses.
        bundledModule("intellij.platform.collaborationTools.auth")
        bundledModule("intellij.platform.collaborationTools.auth.base")
        // ProgressStripe, the thin loading bar along the top of a list.
        bundledModule("intellij.platform.vcs.impl")
        // FilterComponent, the drop-down the filters are drawn with.
        bundledModule("intellij.platform.vcs.log.impl")
        // Optional at runtime (see git-support.xml); compiled against for its repository-change events.
        bundledPlugin("Git4Idea")
        // Optional at runtime (see ssh-support.xml): the IDE's SSH client and Terminal, for
        // SSH sessions into jobs. JediTerm's connector types come through the Terminal's API.
        bundledPlugin("intellij.ssh.plugin")
        bundledPlugin("org.jetbrains.plugins.terminal")
        bundledModule("intellij.libraries.jediterm.core")
        // Optional at runtime (see github-support.xml): the GitHub account the language server's
        // GitHub token comes from.
        bundledPlugin("org.jetbrains.plugins.github")
        // Compose and Jewel, which the resource usage charts are drawn with.
        composeUI()
        // The archive doesn't bundle a JetBrains Runtime; runIde and tests need one.
        jetbrainsRuntime()

        // Test framework (required - no longer automatic)
        testFramework(TestFrameworkType.Platform)
    }
}

// Compile with Java 25, which 2026.2 runs on. The bytecode and JDK APIs stay at Java 21, which
// the oldest supported IDE (2026.1) runs on.
kotlin {
    jvmToolchain(25)
}

tasks {
    withType<JavaCompile> {
        options.release = 21
    }

    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
            // Inherit the platform interfaces' default methods rather than generate overrides calling them,
            // which the verifier reports as uses of the deprecated ones (ToolWindowFactory.isApplicable, say).
            jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY)
            // Don't use JDK APIs newer than Java 21, which 2026.1 runs on.
            freeCompilerArgs.add("-Xjdk-release=21")
            // Don't use stdlib APIs newer than the Kotlin bundled with the oldest supported IDE (2026.1).
            apiVersion.set(KotlinVersion.KOTLIN_2_3)
        }
    }

    processResources {
        // Gives the plugin its own version, to read at runtime.
        val version = project.version.toString()
        inputs.property("version", version)
        filesMatching("com/circleci/idea/plugin.properties") {
            expand("version" to version)
        }
    }

    test {
        // Exclude E2E tests that require running IDE with robot-server
        // Run these separately with: task ui:test
        exclude("**/*E2ETest.class")
        // Force IntelliJ to use the standard class loader so Kover's agent can
        // instrument plugin classes (otherwise PathClassLoader bypasses it).
        systemProperty("idea.force.use.core.classloader", "true")
    }
}

// ===========================
// IntelliJ Platform Configuration
// ===========================

// IntelliJ Platform Configuration (replaces patchPluginXml, signPlugin, publishPlugin)
intellijPlatform {
    // Instrumentation only handles UI Designer .form files and Java @NotNull assertions,
    // neither of which this Kotlin-only plugin has.
    instrumentCode = false

    pluginConfiguration {
        name = "CircleCI"
        version = project.version.toString()
        changeNotes =
            providers.provider {
                with(changelog) {
                    getOrNull(project.version.toString())
                        ?.let { renderItem(it.withHeader(false).withEmptySections(false), Changelog.OutputType.HTML) }
                        .orEmpty()
                }
            }

        ideaVersion {
            sinceBuild = "261"
            untilBuild = "262.*"
        }
    }

    signing {
        certificateChain.set(
            providers.environmentVariable("CERTIFICATE_CHAIN")
                .map { String(Base64.getDecoder().decode(it)) },
        )
        privateKey.set(
            providers.environmentVariable("PRIVATE_KEY")
                .map { String(Base64.getDecoder().decode(it)) },
        )
        password.set(providers.environmentVariable("PRIVATE_KEY_PASSWORD"))
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

// UI Testing Configuration (replaces runIdeForUiTests task)
intellijPlatformTesting {
    runIde {
        register("runIdeForUiTests") {
            task {
                jvmArgumentProviders +=
                    CommandLineArgumentProvider {
                        listOf(
                            "-Drobot-server.port=8082",
                            "-Dide.mac.message.dialogs.as.sheets=false",
                            "-Djb.privacy.policy.text=<!--999.999-->",
                            "-Djb.consents.confirmation.enabled=false",
                        )
                    }
            }

            plugins {
                robotServerPlugin()
            }
        }
    }
}

// ===========================
// Static Analysis Configuration
// ===========================

// Detekt - Kotlin Static Analysis
detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom("$projectDir/config/detekt/detekt.yml")
    baseline = file("$projectDir/config/detekt/baseline.xml")
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = "21"
    reports {
        html.required.set(true)
        xml.required.set(true)
        txt.required.set(false)
        sarif.required.set(true)
    }
}

// ktlint - Kotlin Code Style
ktlint {
    version.set("1.1.1")
    android.set(false)
    ignoreFailures.set(false)
    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.HTML)
    }
    filter {
        exclude("**/generated/**")
        include("**/kotlin/**")
    }
}

// OWASP Dependency-Check - Security Vulnerability Scanning
dependencyCheck {
    // Only what ships in the plugin zip. The other configurations hold the IDE distributions
    // (compile target and verifier IDEs, many GB) and build tooling, which aren't ours to patch.
    scanConfigurations = listOf("runtimeClasspath")
    failBuildOnCVSS = 7.0f
    nvd.apiKey = providers.environmentVariable("NVD_API_KEY").orNull
    formats = listOf("HTML", "JSON")
    suppressionFile = "$projectDir/config/owasp-suppressions.xml"
    analyzers {
        ossIndex {
            enabled = false
        }
    }
}

// Aggregate task to run all static analysis
tasks.register("staticAnalysis") {
    group = "verification"
    description = "Run all static analysis tools (detekt, ktlint, dependency-check)"

    dependsOn(
        "detekt",
        "ktlintCheck",
        "dependencyCheckAnalyze",
    )
}

// ===========================
// Kover Coverage Configuration
// ===========================
//
// Kover (JetBrains' Kotlin coverage tool) is used instead of JaCoCo because it works
// correctly with the IntelliJ Platform test harness. The key enabler is:
//   systemProperty("idea.force.use.core.classloader", "true")
// on the `test` task — this tells IntelliJ's test infrastructure to use the standard
// JVM class loader rather than PathClassLoader, so Kover's agent can instrument
// plugin classes loaded by BasePlatformTestCase tests.

kover {
    reports {
        total {
            xml {
                onCheck = false
            }
            html {
                onCheck = false
            }
        }
        verify {
            // Global threshold across all measured classes.
            // Kover uses offline bytecode instrumentation so it captures coverage from
            // BasePlatformTestCase (platform-harness) tests, unlike JaCoCo which was
            // blocked by PathClassLoader. Observed baseline: ~12% (state 52%, logging 79%,
            // project.models 100%, api.models 30%; toolwindow/actions/job are 0% — those
            // require E2E tests driving a running IDE).
            // Per-package rules require Kover variants; this global floor guards against
            // major regressions in the meantime.
            rule {
                minBound(10)
            }
        }
    }
}
