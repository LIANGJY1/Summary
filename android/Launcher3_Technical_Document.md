# Android 14 Launcher3 完整技术文档

> 基于源码 `Android_14.0.0_r9/packages/apps/Launcher3/` 分析

---

## 目录

1. [Launcher 启动流程](#1-launcher-启动流程)
2. [Launcher3 编译体系（Android.bp）](#2-launcher3-编译体系androidbp)
3. [APK 变体详解](#3-apk-变体详解)
4. [Android Go 版本](#4-android-go-版本)
5. [安装路径与分区机制](#5-安装路径与分区机制)
6. [特权应用机制](#6-特权应用机制)
7. [overrides 覆盖机制](#7-overrides-覆盖机制)
8. [产品配置与编译选择](#8-产品配置与编译选择)
9. [AAOS 车载系统 Launcher](#9-aaos-车载系统-launcher)
10. [自定义 Launcher 开发指南](#10-自定义-launcher-开发指南)
11. [关键源码索引](#11-关键源码索引)

---

## 1. Launcher 启动流程

### 1.1 完整调用链

```
SystemServer.main()
  └→ SystemServer.run()
       └→ startOtherServices()
            └→ ActivityManagerService.systemReady()
                 └→ ActivityTaskManagerService.systemReady()
                      └→ RootWindowContainer.startHomeOnAllDisplays()
                           └→ RootWindowContainer.startHomeOnDisplay()
                                └→ RootWindowContainer.startHomeOnTaskDisplayArea()
                                     ├→ ActivityTaskManagerService.getHomeIntent()        // 构建 Intent
                                     ├→ RootWindowContainer.resolveHomeActivity()         // 解析 Activity
                                     └→ ActivityStartController.startHomeActivity()       // 启动 Activity
```

### 1.2 核心方法详解

#### 1.2.1 `startHomeOnTaskDisplayArea()` — 启动 Home 的入口

**源码位置**: `frameworks/base/services/core/java/com/android/server/wm/RootWindowContainer.java`

```java
boolean startHomeOnTaskDisplayArea(int userId, String reason,
        TaskDisplayArea taskDisplayArea, boolean allowInstrumenting,
        boolean fromHomeKey) {
    // 1. 构建HOME Intent
    // getHomeIntent()返回 ACTION_MAIN + CATEGORY_HOME 的Intent
    // resolveHomeActivity()通过PackageManager解析该Intent，找到匹配的Launcher Activity
    if (taskDisplayArea == getDefaultTaskDisplayArea()) {
        homeIntent = mService.getHomeIntent();
        aInfo = resolveHomeActivity(userId, homeIntent);
    } else {
        // 副屏使用 resolveSecondaryHomeActivity()
        aInfo = resolveSecondaryHomeActivity(userId, taskDisplayArea);
        homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_SECONDARY_HOME);
        homeIntent.setComponent(aInfo.getComponentName());
    }

    // 2. 启动Home Activity
    mService.getActivityStartController().startHomeActivity(homeIntent, aInfo,
            myReason, taskDisplayArea);
    return true;
}
```

#### 1.2.2 `getHomeIntent()` — 构建 HOME Intent

**源码位置**: `frameworks/base/services/core/java/com/android/server/wm/ActivityTaskManagerService.java`

```java
Intent getHomeIntent() {
    Intent intent = new Intent(mTopAction, mTopData != null ? Uri.parse(mTopData) : null);
    intent.setComponent(mTopComponent);
    intent.addFlags(Intent.FLAG_DEBUG_TRIAGED_MISSING);
    // ★ 关键：添加 CATEGORY_HOME 类别
    // 所有声明了 ACTION_MAIN + CATEGORY_HOME 的 Activity 都会被匹配
    if (mFactoryTest != FactoryTest.FACTORY_TEST_LOW_LEVEL) {
        intent.addCategory(Intent.CATEGORY_HOME);
    }
    return intent;
}
```

#### 1.2.3 `resolveHomeActivity()` — 解析匹配的 Launcher

**源码位置**: `frameworks/base/services/core/java/com/android/server/wm/RootWindowContainer.java`

```java
ActivityInfo resolveHomeActivity(int userId, Intent homeIntent) {
    // 1. 解析Intent的MIME类型
    homeIntent = homeIntent.resolveTypeIfNeeded(mService.mContext.getContentResolver());

    // 2. 通过PackageManager查询匹配的Activity
    // 内部调用 ResolveIntentHelper.queryIntentActivities()
    //   → ComputerEngine.queryIntentActivities()
    //   → ComponentResolver.queryActivities()
    //   → 找到所有声明了 ACTION_MAIN + CATEGORY_HOME 的 Activity
    ResolveInfo rInfo = mService.getPackageManagerInternal()
            .resolveIntent(homeIntent, resolvedType, ...);

    // 3. 返回优先级最高的Activity信息
    return rInfo != null ? rInfo.activityInfo : null;
}
```

### 1.3 Intent 解析流程

```
resolveHomeActivity()
  └→ homeIntent.resolveTypeIfNeeded()          // 解析MIME类型
  └→ PackageManagerInternal.resolveIntent()
       └→ ResolveIntentHelper.queryIntentActivities()
            └→ ComputerEngine.queryIntentActivities()
                 └→ ComponentResolver.queryActivities()
                      ├→ 查找所有匹配 ACTION_MAIN + CATEGORY_HOME 的 Activity
                      ├→ 按优先级排序（priority）
                      ├→ 检查 PreferredActivity（用户默认选择）
                      └→ 返回最佳匹配
```

### 1.4 启动时序图

```
SystemServer                    ATMS                    PMS                     AMS
    │                            │                       │                       │
    │──systemReady()──────────→  │                       │                       │
    │                            │──getHomeIntent()────→ │                       │
    │                            │   ACTION_MAIN+HOME    │                       │
    │                            │                       │                       │
    │                            │──resolveIntent()────→ │                       │
    │                            │                       │──queryActivities()──→ │
    │                            │                       │  扫描已注册的Activity   │
    │                            │                       │←─QuickstepLauncher────│
    │                            │←──ResolveInfo─────────│                       │
    │                            │                       │                       │
    │                            │──startHomeActivity()──────────────────────→   │
    │                            │                       │                  启动Activity
    │                            │                       │                       │
    │                            │                       │              用户看到桌面
```

---

## 2. Launcher3 编译体系（Android.bp）

### 2.1 Android.bp 是什么

Android.bp 是 Android 14 使用的构建配置文件（替代旧版 Android.mk），使用 Soong 构建系统语法。类似于上层 App 的 `build.gradle`，但用于系统级模块。

### 2.2 模块类型一览

| 模块类型 | 产出 | 类比 | 用途 |
|----------|------|------|------|
| `filegroup` | 无 | sourceSets | 源文件分组，方便复用 |
| `android_library` | .aar | Android Library | 中间库，包含资源和代码 |
| `java_library_static` | .jar | Java Library | 纯Java库，无Android资源 |
| `android_app` | .apk | Android Application | 最终APK产物 |

### 2.3 编译依赖关系图

```
filegroup(源码分组) ──────────────────────────────────────────────┐
  launcher-src          launcher-quickstep-src                     │
  launcher-go-src       launcher-go-quickstep-src                  │
      │                     │                                       │
      ▼                     ▼                                       │
┌─────────────────────────────────────────┐                        │
│  android_library (中间库，不产出APK)      │                        │
│  ├─ Launcher3ResLib       (基础资源库)    │                        │
│  ├─ Launcher3CommonDepsLib(公共依赖库)    │                        │
│  ├─ QuickstepResLib      (手势导航资源库) │                        │
│  ├─ Launcher3QuickStepLib(手势导航代码库) │                        │
│  └─ LauncherGoResLib     (Go版资源库)     │                        │
└─────────────────────────────────────────┘                        │
      │                     │                                       │
      ▼                     ▼                                       │
┌─────────────────────────────────────────┐                        │
│  android_app (最终APK产物)                │                        │
│  ├─ Launcher3           (基础版)         │ ← overrides: Home, Launcher2
│  ├─ Launcher3QuickStep  (完整版★主流)    │ ← overrides: Home, Launcher2, Launcher3
│  ├─ Launcher3Go         (Go基础版)       │ ← overrides: Home, Launcher2, Launcher3, Launcher3QuickStep
│  └─ Launcher3QuickStepGo(Go完整版)       │ ← overrides: Home, Launcher2, Launcher3, Launcher3QuickStep
└─────────────────────────────────────────┘                        │
                                                                   │
java_library_static (Java库，不产出APK) ────────────────────────────┘
  launcher_log_protos_lite, launcher_quickstep_log_protos_lite
  LauncherPluginLib
```

### 2.4 filegroup 源码分组

| filegroup 名称 | 源码路径 | 说明 |
|----------------|----------|------|
| `launcher-src` | `src/**/*.java`, `src/**/*.kt` | 核心源码 |
| `launcher-quickstep-src` | `quickstep/src/**/*.java`, `quickstep/src/**/*.kt` | 手势导航源码 |
| `launcher-go-src` | `go/src/**/*.java`, `go/src/**/*.kt` | Go版专用源码 |
| `launcher-go-quickstep-src` | `go/quickstep/src/**/*.java`, `go/quickstep/src/**/*.kt` | Go+手势导航源码 |
| `launcher-src_shortcuts_overrides` | `src_shortcuts_overrides/**/*` | 快捷方式覆盖 |
| `launcher-src_ui_overrides` | `src_ui_overrides/**/*` | UI覆盖 |
| `launcher-ext_tests` | `ext_tests/**/*` | 扩展测试 |
| `launcher-proguard-rules` | `proguard.flags` | 混淆规则 |

### 2.5 中间库依赖链

```
Launcher3ResLib (基础资源)
    │
    ▼
Launcher3CommonDepsLib (公共依赖 = ResLib + 系统依赖)
    │
    ├──→ Launcher3 (基础版APK)
    │
    ├──→ QuickstepResLib (手势导航资源 = ResLib + QuickStep资源)
    │       │
    │       ▼
    │   Launcher3QuickStepLib (手势导航代码 = CommonDepsLib + QuickStep源码)
    │       │
    │       ▼
    │   Launcher3QuickStep (完整版APK)
    │
    └──→ LauncherGoResLib (Go资源 = CommonDepsLib + Go源码 + QuickstepResLib)
            │
            ├──→ Launcher3Go (Go基础版APK)
            │
            └──→ Launcher3QuickStepGo (Go完整版APK)
```

---

## 3. APK 变体详解

### 3.1 四个 APK 变体对比

| 属性 | Launcher3 | Launcher3QuickStep | Launcher3Go | Launcher3QuickStepGo |
|------|-----------|-------------------|-------------|---------------------|
| **功能** | 基础桌面 | 桌面+手势导航+最近任务 | Go版桌面 | Go版+手势导航 |
| **目标设备** | 老设备 | 主流设备 ★★★ | 低内存设备 | 低内存+手势导航 |
| **主Activity** | Launcher | QuickstepLauncher | Launcher | QuickstepLauncher |
| **代码库** | CommonDepsLib | QuickStepLib | CommonDepsLib | GoResLib |
| **混淆** | 关闭 | 关闭 | 开启 | 开启 |
| **platform_apis** | 否 | 是 | 否 | 是 |
| **overrides** | Home, Launcher2 | Home, Launcher2, Launcher3 | Home, Launcher2, Launcher3, Launcher3QuickStep | Home, Launcher2, Launcher3, Launcher3QuickStep |

### 3.2 Launcher3 — 基础版

```
android_app {
    name: "Launcher3",
    static_libs: ["Launcher3CommonDepsLib"],
    srcs: [":launcher-src", ":launcher-src_shortcuts_overrides",
           ":launcher-src_ui_overrides", ":launcher-ext_tests"],
    optimize: { enabled: false },          // 关闭混淆
    privileged: true,                       // 特权应用
    system_ext_specific: true,              // 安装到system_ext分区
    overrides: ["Home", "Launcher2"],       // 覆盖旧版
}
```

### 3.3 Launcher3QuickStep — 完整版（主流）

```
android_app {
    name: "Launcher3QuickStep",
    static_libs: ["Launcher3QuickStepLib"],
    platform_apis: true,                    // 使用平台API（@hide接口）
    optimize: { enabled: false },
    privileged: true,
    system_ext_specific: true,
    overrides: ["Home", "Launcher2", "Launcher3"],  // 覆盖基础版
    additional_manifests: [
        "quickstep/AndroidManifest-launcher.xml",   // ★ 声明QuickstepLauncher
        "AndroidManifest-common.xml",
    ],
}
```

**Manifest 合并过程**：

| Manifest 文件 | 作用 |
|--------------|------|
| `quickstep/AndroidManifest.xml` | 主Manifest：权限、TouchInteractionService |
| `quickstep/AndroidManifest-launcher.xml` | ★ 声明 QuickstepLauncher Activity（ACTION_MAIN + CATEGORY_HOME） |
| `AndroidManifest-common.xml` | 公共组件：LauncherProvider、NotificationListener、SecondaryDisplayLauncher |

编译时三个 Manifest 合并为一个完整的 `AndroidManifest.xml`。

### 3.4 Launcher3Go — Go基础版

```
android_app {
    name: "Launcher3Go",
    static_libs: ["Launcher3CommonDepsLib"],
    srcs: [":launcher-src", ":launcher-go-src", ":launcher-src_ui_overrides"],
    resource_dirs: ["go/res"],
    optimize: { },                          // 默认开启混淆
    overrides: ["Home", "Launcher2", "Launcher3", "Launcher3QuickStep"],
    manifest: "go/AndroidManifest.xml",
}
```

### 3.5 Launcher3QuickStepGo — Go完整版

```
android_app {
    name: "Launcher3QuickStepGo",
    static_libs: ["LauncherGoResLib"],
    platform_apis: true,
    optimize: { enabled: true },            // 开启混淆
    overrides: ["Home", "Launcher2", "Launcher3", "Launcher3QuickStep"],
    resource_dirs: ["go/quickstep/res", "go/res", "quickstep/res"],  // 资源覆盖
}
```

---

## 4. Android Go 版本

### 4.1 什么是 Android Go

Android Go 是 Google 专为低内存设备（通常 ≤ 1GB RAM）推出的轻量版 Android。Go 版的核心目标：**减少内存占用和 APK 体积，确保在低配设备上流畅运行**。

### 4.2 Go 版与普通版的具体差异

| 差异项 | 普通版 | Go版 | 源码位置 |
|--------|--------|------|----------|
| 网格布局 | 5×5 | 4×4 | `go/res/xml/device_profiles.xml` |
| 通知圆点 | 启用 | 禁用 | `go/AndroidManifest.xml` (NotificationListener enabled=false) |
| Pin快捷方式 | 启用 | 禁用 | `go/AndroidManifest.xml` (AddItemActivity enabled=false) |
| 数据库 | 直接SQLite | Room | `LauncherGoResLib` 引入 `androidx.room` |
| 混淆 | 关闭 | 开启 | `optimize: { enabled: true }` |
| App Sharing | 无 | 有 | `go/quickstep/src/.../AppSharing.java` |
| Niu Actions | 无 | 有 | `go/quickstep/src/.../TaskOverlayFactoryGo.java` |

### 4.3 Go 版 Manifest 覆盖示例

```xml
<!-- go/AndroidManifest.xml -->
<!-- 禁用通知圆点功能 -->
<service
    android:name="com.android.launcher3.notification.NotificationListener"
    android:enabled="false"
    tools:node="replace" />

<!-- 禁用Pin快捷方式功能 -->
<activity android:name="com.android.launcher3.dragndrop.AddItemActivity"
    android:enabled="false"
    tools:node="replace" />
```

`tools:node="replace"` 表示用 Go 版的定义完全替换基础版的同名组件。

---

## 5. 安装路径与分区机制

### 5.1 Android 系统分区

Android 系统有多个分区，每个分区可放置 APK。PMS 按分区优先级扫描：

| 分区 | 路径 | 优先级(低→高) | 含义 | priv-app支持 |
|------|------|--------------|------|-------------|
| SYSTEM | `/system/app/` | 1(最低) | 核心系统应用 | ✅ `/system/priv-app/` |
| VENDOR | `/vendor/app/` | 2 | 芯片厂商应用 | ✅ `/vendor/priv-app/` |
| ODM | `/odm/app/` | 3 | 设备制造商应用 | ✅ `/odm/priv-app/` |
| OEM | `/oem/app/` | 4 | OEM定制应用 | ❌ |
| PRODUCT | `/product/app/` | 5 | 产品定制应用 | ✅ `/product/priv-app/` |
| SYSTEM_EXT | `/system_ext/app/` | 6(最高) | 系统扩展应用 | ✅ `/system_ext/priv-app/` |

> 源码依据：`frameworks/base/core/java/android/content/pm/PackagePartitions.java` 中的 `SYSTEM_PARTITIONS` 定义

### 5.2 分区优先级的作用

- **同名包覆盖**：高优先级分区的 APK 会覆盖低优先级分区的同名 APK
- **OEM 定制**：OEM 可以在 product 分区放置自己的 Launcher，覆盖 system 分区的 AOSP Launcher
- **分区独立性**：各分区可独立升级，无需整体刷机

### 5.3 Launcher3 的实际安装路径

`privileged: true` + `system_ext_specific: true` 的安装路径决定逻辑：

```
privileged: true
    → APK 安装到 priv-app/ 子目录（而非 app/ 目录）
    → PMS 通过路径判断特权标记

system_ext_specific: true
    → APK 安装到 system_ext 分区
    → 如果设备没有独立的 system_ext 分区，回退到 product 分区
```

实际路径为以下两者之一：

```
/system/system_ext/priv-app/Launcher3QuickStep/Launcher3QuickStep.apk
或
/system/product/priv-app/Launcher3QuickStep/Launcher3QuickStep.apk
```

### 5.4 PMS 扫描流程

```
PackageManagerService.main()
  └→ InitAppsHelper.initSystemApps()
       ├→ 扫描 /system/app/ 和 /system/priv-app/
       ├→ 扫描 /vendor/app/ 和 /vendor/priv-app/
       ├→ 扫描 /odm/app/ 和 /odm/priv-app/
       ├→ 扫描 /product/app/ 和 /product/priv-app/
       └→ 扫描 /system_ext/app/ 和 /system_ext/priv-app/
            └→ 对每个APK解析 AndroidManifest.xml
            └→ 注册到 ComponentResolver
            └→ 如果路径包含 /priv-app/，标记 PRIVATE_FLAG_PRIVILEGED
```

---

## 6. 特权应用机制

### 6.1 什么是特权应用

安装在 `priv-app/` 目录的应用会被 PMS 标记为"特权应用"。

**源码依据**：`frameworks/base/services/core/java/com/android/server/pm/Settings.java`

```java
// 第3927行
if (codePathStr.contains("/priv-app/")) {
    pkgPrivateFlags |= ApplicationInfo.PRIVATE_FLAG_PRIVILEGED;
}
```

### 6.2 特权应用的权限优势

| 权限级别 | 普通系统应用 | 特权应用 | 说明 |
|----------|------------|---------|------|
| `normal` | ✅ | ✅ | 普通权限，自动授予 |
| `dangerous` | 需用户授权 | 需用户授权 | 运行时权限 |
| `signature` | ❌ | ❌ | 仅相同签名的应用 |
| `signature\|privileged` | ❌ | ✅ | ★ 特权应用可获取 |

### 6.3 Launcher3 需要的特权权限

Launcher3 需要通过特权获取的关键权限（定义在 `privapp_whitelist_com.android.launcher3.xml`）：

- `QUERY_ALL_PACKAGES`：查询所有已安装应用（显示应用列表）
- `CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS`：控制远程应用转场动画
- `FORCE_STOP_PACKAGES`：强制停止应用

### 6.4 特权白名单配置

```xml
<!-- privapp_whitelist_com.android.launcher3.xml -->
<permissions>
    <privapp-permissions package="com.android.launcher3">
        <permission name="android.permission.CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS"/>
        <permission name="android.permission.QUERY_ALL_PACKAGES"/>
    </privapp-permissions>
</permissions>
```

此文件通过 `required: ["privapp_whitelist_com.android.launcher3"]` 与 APK 关联，必须与 APK 同时安装。

---

## 7. overrides 覆盖机制

### 7.1 机制原理

`overrides` 是 Soong 构建系统的编译时机制，**不是运行时机制**。

当模块 A 被选中编译，且 A 的 `overrides` 包含模块 B 的名字时，Soong 会从系统镜像中排除模块 B。

### 7.2 各变体的 overrides

| APK | overrides | 含义 |
|-----|-----------|------|
| Launcher3 | Home, Launcher2 | 替换最旧的两个Launcher |
| Launcher3QuickStep | Home, Launcher2, **Launcher3** | 替换旧版+基础版 |
| Launcher3Go | Home, Launcher2, Launcher3, **Launcher3QuickStep** | 替换所有非Go版 |
| Launcher3QuickStepGo | Home, Launcher2, Launcher3, **Launcher3QuickStep** | 替换所有非Go版 |

### 7.3 Soong 处理 overrides 的流程

```
1. 读取所有 Android.bp 文件，构建依赖图
2. 根据 PRODUCT_PACKAGES 确定要编译的模块
3. 当模块A被选中编译，且A的 overrides 包含模块B的名字时，
   Soong会从系统镜像中排除模块B（B不会被安装到 output 目录）
4. 最终系统镜像中只包含被选中的APK，被覆盖的APK不存在
```

### 7.4 验证 overrides 效果

编译后检查系统镜像：

```bash
# 检查编译产物中是否存在被覆盖的APK
find out/target/product/<device>/system -name "Launcher3.apk"
# 如果编译的是 Launcher3QuickStep，则 Launcher3.apk 不存在
```

---

## 8. 产品配置与编译选择

### 8.1 产品配置文件位置

产品配置文件位于源码树的 `device/<厂商>/<产品>/` 目录下，通常是 `.mk` 文件。

例如：
- `device/google/coral/aosp_coral.mk`（Pixel 4）
- `device/google/gs101/aosp_oriole.mk`（Pixel 6）

### 8.2 配置方式

在产品的 `.mk` 文件中通过 `PRODUCT_PACKAGES` 指定要编译的 APK：

```makefile
# 主流设备配置（编译完整版Launcher）
PRODUCT_PACKAGES += Launcher3QuickStep

# Go设备配置（编译Go版Launcher）
PRODUCT_PACKAGES += Launcher3QuickStepGo

# 老设备配置（编译基础版Launcher，无手势导航）
PRODUCT_PACKAGES += Launcher3
```

### 8.3 编译命令

```bash
# 选择产品
lunch aosp_coral-userdebug

# 编译Launcher（增量编译）
make Launcher3QuickStep -j8

# 编译完整系统镜像
make -j8

# 编译产物路径
# out/target/product/<device>/system/product/priv-app/Launcher3QuickStep/
```

### 8.4 Android.bp 关键字段与 build.gradle 对照

| Android.bp | build.gradle | 说明 |
|------------|-------------|------|
| `name` | `project.name` | 模块名 |
| `srcs` | `sourceSets.main.java.srcDirs` | 源码路径 |
| `resource_dirs` | `sourceSets.main.res.srcDirs` | 资源路径 |
| `static_libs` | `dependencies { implementation }` | 静态依赖 |
| `libs` | `dependencies { compileOnly }` | 编译时依赖 |
| `sdk_version` | `compileSdkVersion` | SDK版本 |
| `min_sdk_version` | `minSdkVersion` | 最低SDK |
| `privileged` | N/A | 特权应用（系统特有） |
| `system_ext_specific` | N/A | 安装分区（系统特有） |
| `overrides` | N/A | 覆盖机制（系统特有） |
| `platform_apis` | N/A | 使用平台API（系统特有） |
| `optimize` | `buildTypes.minifyEnabled` | 混淆优化 |
| `additional_manifests` | `sourceSets.main.manifest.srcFile` | Manifest合并 |
| `required` | N/A | 必须同时安装的模块 |

---

## 9. AAOS 车载系统 Launcher

### 9.1 AAOS 不使用 Launcher3

AAOS（Android Automotive OS）使用独立的 **CarLauncher**，而非 Launcher3。

### 9.2 实际模拟器验证结果

使用 `sdk_car_x86_64-eng` 编译的 AAOS13 模拟器验证：

| 项目 | 结果 |
|------|------|
| APK路径 | `/system/priv-app/CarLauncher/CarLauncher.apk`（22.3MB） |
| 包名 | `com.android.car.carlauncher` |
| 主Activity | `com.android.car.carlauncher.CarLauncher` |
| Intent Filter | ACTION_MAIN + CATEGORY_HOME + CATEGORY_DEFAULT + CATEGORY_LAUNCHER_APP |
| 版本 | versionCode=33, targetSdk=33, versionName=13 |
| flags | SYSTEM HAS_CODE（系统应用） |
| 安装分区 | `/system/priv-app/`（注意：不是 `/system/product/priv-app/`） |

HOME Intent 解析结果：

```bash
$ pm resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME
→ com.android.car.carlauncher/.CarLauncher
```

### 9.3 为什么 AAOS 不用 Launcher3

| 对比项 | Launcher3（手机） | CarLauncher（车载） |
|--------|-------------------|-------------------|
| 布局 | 网格图标 | 大卡片布局 |
| 交互 | 触屏滑动 | 旋钮/语音/大按钮 |
| 功能 | 应用列表+Widget | 媒体控制+空调+导航 |
| 安全 | 无限制 | 驾驶安全限制 |
| 特有功能 | 手势导航 | InCallService集成 |

### 9.4 CarLauncher 源码位置

```
packages/apps/Car/Launcher/
```

---

## 10. 自定义 Launcher 开发指南

### 10.1 方法一：Manifest 声明（推荐）

在自定义 Launcher 的 `AndroidManifest.xml` 中声明 HOME Intent Filter：

```xml
<activity
    android:name=".MyLauncher"
    android:launchMode="singleTask"
    android:clearTaskOnLaunch="true"
    android:stateNotNeeded="true"
    android:exported="true"
    android:enabled="true">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.HOME" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
</activity>
```

### 10.2 方法二：源码中硬编码

修改 `ActivityTaskManagerService.java` 中的 `getHomeIntent()`：

```java
Intent getHomeIntent() {
    Intent intent = new Intent(mTopAction, mTopData != null ? Uri.parse(mTopData) : null);
    intent.setComponent(mTopComponent);
    intent.addFlags(Intent.FLAG_DEBUG_TRIAGED_MISSING);
    if (mFactoryTest != FactoryTest.FACTORY_TEST_LOW_LEVEL) {
        intent.addCategory(Intent.CATEGORY_HOME);
    }
    // ★ 硬编码指定自定义Launcher的包名和Activity
    intent.setPackage("com.example.myLauncher");
    return intent;
}
```

### 10.3 方法三：Android.bp overrides 机制

在自定义 Launcher 的 `Android.bp` 中使用 overrides 替换系统 Launcher：

```
android_app {
    name: "MyLauncher",
    overrides: [
        "Home",
        "Launcher2",
        "Launcher3",
        "Launcher3QuickStep",
        "Launcher3Go",
        "Launcher3QuickStepGo",
        "CarLauncher",          // AAOS也需要覆盖
    ],
    privileged: true,
    system_ext_specific: true,
    required: ["privapp_whitelist_com.example.myLauncher"],
}
```

然后在产品配置中：

```makefile
PRODUCT_PACKAGES += MyLauncher
```

### 10.4 特权白名单

创建 `privapp_whitelist_com.example.myLauncher.xml`：

```xml
<permissions>
    <privapp-permissions package="com.example.myLauncher">
        <permission name="android.permission.QUERY_ALL_PACKAGES"/>
    </privapp-permissions>
</permissions>
```

---

## 11. 关键源码索引

### 11.1 Launcher 启动流程

| 文件 | 路径 | 关键方法 |
|------|------|----------|
| RootWindowContainer | `frameworks/base/services/core/java/com/android/server/wm/RootWindowContainer.java` | `startHomeOnTaskDisplayArea()`, `resolveHomeActivity()`, `startHomeOnDisplay()` |
| ActivityTaskManagerService | `frameworks/base/services/core/java/com/android/server/wm/ActivityTaskManagerService.java` | `getHomeIntent()`, `systemReady()` |
| ActivityStartController | `frameworks/base/services/core/java/com/android/server/wm/ActivityStartController.java` | `startHomeActivity()` |
| ResolveIntentHelper | `frameworks/base/services/core/java/com/android/server/pm/ResolveIntentHelper.java` | `queryIntentActivities()`, `resolveIntent()` |
| ComputerEngine | `frameworks/base/services/core/java/com/android/server/pm/ComputerEngine.java` | `queryIntentActivities()` |
| ComponentResolver | `frameworks/base/services/core/java/com/android/server/pm/ComponentResolver.java` | `queryActivities()` |

### 11.2 Launcher3 编译

| 文件 | 路径 | 说明 |
|------|------|------|
| Android.bp | `packages/apps/Launcher3/Android.bp` | 编译配置（4个APK变体） |
| AndroidManifest.xml | `packages/apps/Launcher3/AndroidManifest.xml` | 基础版Manifest |
| AndroidManifest-common.xml | `packages/apps/Launcher3/AndroidManifest-common.xml` | 公共组件声明 |
| quickstep/AndroidManifest.xml | `packages/apps/Launcher3/quickstep/AndroidManifest.xml` | QuickStep主Manifest |
| quickstep/AndroidManifest-launcher.xml | `packages/apps/Launcher3/quickstep/AndroidManifest-launcher.xml` | QuickstepLauncher声明 |
| go/AndroidManifest.xml | `packages/apps/Launcher3/go/AndroidManifest.xml` | Go版Manifest |

### 11.3 系统分区与安装

| 文件 | 路径 | 说明 |
|------|------|------|
| PackagePartitions | `frameworks/base/core/java/android/content/pm/PackagePartitions.java` | 分区定义 |
| InitAppsHelper | `frameworks/base/services/core/java/com/android/server/pm/InitAppsHelper.java` | 系统应用扫描 |
| Settings | `frameworks/base/services/core/java/com/android/server/pm/Settings.java` | 特权标记判断 |
| ApplicationInfo | `frameworks/base/core/java/android/content/pm/ApplicationInfo.java` | PRIVATE_FLAG_PRIVILEGED |

### 11.4 AAOS CarLauncher

| 文件 | 路径 | 说明 |
|------|------|------|
| CarLauncher源码 | `packages/apps/Car/Launcher/` | 车载Launcher |

---

## 附录：常用调试命令

```bash
# 查看当前HOME Intent解析结果
adb shell pm resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME

# 查看已安装的Launcher包名
adb shell pm list packages | grep -i launcher

# 查看Launcher APK安装路径
adb shell pm path com.android.launcher3

# 查看Launcher详细信息
adb shell dumpsys package com.android.launcher3

# 查看所有特权应用
adb shell ls /system/priv-app/ /system/product/priv-app/ /system/system_ext/priv-app/

# 查看当前正在运行的Home Activity
adb shell dumpsys activity activities | grep -i home

# 强制停止Launcher（系统会自动重启）
adb shell am force-stop com.android.launcher3

# 设置默认Launcher（需要root）
adb shell cmd package set-home-activity com.android.launcher3/.uioverrides.QuickstepLauncher
```
