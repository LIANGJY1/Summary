plugins {
    id("com.android.application") version "8.8.0"
    kotlin("android") version "2.2.20"
    kotlin("plugin.compose") version "2.2.20"
}

android {
    namespace = "atlas.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "atlas.android"
        // 29+：共享代码用到 java.nio.file / java.time（OpenJDK 子集自 API 26 起可用）
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // 共享桌面端主体源码（唯一事实源，UI 与 core/Indexer 全部复用）。
    // 仅排除 AWT/Swing 桌面专属文件；平台差异统一收敛在 atlas.platform，
    // 每端各有一份同名实现（桌面：app/src/main/kotlin/atlas/platform/，Android：本工程）。
    sourceSets.getByName("main") { /* manifest 等资源仍走默认布局 */ }

    buildTypes {
        release {
            isMinifyEnabled = false // MVP 不混淆，与桌面端同理（sqlite 反射加载）
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = false }
    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
    sourceSets.getByName("main") {
        kotlin.srcDir("../../app/src/main/kotlin")
        kotlin.exclude(
            "atlas/Main.kt",             // 桌面入口与窗口壳（AppRoot/SetupView）
            "atlas/ui/WindowChrome.kt",  // 无边框窗口/标题栏拖拽/窗口按钮
            "atlas/ui/ToolsView.kt",     // adb/飞书打卡/录屏等 PC 侧工具
            "atlas/ui/SettingsView.kt",  // 含 JFileChooser 的桌面设置页（通用件已拆出共享）
            "atlas/platform/**",         // 桌面平台实现（Android 用同名实现替换）
        )
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")

    // Android 自带 SQLite 的 JDBC 驱动：共享 Indexer/AppStore 的 java.sql 代码原样可用
    implementation("org.sqldroid:sqldroid:1.1.0-rc1")
    // KMP 库：若未发布 androidJvm 变体，显式按 jvm 变体解析（纯 Kotlin，Android 可直接用）
    implementation("io.github.open-spaced-repetition:fsrs:1.0.0") {
        attributes {
            attribute(
                org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.attribute,
                org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.jvm,
            )
        }
    }
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}
