# 应用焦点契约：怎样申请、响应和释放

> 以 Android 13/AAOS 13 为主。此册只讨论应用与焦点系统的契约。播放器的数据通路见后续文档。官方 API 文档可能包含 Android 15 之后新增的限制，不能倒推为 Android 13 行为。

**Q1: [learning] 应用要开始一段声音，AudioManager、AudioAttributes、AudioFocusRequest 各负责什么？**

AudioManager 是应用申请/放弃焦点的公开入口，AudioAttributes 表明声音用途，AudioFocusRequest 把用途、预期时长/让位方式和回调绑定成一次请求。焦点请求的 AudioAttributes 要与实际播放器使用的一致，否则车机焦点按一个 context 裁决，路由/音量却按另一个 context 执行。

1. **AudioAttributes.usage**：决定 AAOS 中的 CarAudioContext、路由及音量组候选。点击音用 `USAGE_ASSISTANCE_SONIFICATION`，媒体用 `USAGE_MEDIA`，导航用 `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`。
2. **gain hint**：持续媒体用 `AUDIOFOCUS_GAIN`。短声希望旧声音暂停用 `AUDIOFOCUS_GAIN_TRANSIENT`。允许旧声音继续但可 duck 用 `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`。短时排他可用 `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE`，不可拿来给普通点击声抬优先级。
3. **请求结果**：`GRANTED` 后才按协议播放。`FAILED` 时不播。`DELAYED` 时等回调授予再播。一次很短的点击通常没有等数秒再播的价值，所以通常不启用延迟焦点。
4. **放弃时机**：逻辑声音结束或用户取消时调用 `abandonAudioFocusRequest()`。暂停期间是否保留取决于会话意图。永久 loss 后不能继续当自己持焦。

