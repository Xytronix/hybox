import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

repositories {
    mavenCentral()
}

gradlePlugin {
    plugins {
        create("runHytalePlugin") {
            id = "io.github.xytronix.hybox.run-hytale"
            implementationClass = "io.github.xytronix.hybox.gradle.RunHytalePlugin"
        }
    }
}

