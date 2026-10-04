// Atlas Android 端（独立 Gradle 工程；共享桌面端源码见 app/build.gradle.kts 的 srcDir）
pluginManagement {
    repositories {
        // 本机外网代理不可用，全部走阿里云直连镜像（google 仓库亦有镜像）
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.PREFER_SETTINGS
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}
rootProject.name = "atlas-android"
include(":app")
