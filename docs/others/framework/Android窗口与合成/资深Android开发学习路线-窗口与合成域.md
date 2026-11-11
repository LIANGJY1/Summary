# 资深 Android 开发养成路线：吃透窗口与合成域
## 以窗口透明与合成源码分析为主线

> 前提：你已能写业务层 Android（Activity/Fragment/View 使用层面），目标是把两篇文章的全部内容变成自己的可迁移能力。
> 本路线的全部教材、源码、题库都在本地两仓，不需要外部资源。路径口径：两篇主教材文章与本文件同目录；`docs/md/` 前缀的存量配套文档指 AAOS13_study 学习树（/home/liang/Project/MyProject/AAOS13_study）下的路径。

---

## 0. 先定义"学透"：三层能力标准

| 层级 | 能力 | 检验方式 |
|---|---|---|
| L1 能复述 | 不看资料画出全链路图（三进程、六层：声明→状态→几何→绘制→合成→转场），解释遮挡、绘制、合成和转场之间的关系 | 白纸默写链路图，对照文章 §0 |
| L2 能推导 | 给一个**没见过**的现象（如"分屏下某 App 不 stop""旋转瞬间黑一下"），能用'声明与实际像素两组状态 / 期望可见与实际可见的分离 / 合成列表三个过滤条件'推出机制假设 | 自测题 30 问中的推导题 |
| L3 能测量 | 对每个假设设计出可证伪的实验（adb/demo/trace），并知道命令的服务端入口与注意事项 | 实验清单 E1~E12 全部独立完成 |

学习目标不仅是理解代码，还要能设计实验，区分源码事实、推论和设备实测结果。

## 1. 五条方法（贯穿全程）

1. **追链法**：每个机制从入口追到底，每一步记 file:line，亲手画调用链。禁止"大概在那边"。两篇文章的每段代码都是链上的一站，你的任务是脱离文章自己重走一遍。
2. **两组状态的心智模型**：分析问题时先区分两组状态——**声明 vs 实际像素**（occludesParent/format vs 真实画出的内容）、**期望可见 vs 实际可见**（visibleRequested vs isVisible）。遇到新问题先问：这是哪一组状态？两者此刻一致吗？
3. **实验闭环**：学习每个机制后，选取对应实验；区分源码已验证、设备已验证和仍待验证的结论。
4. **输出驱动**：每阶段结束产出一页纸链路图＋一组闪卡（问题→机制→证据位置）。本仓 knowledge-base/android 已有 1304 题可做间隔重复底料，你自己的闪卡补充域内新题。
5. **版本纪律**：一切以本地树（A13）为准；读任何旧文章先过一遍文章一附录 A 的差异清单。引用机制必带版本。

**三遍读书法**（对文章一）：第一遍通读只建框架，不抠代码（半天）；第二遍逐节双开源码，把文中每段引文在编辑器里定位并上下各读 50 行——**引文是路标不是终点**（这是主要时间开销）；第三遍合上文章做实验，卡住才回查（检验）。

## 2. 教材盘点（全在本仓）

- **核心**（均与本文件同目录）：Android13窗口透明与合成全链路解剖.md（机制主教材，§1~§9）；技术报告与交接文档逐句源码审计.md（方法论教材，学"如何审计"）。
- **配套精读**（已有存量文档，按阶段指定）：docs/md/一帧的生命周期.md、docs/md/一次触摸的旅程.md、docs/md/WMS窗口管理.md、docs/md/Surface与BufferQueue.md、docs/md/Android动画.md、docs/md/Activity启动.md、docs/md/分屏多窗口SplitScreen.md、docs/md/SystemUI.md、docs/md/lmkd与进程组.md、docs/md/启动全流程/（00~09）、docs/md/Binder*（4 篇）、docs/md/Handler.md。
- **源码树**：本仓即 A13 全源码带中文批注。批注是导览，**结论以原始代码为准**（批注战役本身经过两轮审计修正，这个事实本身就值得体会）。
- **工具**：scripts/check_doc_paths.py（你写文档时的路径校验脚本）；adb 命令手册见文章一 §8。

## 3. 六阶段路线

建议安排约 16~18 周（每周 8~10 小时）；实际进度取决于基础、设备与实验条件。每阶段格式：目标 → 材料 → 动手 → 产出物 → 过关标准。

### 阶段 0：地基（1~2 周）

