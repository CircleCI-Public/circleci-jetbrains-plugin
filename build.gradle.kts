plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.21"
    id("org.jetbrains.intellij") version "1.17.0"
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
}

intellij {
    version.set("2024.3")
    type.set("IU") // IntelliJ IDEA Ultimate Edition

    // LSP4IJ for Language Server Protocol support
    // Downloaded from JetBrains Marketplace
    plugins.set(listOf(
        "com.redhat.devtools.lsp4ij:0.9.0"
    ))

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
