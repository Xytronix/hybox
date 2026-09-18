import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.the
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.api.tasks.testing.Test

plugins {
    java
    id("sh.harold.hytale.run")
}

val hytaleServerVersion = providers
    .gradleProperty("hytaleServerVersion")
    .orElse("0.6.7")

repositories {
    mavenCentral()
    maven("https://maven.hytale.com/release")
    maven("https://maven.hytale.com/pre-release")
}

dependencies {
    compileOnly("com.hypixel.hytale:Server:${hytaleServerVersion.get()}")
    implementation(project(":hybox-core"))
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.hypixel.hytale:Server:${hytaleServerVersion.get()}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<ProcessResources>("processResources") {
    inputs.property("pluginVersion", rootProject.version.toString())
    inputs.property("hytaleVersion", hytaleServerVersion)
    filesMatching("manifest.json") {
        expand(mapOf("version" to rootProject.version.toString(), "hytaleVersion" to hytaleServerVersion.get()))
    }
}

tasks.named<Test>("test") {
    jvmArgs("-Djava.util.logging.manager=com.hypixel.hytale.logger.backend.HytaleLogManager")
}

tasks.named<Jar>("jar") {
    archiveBaseName.set("Hybox")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(project(":hybox-core").the<JavaPluginExtension>().sourceSets.getByName("main").output)
}
