package atlas.ui

import java.awt.AWTEvent
import java.awt.Component
import java.awt.Container
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.ComponentEvent
import java.awt.event.WindowEvent
import java.awt.font.TextHitInfo
import java.awt.im.InputMethodRequests
import java.text.AttributedCharacterIterator
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * 输入法候选框定位兜底（2026-09-26）。
 *
 * 根因链：Compose 的 `DesktopTextInputService.getTextLocation` 在拿不到焦点矩形时返回
 * null，XIM 客户端因此退化为 root-window 模式，候选框固定画在屏幕左下角。受控实验证明
 * 同一 JBR + fcitx4/搜狗 下纯 Swing 窗口（自带 requests 实现）四个 flag 组合全部跟随
 * 光标——服务端没问题，坏在 Compose 给出的 requests。
 *
 * 修法：反射把每个 Compose 场景挂给 AWT 的 `InputMethodRequests`
 * （`ComposeSceneMediator.currentInputMethodRequests`）换成包装器——委托原实现，原实现
 * 给不出有效坐标时回退到「所在窗口内的一个合理 spot」，保证服务端永远拿得到位置。
 * Compose 在每次文本框焦点切换时会用新实例覆盖该字段（enableInput），所以除一次性包装
 * 外，还在焦点/按键/窗口事件与低频定时器上持续重挂。
 */
object ImeCaretFix {
    private const val MEDIATOR_FIELD = "mediator"
    private const val REQUESTS_FIELD = "currentInputMethodRequests"

    @Volatile private var installed = false

    fun install() {
        if (installed) return
        installed = true
        // 窗口创建 / 焦点变化 / 鼠标与键盘事件都可能伴随 enableInput 覆盖字段，顺手重挂
        Toolkit.getDefaultToolkit().addAWTEventListener(
            { event ->
                when (event) {
                    is WindowEvent -> if (event.id == WindowEvent.WINDOW_OPENED) fixWindow(event.window)
                    is ComponentEvent -> fixWindow(SwingUtilities.windowForComponent(event.component) ?: event.component as? Window)
                    else -> {}
                }
            },
            AWTEvent.WINDOW_EVENT_MASK or AWTEvent.FOCUS_EVENT_MASK or
                AWTEvent.MOUSE_EVENT_MASK or AWTEvent.KEY_EVENT_MASK,
        )
        // 事件间隙的兜底：800ms 一轮全量检查（对象数量级很小，EDT 上开销可忽略）
        Timer(800) { Window.getWindows().forEach(::fixWindow) }.apply { isRepeats = true; start() }
        Window.getWindows().forEach(::fixWindow)
    }

    private fun fixWindow(window: Window?) {
        if (window == null) return
        walk(window.components)
    }

    private fun walk(components: Array<Component>) {
        for (c in components) {
            tryWrap(c)
            if (c is Container) walk(c.components)
        }
    }

    private fun tryWrap(component: Component) {
        runCatching {
            val mediatorField = component.javaClass.declaredFields.firstOrNull {
                it.name == MEDIATOR_FIELD || it.type.name.contains("ComposeSceneMediator")
            } ?: return
            mediatorField.isAccessible = true
            val mediator = mediatorField.get(component) ?: return
            val requestsField = mediator.javaClass.declaredFields.firstOrNull {
                it.type == InputMethodRequests::class.java && it.name == REQUESTS_FIELD
            } ?: return
            requestsField.isAccessible = true
            val origin = requestsField.get(mediator) as? InputMethodRequests
            if (origin is PatchedInputMethodRequests || origin is AlwaysAliveRequests) return
            requestsField.set(
                mediator,
                if (origin == null) {
                    // 关键：字段为 null（还没激活任何文本框）时也要放一个常驻实现——
                    // XIM 在窗口聚焦时就创建输入上下文并协商样式，requests 为 null 会被当成
                    // 「被动客户端」，XIC 建成 root-window 样式，之后候选框永远在屏幕左下角。
                    AlwaysAliveRequests(component)
                } else {
                    PatchedInputMethodRequests(origin, component)
                },
            )
            atlas.core.Log.i("IME 兜底：已包装 ${component.javaClass.name.substringAfterLast('.')} 的 InputMethodRequests")
        }
    }

