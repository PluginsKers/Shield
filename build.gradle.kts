plugins {
    java
    kotlin("jvm") version "2.3.21"
    id("com.gradleup.shadow") version "8.3.5"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // 以 Dialog API 首次公开的 Paper 版本为编译边界。
    compileOnly("io.papermc.paper:paper-api:1.21.7-R0.1-SNAPSHOT")
    // 运行时应安装明确支持目标 Minecraft 版本的 ProtocolLib 构建。
    compileOnly("net.dmulloy2:ProtocolLib:5.4.0")
}

val releasePackages by tasks.registering(Sync::class) {
    into(layout.buildDirectory.dir("distributions"))
    from(tasks.shadowJar)
    listOf("1.21.11", "26.1", "26.1.1", "26.1.2", "26.2", "26.3").forEach { minecraftVersion ->
        from(tasks.shadowJar) {
            rename { name -> "${name.removeSuffix(".jar")}-mc$minecraftVersion.jar" }
        }
    }
}

tasks {
    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    processResources {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") {
            expand(props)
        }
    }

    // shadowJar 取代默认 jar：classifier 置空让产物同名覆盖，禁用 jar 避免 Gradle 9 的隐式依赖冲突。
    jar {
        enabled = false
    }

    shadowJar {
        archiveClassifier.set("")
        into("META-INF") { from("LICENSE", "NOTICE") }
    }

    build {
        dependsOn(releasePackages)
    }
}
