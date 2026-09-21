# Android 音频域

> Android 音频框架（焦点、播放配置、音源识别）的机制事实与踩坑，跨车机/手机项目成立；由 session-to-knowledge skill 维护。

## 边界

- **收**：AudioFocus / AudioPlaybackConfiguration / 音源识别 / 音频路由相关的机制层事实与踩坑
- **不收**：UI 域坑（→ [android-ui.md](./android-ui.md)）、跨进程共享与监听机制（→ [android-provider.md](./android-provider.md)）、通用设计权衡（→ [design-principles.md](./design-principles.md)）
- **分工**：本文收音频域"机制是什么、哪里有陷阱"；"该怎么设计"的通用思想只在 design-principles.md 出现

## 规则

- 条目用默认五段模板：**现象 → 原因 → 误区 → 解决方案 → 启示**
- 标题为完整命题（"X 条件下会 Y"），便于按问题检索

## 目录

- [音源识别需焦点层与播放层合用：焦点层给"谁"，播放层给"真不真"](#音源识别需焦点层与播放层合用焦点层给谁播放层给真不真)

<!-- 条目模板：见 session-to-knowledge skill 默认五段模板 -->

## 音源识别需焦点层与播放层合用：焦点层给"谁"，播放层给"真不真"

**现象**：车机项目（Android 车机 Launcher）要做"特定音乐 App 播放时执行某动作"。第一版用 `AudioPlaybackConfiguration.getClientUid()` 反查包名判断音源，实车日志里所有播放配置返回同一大堆包名（40+ 个）；改用"配置存在即播放"判断状态，又出现"明明没在放歌却全部判定为播放中"。

**原因**：两层机制事实叠加。①车机全系统应用共享 `android.uid.system`（UID 1000），`getClientUid()` 拿到 UID 后 `getPackagesForUid()` 反查返回的是整个共享组的包名集合——共享 UID 环境下 UID→包名反查天然失效；②audio HAL 启动时为每个 usage 预注册 playback config，`AudioManager.getActivePlaybackConfigurations()` 返回的是**全部注册项**而非"正在出声"的列表，必须再按 `playerState == PLAYER_STATE_STARTED` 过滤。而焦点层 `AudioFocusInfo.getPackageName()` 是系统在焦点申请时已解析好的字段，不经过 UID 反查，可靠。

**误区**：直觉认为 `getActivePlaybackConfigurations()` 里的"active"就是"正在播放"——实际它是"已注册"；直觉认为拿 UID 反查包名是标准 API 用法——在共享 UID 的系统应用/车机/定制 ROM 环境这条路根本不通，还容易误判成自己代码的 bug 反复排查。

**解决方案**（来源：萌宠服务 `yadea_master/application/Launcher/.../pet/platform/PetSources.kt` 概念）：

```kotlin
// 焦点层：谁持有 MEDIA 焦点（包名由系统解析好，可靠）
carAudioManager.registerAudioFocusCallback { infos ->
    val source = infos.filter { it.usage == AudioAttributes.USAGE_MEDIA }
        .map { it.packageName }
}
// 播放层：是否真的在出声（必须按状态过滤预注册配置）
audioManager.registerAudioPlaybackCallback { configs ->
    val playing = configs.any {
        it.audioAttributes.usage == AudioAttributes.USAGE_MEDIA &&
        it.playerState == AudioPlaybackConfiguration.PLAYER_STATE_STARTED
    }
}
```

两层事件做与运算：焦点在目标包名上 且 播放状态为 STARTED，才判定"目标音源播放中"。

**启示**：
- 系统服务返回的"活跃列表"先问一句：这是**注册表**还是**运行表**（蓝牙连接、打印机、USB 设备服务同理）——预注册/常驻条目会污染列表，必须按运行期状态字段二次过滤
- 共享 UID 环境（系统应用、`sharedUserId`、车机 ROM）里 UID→包名反查普遍失效，优先用框架已解析好的包名字段；凡涉及应用身份的 API，先在目标环境打日志验证返回值再写业务逻辑