- **目标**：补齐读懂主教材必需的三块地基——Binder IPC（跨进程调用怎么发生）、Handler/Choreographer（帧节拍从哪来）、AOSP 目录与编译（源码怎么导航）。
- **材料**：docs/md/Binder.md 及 Binder_open/ioctl/mmap 三篇、docs/md/Handler.md、docs/md/Android架构目录导览.md（18 章，通读建地图）、docs/md/启动全流程/07_system_server.md（服务怎么注册进 system_server）。
- **动手**：① 在本仓定位任意五个类文件（ActivityRecord、WindowState、Output.cpp、ViewRootImpl、DecorView），说出各自进程归属；② 用 `service list` 和 `dumpsys -l` 在模拟器/真机上列出本域全部服务。
- **过关**：能解释"应用进程的一次 relayout 是怎么到达 WMS 又怎么回来的"（IWindowSession.aidl → Session.java → WindowManagerService），不看资料说出三个进程的分工。

### 阶段 1：应用侧——窗口与绘制（2~3 周）

- **目标**：吃透文章一 §1（声明层）、§3（几何层）、§4（绘制层）：主题属性两条通道、relayout 往返、frame/DecorView/buffer 同源、insets→padding、DecorView 结构与 statusBarColor 真身。
- **材料**：文章一 §1/§3/§4；docs/md/一帧的生命周期.md（渲染主链，与 §3 互证）；docs/md/一次触摸的旅程.md（ViewRootImpl 的另一半职责）。
- **源码精读清单**（每个都从文章引文处向上下扩展读）：PhoneWindow.generateLayout 全函数、DecorView 的 updateColorViews/updateColorViewInt/drawableChanged、ViewRootImpl 的 setView/performTraversals/relayoutWindow、WindowLayout.computeFrames 全文、View.fitSystemWindowsInt/applyInsets。
- **动手**：E1（四组合主题矩阵 demo：建 4 个 Activity 分别配 translucent×background 四组合，观察声明不透明且内容留有透明区域时的窗口、图层和最终画面）、E4（fitsSystemWindows 开关对比 + uiautomator dump 量 bounds）、E5（找到 statusBarBackground 色块 View）。
- **产出**：一页纸"从 setContentView 到第一帧显示到屏幕"链路图；闪卡 Q1~Q11。
- **过关**：白纸画出 relayout 往返图（含 computeFrames 三档取源）；口头解释"哪些布局配置使普通 Activity 窗口延伸到系统栏下方，对话框又如何避让"。

### 阶段 2：服务端——WMS/ATMS 状态记录与转场（3~4 周，本路线最重的一站）

- **目标**：吃透 §2（状态层）与 §6（转场层）：ensureActivitiesVisible 扫描、期望/实际可见性的分离、pause/stop 两链、T0~T3 时间轴、leash、动画资源解析链、两套转场状态机。
- **材料**：文章一 §2/§6；docs/md/Activity启动.md、docs/md/WMS窗口管理.md、docs/md/Android动画.md、docs/md/分屏多窗口SplitScreen.md（遮挡语义的延伸）。
- **源码精读清单**：EnsureActivitiesVisibleHelper 全文（不到 300 行，逐行）、ActivityRecord 的 makeInvisible/setOccludesParent/prepareSurfaces/onAnimationFinished、TaskFragment.startPausing、ActivityTaskSupervisor.processStoppingAndFinishingActivities、AppTransitionController.handleAppTransitionReady 全函数、AppTransition.loadAnimation、WindowContainer.applyAnimation/getAnimationAdapter/AnimationRunnerBuilder、SurfaceAnimator、WindowState 的 performShowLocked/onExitAnimationDone/isOnScreen、TransitionAnimation.loadAnimationAttr。
- **动手**：E2（occludesParent 对比观测）、E3（logcat events 量出 pause/stop 与动画的时序关系）、E8（winscope 双 trace 回放一次完整 T0~T3）、E9（**核心实验**：比较清除 activityOpenExitAnimation 与配置 alpha 1→1 的情况，记录实际选中的动画、窗口隐藏时机和图层变化）、E10（makeCustomAnimation 在任务级被忽略 vs overrideTaskTransition 打通）。
- **产出**：一页纸 T0~T3 时序图（含三处'动画期间延迟隐藏'判断与两个 stop 前置条件）；闪卡 Q12~Q22。
- **过关**：不看资料回答"exit 动画从谁的主题来、为什么"；给一个陌生 App 的转场异常（如退出黑一下），能在 5 分钟内列出前三个嫌疑与对应取证命令。

