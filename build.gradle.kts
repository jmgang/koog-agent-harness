plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("ai.koog:koog-agents:1.3.0")
    implementation("ai.koog:agents-test:1.3.0")
    implementation("io.opentelemetry:opentelemetry-sdk-testing:1.61.0")
    implementation("ch.qos.logback:logback-classic:1.6.5")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass = "harness.MainKt"
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

tasks.test {
    useJUnitPlatform()
}
