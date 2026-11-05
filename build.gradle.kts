// Build for the sportsbook PAM ledger.
// Deliberately dependency-free at runtime: every cryptographic primitive used
// (SHA-256, Ed25519, CRC32C) ships with JDK 27, so the trusted computing base
// is just the JDK plus the Kotlin standard library.
plugins {
    kotlin("jvm") version "2.4.10"
    application
}

repositories { mavenCentral() }

dependencies {
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(27)
    // Kotlin 2.4 emits at most JVM 26 bytecode; both languages must agree while running on JDK 27.
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_26) }
}

tasks.withType<JavaCompile>().configureEach { options.release.set(26) }

application { mainClass.set("com.pam.ledger.demo.DemoKt") }

tasks.processResources {
    from("docs/screenshots") { into("web/screenshots") }
}

// The ledger web console: ./gradlew web  (Windows: .\gradlew.bat web)
// Options go after --args, e.g. ./gradlew web --args="--port 9090 --data build/web-ledger"
tasks.register<JavaExec>("web") {
    group = "application"
    description = "Runs the ledger web console on http://localhost:8080"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.pam.ledger.web.WebAppKt")
}

tasks.test {
    useJUnitPlatform {
        if (!providers.gradleProperty("includeSoak").isPresent) excludeTags("soak")
    }
    // Keep concurrent HTTP requests honest on hosts with only two virtual-thread carriers.
    systemProperty("jdk.virtualThreadScheduler.parallelism", "2")
    // The regulatory storm retains every observed balance as independent evidence.
    maxHeapSize = "2g"
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