### 阶段 3：图形栈——SurfaceFlinger 与合成（3~4 周）

- **目标**：吃透 §5（合成层）：一帧生命周期、进入合成列表的三个过滤条件、blendMode、黑色像素推导、hide 传导、composition cache；顺带 §7.1 的 buffer 数量与容量计算。
- **材料**：文章一 §5/§7.1；docs/md/Surface与BufferQueue.md（BufferQueue/BLAST 全景）。
- **源码精读清单**：Output.cpp 的 ensureOutputLayerIfVisible 全函数、composeSurfaces、generateClientCompositionRequests；Layer.cpp 的 prepareBasicGeometryCompositionState/prepareClearClientComposition/isHiddenByPolicy；BufferLayer.isVisible/isOpaque；SkiaGLRenderEngine.drawLayersInternal（清零、快照模糊、kSrcOver、drawRect 四站）；BLASTBufferQueue.cpp 的构造与 update；SurfaceComposerClient.Transaction 的 hide/setOpaque。
- **动手**：E6（核对 frame、buffer 尺寸及其例外）、E7（hide 后层仍在 --list、销毁后才消失的时机问题，亲手验证一遍）、E12（blur 强制 client composition 的 perfetto 对照）。
- **产出**：一页纸"一帧的合成流水线"图；透明区域最终颜色的条件分析；闪卡 Q23~Q27。
- **过关**：能说明 client composition 路径中透明黑清零的作用，并指出推导黑色画面所需的图层和输出条件。

### 阶段 4：性能与测量（2 周）

- **目标**：吃透 §7（成本层）与 §8（测量层）：把半透明架构的额外开销算清楚，把全部监控命令用到熟练，知道每条命令的服务端入口与失效形态。
- **材料**：文章一 §7/§8；审计篇发现四/五/八（三条命令为什么失效——**学命令要连它的 dump 源码一起学**）；docs/md/lmkd与进程组.md（adj 的下游）。
- **动手**：E11（translucent 栈 vs 不透明栈的 meminfo/gfxinfo 对比实验，自己复算报告的 3.7MB/11.1MB 数值）；把文章一 §8 的手册在自己的设备上全部跑一遍并记录实际输出；用 perfetto+winscope 抓一次完整转场并逐帧指认 layer 变化。
- **产出**：你自己设备上的《测量手册实测版》（每条命令+真实输出截取+注意事项注记）。
- **过关**：给你"设置前台时桌面是否参与合成"这一问题，能在 3 条命令内给出铁证。

### 阶段 5：审计方法论与毕业项目（2~3 周）

- **目标**：把审计篇当方法论教材用——学的不是那十条发现，而是生产发现的流程：断言拆解 → 三处对证（源码/设备仓/实机）→ 四级裁定 → 修正动作。
- **材料**：审计篇全文精读，重点四条纪律（结果对≠机制对 / 命令必须落到服务端入口 / 排除法要验证变量真的被改变 / 版本口径精确）。
- **动手（毕业项目，三选一）**：
  A. **复刻审计**：拿文章一本身当审计对象，任选 20 条断言独立验证（记录成立条件、证据与任何发现的勘误）；
  B. **新域解剖**：选一个本教材未覆盖的域（屏幕旋转、PIP 进出、深色模式切换、多屏 presentation、letterbox 兼容模式），按文章一的结构写一篇全链路解剖，交 check_doc_paths.py 与四条纪律自检；
  C. **未决问题重查**：按审计篇发现二/三给出的三个可检验假设（shell_transit 属性、主题 colorBackground、TYPE_APPLICATION_STARTING 观测），设计完整取证方案并在可用设备上执行。
- **过关**：毕业项目通过"另一个会话的 AI 或同事"的逐句质询（把本文档和两篇文章给对方，让对方挑刺，你能守住每一句）。

## 4. 自测题 30 问（按阶段分组，全部 recall 式；答案均在文章一/二对应节）

