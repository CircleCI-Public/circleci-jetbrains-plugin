import org.gradle.process.CommandLineArgumentProvider
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.21"
    id("org.jetbrains.intellij.platform") version "2.11.0"
    id("org.jetbrains.kotlinx.kover") version "0.9.4"

    // Static Analysis Tools
    id("io.gitlab.arturbosch.detekt") version "1.23.4"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.0"
    id("org.owasp.dependencycheck") version "12.1.0"
}

group = "com.circleci"
version = "1.3.1"

repositories {
    mavenCentral()

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
    // HTTP Client
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // JSON Parsing
    implementation("com.google.code.gson:gson:2.10.1")

    // WebSocket
    implementation("com.pusher:pusher-java-client:2.4.4")

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
    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.23.4")

    // IntelliJ Platform Dependencies (replaces intellij {} block)
    intellijPlatform {
        // Platform version and type (was: version.set("2024.3"), type.set("IC"))
        create("IC", "2024.3")

        // Marketplace plugins - LSP4IJ for Language Server Protocol support
        plugin("com.redhat.devtools.lsp4ij", "0.19.1")

        // Test framework (required - no longer automatic)
        testFramework(TestFrameworkType.Platform)
    }
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }

    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "21"
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
    pluginConfiguration {
        name = "CircleCI"
        version = "1.3.1"

        ideaVersion {
            sinceBuild = "243"
            untilBuild = "253.*"
        }
    }

    signing {
        certificateChain.set(providers.environmentVariable("CERTIFICATE_CHAIN")
            .map { String(java.util.Base64.getDecoder().decode(it)) })
        privateKey.set(providers.environmentVariable("PRIVATE_KEY")
            .map { String(java.util.Base64.getDecoder().decode(it)) })
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
    failBuildOnCVSS = 7.0f
    nvd.apiKey = providers.environmentVariable("NVD_API_KEY").orNull
    formats = listOf("HTML", "JSON")
    suppressionFile = "$projectDir/config/owasp-suppressions.xml"
    analyzers {
        ossIndexEnabled = false
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