    /**
     * 委托原 requests；[getTextLocation] 拿不到有效坐标时回退到所在窗口内容区内的
     * 一个合理 spot（有历史有效值用历史值，否则用窗口内偏左上的固定锚点），
     * 保证输入法服务端永远能拿到候选框位置。
     */
    private class PatchedInputMethodRequests(
        private val origin: InputMethodRequests,
        private val anchor: Component,
    ) : InputMethodRequests {
        @Volatile private var lastGood: Rectangle? = null

        override fun getTextLocation(offset: TextHitInfo?): Rectangle? {
            val delegated = runCatching { origin.getTextLocation(offset) }.getOrNull()
            if (delegated != null && (delegated.x != 0 || delegated.y != 0)) {
                lastGood = delegated
                return delegated
            }
            if (!anchor.isShowing) return lastGood
            val loc = runCatching { anchor.locationOnScreen }.getOrNull() ?: return lastGood
            val fallback = Rectangle(
                loc.x + anchor.width / 4,
                loc.y + (anchor.height * 0.2f).toInt().coerceAtLeast(48),
                2,
                24,
            )
            atlas.core.Log.d("IME 兜底：原实现返回 $delegated，回退 spot=$fallback")
            return fallback
        }

        override fun getLocationOffset(x: Int, y: Int): TextHitInfo? =
            runCatching { origin.getLocationOffset(x, y) }.getOrNull()

        override fun getInsertPositionOffset(): Int =
            runCatching { origin.getInsertPositionOffset() }.getOrElse { 0 }

        override fun getCommittedText(
            beginIndex: Int,
            endIndex: Int,
            attributes: Array<out AttributedCharacterIterator.Attribute>?,
        ): AttributedCharacterIterator =
            origin.getCommittedText(beginIndex, endIndex, attributes)

        override fun getCommittedTextLength(): Int =
            runCatching { origin.getCommittedTextLength() }.getOrElse { 0 }

        override fun cancelLatestCommittedText(
            attributes: Array<out AttributedCharacterIterator.Attribute>?,
        ): AttributedCharacterIterator? =
            runCatching { origin.cancelLatestCommittedText(attributes) }.getOrNull()

        override fun getSelectedText(
            attributes: Array<out AttributedCharacterIterator.Attribute>?,
        ): AttributedCharacterIterator? =
            runCatching { origin.getSelectedText(attributes) }.getOrNull()
    }

    /**
     * 无激活文本框时的常驻 requests：唯一目的是让 XIM 创建输入上下文时把窗口认作
     * active 客户端（协商 PreeditPosition 样式，候选框跟随坐标）。无文本框时给
     * 窗口内兜底坐标；文本输入本身仍由 Compose 激活后的真实 requests 处理。
     */
    private class AlwaysAliveRequests(private val anchor: Component) : InputMethodRequests {
        override fun getTextLocation(offset: TextHitInfo?): Rectangle? {
            if (!anchor.isShowing) return Rectangle(0, 0, 2, 24)
            val loc = runCatching { anchor.locationOnScreen }.getOrNull() ?: return Rectangle(0, 0, 2, 24)
            return Rectangle(
                loc.x + anchor.width / 4,
                loc.y + (anchor.height * 0.2f).toInt().coerceAtLeast(48),
                2,
                24,
            )
        }

        override fun getLocationOffset(x: Int, y: Int): TextHitInfo? = null

        override fun getInsertPositionOffset(): Int = 0

        override fun getCommittedText(
            beginIndex: Int,
            endIndex: Int,
            attributes: Array<out AttributedCharacterIterator.Attribute>?,
        ): AttributedCharacterIterator? = null

        override fun getCommittedTextLength(): Int = 0

        override fun cancelLatestCommittedText(
            attributes: Array<out AttributedCharacterIterator.Attribute>?,
        ): AttributedCharacterIterator? = null

        override fun getSelectedText(
            attributes: Array<out AttributedCharacterIterator.Attribute>?,
        ): AttributedCharacterIterator? = null
    }
}