**阶段 1（Q1~Q11）**
1. A13 中 windowIsTranslucent=true 被 PhoneWindow 读到后做了什么？窗口像素 format 实际由什么决定？（§1.1）
2. mOccludesParent 在何时何处由什么解析而来？occludesParent() 返回 true 的完整条件？（§1.2）
3. layer 的 opaque 最终由哪个标志传达？写出它的完整计算式（含"format 带 alpha 即判 false"的条件）。（§1.3）
4. computeFrames 的三段式是什么？多窗口时 windowBounds 从哪来？（§3.1-3.2）
5. 所示测量路径中，MATCH_PARENT 如何使用 frame 尺寸？EXACTLY 在哪里产生，哪些情况需要另行检查？（§3.3）
6. surface 尺寸的常规来源与两个合法偏差？setDefaultBufferSize 和 setDestinationFrame 各管什么？（§3.3）
7. fitsSystemWindows 的 padding 最终打在哪个 View 上、由哪一行代码写入？insets 的源头是谁申报的？（§3.4）
8. statusBarColor=#00000000 时色块 View 处于什么状态？判定式是什么？（§4.2）
9. 应用自绘栏底与 SystemUI 栏透明之间的 appearance 协同闭环四步？（§4.2）
10. A13 的 config_forceWindowDrawsStatusBarBackground 默认值造成什么行为？（§4.2）
11. 对话框窗口为什么能从状态栏下方开始摆？涉及哪个客户端兼容函数？（§3.2）

**阶段 2（Q12~Q22）**
12. ensureActivitiesVisible 扫描中传播的核心变量是什么？何时置真何时复位？分屏的合并判定规则？（§2.1）
13. mVisibleRequested 的唯一写点在哪？它与 isVisible 的语义差在转场期如何体现？（§2.2）
14. stop 的两个前置条件分别是什么？在哪个文件检查？（§2.4）
15. Activity 停止如何影响进程状态、ViewRoot 和 Surface？各条路径的触发条件与代码入口是什么？（§2.5）
16. T0~T3 时间轴：每个时刻发生什么、哪些判断在维持 surface 可见？（§6）
17. exit 动画（activityOpenExitAnimation）从谁的主题解析？依据哪段代码的什么规则？（§6.3）
18. AOSP 默认 activity_open_exit 的两个分量是什么？它的工程含义？（§6.3）
19. leash 是什么？所示动画如何更新其属性，为什么这种变换不要求应用逐帧 relayout 或重绘？（§6.4）
20. makeCustomAnimation 在什么条件下被忽略？overrideTaskTransition 如何破局、需要什么权限？（§6.3）
21. legacy 与 Shell Transitions 的服务端判定依据？本树默认走哪条、由什么控制？（§6.7）
22. 转场期间 TDA 表面为什么会有颜色？色值来源链四跳？何时清除？（§6.4）

**阶段 3~4（Q23~Q30）**
23. ensureOutputLayerIfVisible 的三个过滤条件与两个 Region 累积量？（§5.2）
24. Layer isVisible 的三个条件？alpha 如何处理父级？（§5.2）
25. blendMode=NONE 的选择条件？圆角为什么破坏 opaque？（§5.3）
26. GPU 画布清零、client target 提交及层全部消失时的处理分别在哪里？仅凭这些代码能否确定设备最终像素？（§5.5）
27. hide 之后层为什么还在 dumpsys --list 里？什么时候才真正消失？（§5.6）
28. client composition cache 的命中条件是什么？哪些动画状态变化可能导致未命中？（§5.7）
29. BLAST 的队列上限受哪些参数影响？怎样估算三张 buffer 的容量，为什么不能把估算当作实测驻留量？（§7.1）
30. 背景模糊可能引入哪些合成开销？Kawase 的降采样与 pass 上限？（§7.3）

**推导题（L2 检验，随时抽自己）**：分屏下为什么被盖住的全屏任务可能不 stop？旋转动画中 BlackFrame 干什么用？windowDisablePreview 为什么在从桌面启动时无效、这对排除法实验意味着什么（审计篇发现三）？

## 5. 实验清单 E1~E12 速查

