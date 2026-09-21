import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.compose") version "2.2.20"
    id("org.jetbrains.compose") version "1.12.0"
}

repositories {
    maven("https://maven.aliyun.com/repository/public")
    mavenCentral()
    google()
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.9.0") // CMP 1.12 配套版本（访问器已弃用为错误级）
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    implementation("io.github.open-spaced-repetition:fsrs:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation(kotlin("test"))
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.test { useJUnitPlatform() }

compose.desktop.application {
    mainClass = "atlas.MainKt"
    nativeDistributions {
        targetFormats(TargetFormat.Deb)
        packageName = "atlas"
        packageVersion = "1.0.0"
        description = "Atlas — AI 成长工作站（零模型本地知识库）"
        vendor = "atlas"
        linux { shortcut = true }
        // sqlite-jdbc 经反射加载驱动，jdeps 分析不到——必须显式带上 java.sql 模块
        modules("java.sql", "java.sql.rowset", "jdk.unsupported")
    }
    buildTypes {
        release {
            proguard { isEnabled.set(false) } // MVP 不做混淆，避免 ProGuard 反射规则问题
        }
    }
}
