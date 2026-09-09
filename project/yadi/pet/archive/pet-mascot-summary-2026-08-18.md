# 萌宠（VPA Pet）功能 — 今日工作总结

> 日期：2026-08-18
> 范围：Launcher 萌宠动作仲裁 + 音源识别 + 萌宠开关接入

---

## 一、工作概览

| # | 事项 | 结果 |
|---|---|---|
| 1 | Sisyphus 默认模型调整 | deepseek-v4-flash → mimo-v2.5-pro |
| 2 | PetAction/PetActionCode 合并评估 | 确认 1:1 映射冗余，建议合并为单一枚举 |
| 3 | Pet 相关代码加日志 | PetActionController 全方法 LogUtils 日志 |
| 4 | 萌宠开关接口调研 | 确认 Settings.Global + ContentObserver + Kanzi 桥接 |
| 5 | 萌宠开关接入（不改 Setting） | 方案 C 配置化双通道 + PetPropertyMonitor |
| 6 | AudioSource 代码加日志 | AudioSourceMonitor 全方法日志 |
| 7 | 音源包名识别（核心需求） | 焦点层 getPackageName 方案，实车验证通过 |
| 8 | 播放状态失真修复 | getPlayerState 过滤预注册配置 |
| 9 | 状态抖动修复 | 2 秒防抖过滤切歌抖动 |

---

## 二、核心问题清单与解决方案

### 问题 1：萌宠开关信号源（不改 Setting）

**背景**：萌宠开关在 Setting 应用，Launcher 需要感知开关状态。

**现状链路**：
```
AIPetFragment → Settings.Global("ai_pet_switch")
  → KanziDataSourceManager ContentObserver → Kanzi → PetActionController
```

**问题**：链路多一跳、无初始值同步、依赖 Kanzi 正常。

**方案 C（配置化双通道）**：
- `PetConfig.CHANNEL_KANZI = 0`（旧链路）
- `PetConfig.CHANNEL_PROPERTY = 1`（新增直连，默认）
- 新增 `PetPropertyMonitor.kt` 直接监听 `Settings.Global`，register 时立即回调初始值
- `PetActionController.onKanziData` 按通道条件过滤 `VPA_SWITCH`

### 问题 2：音源包名识别（三次迭代）

**需求**：萌宠开关开时，网易云音乐 / 蓝牙音乐播放 → 舞动动画循环；暂停/结束 → 停止恢复常驻态。

**迭代 1：播放层 `getClientUid()` — 失败**
- `AudioPlaybackConfiguration.getClientUid()` → `getPackagesForUid(uid)`
- 日志显示所有播放配置返回**同一大堆包名**（40+ 个）
- 根因：车机所有播放配置共享 `android.uid.system`（UID 1000），`getPackagesForUid(1000)` 返回整个共享 UID 组

**迭代 2：焦点层 `getPackageName()` — 成功**
- `AudioFocusInfo.getPackageName()` 是系统在焦点申请时解析好的字段，**不经过 UID 反查、不经过匿名化**
- 实车日志验证：`usagePackages={1=[com.arcvideo.car.ncm.music]}`
- 网易云车机版包名 = `com.arcvideo.car.ncm.music`（非标准 `com.netease.cloudmusic`）

**迭代 3：librarian 调研印证（补充）**
- `getClientUid()` 是 `@SystemApi @hide`，且有匿名化机制（无 `MODIFY_AUDIO_ROUTING` 权限时 UID=-1）
- 确认焦点层 `getPackageName()` 是唯一正确数据源

### 问题 3：播放状态失真

**现象**：`activePlaybackConfigurations` 返回 11 个配置，覆盖所有 usage，全部误判"播放中"。

**根因**：车机 audio HAL 为每个 usage 预注册 playback config，`activePlaybackConfigurations` 返回全量注册列表（非真实播放列表）。`getPlayerState()` 是 @hide 无法过滤。

**解决**：framework.jar 编译环境（`-Xbootclasspath/p`）下 `getPlayerState()` 是 public，直接调用：
```kotlin
val playingUsages = activeConfigs
    .filter { it.playerState == AudioPlaybackConfiguration.PLAYER_STATE_STARTED }
    .mapNotNull { it.audioAttributes?.usage }
    .toSet()
```
仅保留真正 `STARTED` 的配置。

### 问题 4：isActive 时序 bug

**现象**：`onFocusGained` 后 `evaluateSourceState` 读到的 `isActive` 仍为 false。

