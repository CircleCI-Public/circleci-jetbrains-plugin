plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.21"
    id("org.jetbrains.intellij") version "1.17.0"

    // Static Analysis Tools
    id("io.gitlab.arturbosch.detekt") version "1.23.4"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.0"
    id("org.owasp.dependencycheck") version "9.0.9"
}

group = "com.circleci"
version = "1.1.1"

repositories {
    mavenCentral()
}

dependencies {
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

    // Static Analysis
    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.23.4")
}

intellij {
    version.set("2024.3")
    type.set("IU") // IntelliJ IDEA Ultimate Edition

    // LSP4IJ for Language Server Protocol support
    // Downloaded from JetBrains Marketplace
    plugins.set(
        listOf(
            "com.redhat.devtools.lsp4ij:0.9.0",
        ),
    )

    // Add marketplace URL for plugin downloads
    pluginsRepositories {
        marketplace()
    }
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }

    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }

    patchPluginXml {
        sinceBuild.set("232")
        untilBuild.set("253.*")
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }

    test {
        // Exclude platform integration test that requires special IDE environment setup
        // TODO: Fix CircleCIStateStoreTest to work with JUnit 4 or convert to lightweight test
        exclude("**/CircleCIStateStoreTest.class")
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
    formats = listOf("HTML", "JSON")
    suppressionFile = "$projectDir/config/owasp-suppressions.xml"
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
