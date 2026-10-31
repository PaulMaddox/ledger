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
    useJUnitPlatform()
    // The regulatory storm retains every observed balance as independent evidence.
    maxHeapSize = "2g"
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