| # | 实验 | 验证的机制 | 阶段 |
|---|---|---|---|
| E1 | 四组合主题矩阵 demo，截图验证黑/透/正常 | 核对声明、实际像素与下层图层的关系 | 1 |
| E2 | dumpsys activity activities 对比 translucent/opaque 的 occludesParent 与下层 state | 状态记录通道 | 1/2 |
| E3 | logcat events 量 pause/stop 与动画起止的时序 | 观察 stop 条件与动画结束的时序 | 2 |
| E4 | fitsSystemWindows 开关 + uiautomator dump 量内容 bounds | insets→padding | 1 |
| E5 | Layout Inspector 找 statusBarBackground 色块 | statusBarColor 真身 | 1 |
| E6 | dumpsys window windows Frames vs SF 层 buffer 尺寸核对 | 布局尺寸与 buffer 的关系 | 1/3 |
| E7 | hide 后 --list 仍有名；持续采样至 surface 销毁 | 层树 vs 合成列表 | 3 |
| E8 | cmd window tracing + service call SurfaceFlinger 1025 双 trace，Winscope 回放 T0~T3 | 转场全链 | 2 |
| E9 | 比较清除 exit 动画和配置 alpha 1→1 动画时的图层与画面 | 保活（延迟隐藏）动画原理 | 2 |
| E10 | 任务级 custom anim 被忽略 vs overrideTaskTransition 生效 | canCustomize 门 | 2 |
| E11 | translucent 栈 vs 不透明栈 meminfo/gfxinfo 对比，复算 3.7MB/11.1MB | 成本模型 | 4 |
| E12 | FLAG_BLUR_BEHIND 开关的 perfetto 对照（client composition 范围） | blur 放大器 | 4 |

实验环境：优先本仓 out/ 构建的模拟器镜像或任意 A13 真机；E8~E10 需要可安装 demo 的设备。每个实验写三段记录：预期（从文章抄机制）→ 实测（命令+原始输出）→ 裁定（一致/不一致及解释）。**不一致时先怀疑自己的实验设计，再用审计篇四条纪律复查文章**——这个流程本身就是毕业训练。

## 6. 常见失败模式（前人遇到过的问题）

1. **只读不测**：读完感觉全懂，第一个线上问题就露馅。对策：每阶段的"过关"都必须包含动手项。
2. **背行号不背结构**：行号会随版本漂移，结构（声明与像素两组状态、期望可见与实际可见的分离、合成列表三个过滤条件、T0~T3）不会。闪卡正面写现象、背面写结构，file:line 只做佐证。
3. **版本混读**：拿 A8 的博客解释 A13 的行为（附录 A 的八条差异每条都对应一类网上错误文章）。
4. **只在 Java 层打转**：本域一半真相在 native（Output.cpp/Layer.cpp/BLASTBufferQueue.cpp）。阶段 3 是硬门槛，绕不过去。
5. **把批注当源码**：本仓批注是学习导览，个别批注经过修正仍有历史痕迹——引用前先看原始代码行，这正是审计篇纪律一的日常化。

## 7. 进阶方向（毕业之后）

- **Shell Transitions 深潜**：打开 persist.wm.debug.shell_transit，对照 Transition.java/WMShell Transitions.java/DefaultTransitionHandler 重走阶段 2 的全部实验，总结两套转场体系的差异成文。
- **A14/A15 差异追踪**：用本仓 A14 移植台账（knowledge-base/android/A14学习批注移植台账.md）对照两篇文章逐节标出演进（ActivityInfoBase 拆分、Transition 全面接管等）。
- **HWC/vendor 侧**：阶段 3 止步于 validateDisplay 边界；有条件时读设备 vendor composer HAL，补上"平面上限"这块 AOSP 不可见的拼图。
- **横向联通**：本域与输入（一次触摸的旅程.md：窗口可见性如何影响触摸分发）、音频（焦点与生命周期的联动）、电源（doze 下动画缩放）各有一条接缝，每条接缝写一篇短文即是一次毕业级练习。

## 8. 时间表总览

| 周次 | 阶段 | 里程碑 |
|---|---|---|
| 1-2 | 阶段 0 | 三进程分工口述过关 |
| 3-5 | 阶段 1 | E1/E4/E5 完成，relayout 往返图白纸过关 |
| 6-9 | 阶段 2 | E8/E9/E10 完成，T0~T3 时序图白纸过关 |
| 10-13 | 阶段 3 | E7/E12 完成，黑色像素推导独立成文 |
| 14-15 | 阶段 4 | 《测量手册实测版》交稿 |
| 16-18 | 阶段 5 | 毕业项目通过质询 |

每周节奏建议：源码精读 40% ＋ 实验 40% ＋ 闪卡/输出 20%。任何一周实验没做完，砍阅读保实验。
