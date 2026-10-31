// Build for the sportsbook PAM ledger.
// Deliberately dependency-free at runtime: every cryptographic primitive used
// (SHA-256, Ed25519, CRC32C) ships with JDK 21, so the trusted computing base
// is just the JDK plus the Kotlin standard library.
plugins {
    kotlin("jvm") version "2.2.20"
    application
}

repositories { mavenCentral() }

dependencies {
    testImplementation(kotlin("test"))
}

kotlin { jvmToolchain(21) }

application { mainClass.set("com.pam.ledger.demo.DemoKt") }

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