**根因**：`lastFocusUsages` 在 `handleFocusChanged` 末尾才赋值，而 `onFocusGained/onFocusLost` 在赋值前就被调用。

**解决**：diff 循环前提前更新 `lastFocusUsages`，用局部变量保存旧值。

### 问题 5：MUSIC 后紧接着 MUSIC_END（闪断）

**现象**：舞动动画刚开始就停止，20ms 内状态翻转。

**根因**：网易云切歌/缓冲时 `STARTED ↔ PAUSED` 快速抖动，萌宠零延迟响应每次状态变化。

**解决**：2 秒防抖（`PetConfig.MUSIC_END_DEBOUNCE_MS = 2_000L`）：
```kotlin
PLAYING → cancelMusicEnd() + dispatch(MUSIC)
PAUSED/STOPPED → scheduleMusicEnd() 延迟 2s 确认
  期间恢复 PLAYING → 取消；保持暂停 → dispatch(MUSIC_END)
```

---

## 三、最终架构

```
采集层  AudioSourceMonitor（焦点层 + 播放层）
  ├─ CarAudioManager.CarFocusCallback → AudioFocusInfo.getPackageName() → usagePackages
  └─ AudioManager.AudioPlaybackCallback → getPlayerState()==STARTED → usagePlaying
       ↓ AudioSourceEvent(source, state, usage, isActive, packages)
分类层  PetActionController.MUSIC_SOURCE_PACKAGES（包名过滤）
       ↓
消费层  PetActionController（dispatch MUSIC/MUSIC_END，2s 防抖）
```

### 数据流（实车验证）

```
网易云播放 → 焦点 gained MEDIA (package=com.arcvideo.car.ncm.music)
  → getPlayerState==STARTED → PLAYING → dispatch MUSIC(90) 舞动循环
  ↓ 2s 防抖
网易云暂停 → PAUSED 持续 2s → dispatch MUSIC_END(91) 恢复常驻
```

---

## 四、文件改动清单

### 新增
| 文件 | 说明 |
|---|---|
| `control/pet/PetPropertyMonitor.kt` | 监听 Settings.Global 萌宠开关 |
| `control/pet/PetActionController.kt` | 仲裁中枢（本次大量改造） |

### 修改
| 文件 | 改动 |
|---|---|
| `control/pet/PetConfig.kt` | 通道常量 + `MUSIC_END_DEBOUNCE_MS` |
| `control/pet/PetActionController.kt` | 包名过滤 + 防抖 + 全方法日志 |
| `control/audiosource/AudioSourceListener.kt` | `AudioSourceEvent` 加 `packages` 字段 |
| `control/audiosource/AudioSourceMonitor.kt` | 焦点层取包名 + playerState 过滤 + 时序修复 + 日志 |

### 关键包名常量
```kotlin
MUSIC_SOURCE_PACKAGES = setOf(
    "com.arcvideo.car.ncm.music",  // 网易云（车机版）
    "com.yadea.btmusic",           // 蓝牙音乐（待验证）
)
```

---

## 五、遗留事项

1. **蓝牙音乐包名待验证**：日志中尚未出现蓝牙播放的焦点变化，`com.yadea.btmusic` 为推测值，需实车播放蓝牙音乐验证。
2. **interruptedUsages 残留**：NOTIFICATION（usage=5）被打断后长期残留 `interruptedUsages`，不影响 MEDIA 业务，未处理。
3. **NOTIFICATION 状态抖动**：SystemUI 通知音在 PLAYING/PAUSED/INTERRUPTED 间抖动，不影响 MEDIA 业务。
4. **PetActionCode 合并**：评估完成但未实施（用户后续可决定是否合并 PetAction 与 PetActionCode）。

---

## 六、技术要点备忘

- **Launcher 是系统应用**：`android:sharedUserId="android.uid.system"` + 平台签名（platform.jks），hidden API 豁免。
- **framework.jar 编译**：`-Xbootclasspath/p:framework.jar`，`getPlayerState()`、`getClientUid()` 等 @hide 方法编译期 public。
- **焦点层 vs 播放层**：焦点层给"谁持有焦点 + 包名"（可靠），播放层给"是否真实出声"（需 playerState 过滤）。
- **共享 UID 陷阱**：`getClientUid()` → `getPackagesForUid()` 遇到 `android.uid.system` 会返回全部系统包名，不可用于音源识别。
