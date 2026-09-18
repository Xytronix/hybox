import org.gradle.language.jvm.tasks.ProcessResources

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<ProcessResources>("processResources") {
    filesMatching("io/github/xytronix/hybox/core/version.txt") {
        expand(mapOf("version" to rootProject.version.toString()))
    }
}