来源：[管理音频焦点](https://developer.android.com/media/optimize/audio-focus)、[AudioFocusRequest API](https://developer.android.com/reference/android/media/AudioFocusRequest)、[AAOS 焦点文档](https://source.android.com/docs/automotive/audio/audio-focus)。

**Q2: [learning] 如何写一个与播放器解耦、能用于短时音效的焦点控制器？**

`AudioFocusRequest` 与本例用到的 Builder API 自 API 26 起可用，因此下面的 Java 类要求运行在 API 26 及以上。类只负责焦点：宿主把实际开始和停止播放接到两个回调，并在音效真正结束时调用 `finish()`。这样代码不假装 SoundPool 提供了播放完成回调。SoundPool 只有加载完成回调，具体音效结束需要宿主按播放器能力管理。该实现不接受延迟焦点，失败时返回 false。

```java
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

public final class ShortSoundFocus implements AutoCloseable {
    private final AudioManager audioManager;
    private final AudioFocusRequest request;
    private final Runnable startPlayback;
    private final Runnable stopPlayback;
    private boolean requested;

    public ShortSoundFocus(Context context, Runnable startPlayback,
            Runnable stopPlayback) {
        this.audioManager = context.getSystemService(AudioManager.class);
        this.startPlayback = startPlayback;
        this.stopPlayback = stopPlayback;
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .build();
        this.request = new AudioFocusRequest.Builder(
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(change -> {
                    if (change < 0) {
                        finish();
                    }
                }, new Handler(Looper.getMainLooper()))
                .build();
    }

    public boolean play() {
        if (requested) {
            return false;
        }
        int result = audioManager.requestAudioFocus(request);
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            return false;
        }
        requested = true;
        try {
            startPlayback.run();
        } catch (RuntimeException e) {
            finish();
            throw e;
        }
        return true;
    }

    public void finish() {
        stopPlayback.run();
        if (requested) {
            audioManager.abandonAudioFocusRequest(request);
            requested = false;
        }
    }

    @Override public void close() {
        finish();
    }
}
```

配置项及省略后果必须逐项核对：

1. `setUsage(USAGE_ASSISTANCE_SONIFICATION)`：明确普通 UI 音效，使 AAOS 13 映射到 `SYSTEM_SOUND`。省略时 Builder 默认 usage 为 `USAGE_UNKNOWN`，车机通常映射到 `MUSIC`，会改变焦点矩阵和路由。
2. `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`：表明短时声音容许旧持焦者继续。Builder 必须给 gain 类型。换成 `AUDIOFOCUS_GAIN_TRANSIENT` 将使并发矩阵即使允许共存也可能让旧持有者丢焦。
3. `setAudioAttributes(attributes)`：让焦点与播放器用同一用途。省略时 AudioFocusRequest 默认 `USAGE_MEDIA`。若播放器仍按 UI 音效播放，就会出现焦点/路由语义错位。
4. `setOnAudioFocusChangeListener(..., Handler(Looper.getMainLooper()))`：失焦时在主线程结束该短音并放弃请求，避免已停止播放却继续占着焦点。显式主线程 Handler 是为了让本例的 `play()`/`finish()` 与回调共享线程。省略监听器时，在未要求延迟焦点的条件下 Builder 可创建请求，但应用收不到后续变化。省略 Handler 的重载会使用 AudioManager 创建时关联的 Looper，不能假定始终是主线程。
5. 未调用 `setAcceptsDelayedFocusGain(true)`：默认为 false，焦点暂不可用时本例立即失败。若要延迟播放，必须显式打开、保持回调，并在收到 `AUDIOFOCUS_GAIN` 后启动。对点击反馈通常不合适。
6. 未调用 `setWillPauseWhenDucked(true)`：默认 false。此参数表达“本应用持焦时，别人请求 MAY_DUCK，本应用希望暂停”。它不控制本次请求能否并发。短音若被其他声音打断，本例在 loss 回调停止。

本类要求宿主在主线程串行调用 `play()`/`finish()`。若跨线程使用，要通过 Handler 串行派发或同步状态。`stopPlayback` 应允许重复调用，因为失焦回调和宿主结束动作可能先后到达。它不支持多个音效实例重叠持焦。`startPlayback` 应异步或快速返回。实际播放器的 AudioAttributes 也必须设为相同 usage。来源：[AudioFocusRequest API 与默认值](https://developer.android.com/reference/android/media/AudioFocusRequest)、[AudioAttributes.Builder](https://developer.android.com/reference/android/media/AudioAttributes.Builder)、[AAOS 13 CarAudioFocus](https://android.googlesource.com/platform/packages/services/Car/+/refs/heads/android13-release/service/src/com/android/car/audio/CarAudioFocus.java)。

**Q3: [learning] 媒体应用收到 loss、transient loss、duckable loss 时，何时恢复？**

媒体应按焦点变化管理同一个播放会话，不能仅看 AudioManager 当前音量，也不能定时自行“抢回来”。

1. `AUDIOFOCUS_LOSS`：视为永久失焦，停止或暂停并释放本次焦点会话。用户再次点播放时重新申请。不要等待系统必然恢复。
2. `AUDIOFOCUS_LOSS_TRANSIENT`：暂时暂停并保留可恢复状态。随后收到 `AUDIOFOCUS_GAIN` 才恢复，而且还要确认用户没有在等待期间主动暂停。
3. `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK`：普通音乐可降低音量继续播放。有语音内容的播客、有声书可通过 `setWillPauseWhenDucked(true)` 请求回调并暂停，以免丢失语义。Android 8+ 系统可自动 duck 非语音内容而不回调应用。AAOS 外部焦点策略也会影响通知方式，不能依赖“每次都会收到 CAN_DUCK”。

来源：[Android 音频焦点回调和自动 duck](https://developer.android.com/media/optimize/audio-focus)、[AAOS 并发条件](https://source.android.com/docs/automotive/audio/audio-focus)。

**Q4: [learning] 车机焦点被拒或延迟时，应用界面应如何呈现？**

把焦点结果当状态转换，而非一次“播放命令”的附属返回值。拒绝表示当前不可播，延迟表示未来可能可播，两者都不能立即向音频输出写数据。

1. **拒绝**：保持停止状态，可在界面说明“通话或系统声音正在使用音频”。是否提示由产品体验决定。普通点击音可直接省略，不应把一次点击排队到通话后。
2. **延迟**：只用于确有等待价值的长内容。`setAcceptsDelayedFocusGain(true)` 配监听器，收到 `AUDIOFOCUS_GAIN` 后再次检查用户意图和界面/生命周期，再开始播放。
3. **用户取消**：在等待中也要调用 `abandonAudioFocusRequest()`。否则稍后回调到达可能突然播放。

AAOS 13 的 CarAudioFocus 只允许 `AUDIOFOCUS_GAIN` 且带延迟标志的请求被延迟。同一 client/listener 的不同 usage 并发请求会被拒绝，且实现仅保存一个延迟请求。来源：[AAOS 13 CarAudioFocus 源码](https://android.googlesource.com/platform/packages/services/Car/+/refs/heads/android13-release/service/src/com/android/car/audio/CarAudioFocus.java)、[AudioFocusRequest 延迟焦点说明](https://developer.android.com/reference/android/media/AudioFocusRequest)。
