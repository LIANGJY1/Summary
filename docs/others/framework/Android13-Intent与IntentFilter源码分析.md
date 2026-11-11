# Android13 Intent 与 IntentFilter 源码分析

> 一个 Intent 从构造、跨进程、解析、匹配到被裁决的完整一生：字段级解剖、action/category/data 的匹配语义、PMS 解析链五关，以及雅迪双实例事故里「意图形状」这条根因链的机制归宿。姊妹篇：《Android13-Launcher启动、LaunchMode与Task源码分析》（同目录）。

## 目录

- ch0 导读：材料、口径与主线图
- ch1 字段解剖：一个 Intent 的十六个口袋
- ch2 action：入口语义与空值豁免（收口一：Monkey 的 MAIN 为何不等于 HOME 意图）
- ch3 category：子集匹配与 DEFAULT 闸门（收口二：差一个 category 如何翻转类型闸）
- ch4 data 与 type：互斥设值与四段数据
- ch5 IntentFilter 与 match()：三段裁决与优先级
- ch6 解析链：从 resolveActivity 到 buildResolveList 五关（收口三：B 的 cmp= 从何而来）
- ch7 身份判定：filterEquals、equals 与同形状（收口四：filterEquals 为何救不了 A/B）
- ch8 Parcelable 序列化：跨进程形状不变的证据
- ch9 flags 差集与 selector/ClipData/extras 备注
- ch10 事故意图形状总演算：逐字段对照与五关代入

---

## 0. 导读：材料、口径与主线图

### 0.1 本篇定位与三材料

本篇是 Intent 域的深剖篇：把「意图」这件组件通信中枢消息从字段到匹配器到解析链全部拆开。它与姊妹篇的分工是：**姊妹篇讲「一个启动请求如何被裁决进任务栈」，本篇讲「裁决所依据的那张意图形状清单本身」**。姊妹篇第 2、4、8 章多处引用意图形状判定（isHomeIntent 五关、resolveActivity 回写、filterEquals），机制的完整推导在本篇，姊妹篇保留应用位并互指本章。

**材料一：学习树（主证据源）。** 路径 /home/liang/Project/MyProject/AAOS13_study，HEAD 为 cb9d30f9e。文中所有相对链接（形如 `../../../../AAOS13_study/frameworks/base/...#L 行号`）与不带树名标注的行号，一律指这棵树的 frameworks/base。它是 A13 原生代码加上此前批注战役留下的中文注释；**引文一律剔除批注行**，机制与 AOSP android-13 一致。

**材料二：雅迪树（对照源）。** 路径 /home/liang/Project/Reachauto/YaDi/yadi_android，frameworks/base HEAD 为 8302534c，与事故构建 476 的对应关系未核实。本篇开工前对 Intent.java 与 IntentFilter.java 做过逐行 diff：除批注行外实质差异仅两处（0.3 节末列明），其余机制两树一致【源码事实·两树对照】。

**材料三：事故日志（事实源）。** 文件为 /home/liang/Downloads/log/logcat_2026_11_03_05_43_01_557-103.txt，与姊妹篇 §8 同源；本篇引用日志一律给「行号 + 原文」，完整时间线回指姊妹篇 §8.1。

### 0.2 引文约定与事实标记

代码引文保留原始缩进与注释（AOSP 英文注释），剔除学习树批注战役添加的中文注释行；过长处用独立一行的 `...` 省略。事实标记四级：

| 标记 | 含义 |
| --- | --- |
| 【源码事实】 | 引文可直接支撑的陈述 |
| 【源码事实·两树对照】 | 经学习树与雅迪树 diff 或双读支撑的陈述 |
| 【源码事实推演】 | 由引文规则代入具体输入推出的必然路径 |
| 【日志事实】 | 事故日志原文可直接支撑的陈述 |
| 【未核实】 | 材料不足、需实机或额外证据的陈述 |

机制讲解统一回答四问：**规则**是什么、**设置者**是谁、**执行**在哪一行、**后果**是什么。

### 0.3 主线图：一个 Intent 的一生

```text
应用进程构造                     system_server                        应用进程
Intent(action, categories,  ...)
   |  setAction/addCategory/setData...（ch1）
   |  writeToParcel 字段定序（ch8）
   +---------- Binder ---------->  ActivityManagerService
                                      |  resolveActivity：隐式转显式回写（ch6）
                                      |  isHomeIntent/isMainIntent 形状判定（ch2/ch3）
                                      |  PMS 解析链：buildResolveList 五关（ch6）
                                      |    IntentFilter.match 三段（ch5）
                                      |  WM 裁决：类型闸/任务选择（姊妹篇 ch3/ch4）
                                      +---------- ClientTransaction ---------->
                                                                                  onCreate 收到同一个 Intent
```

三个要点先立在这里，后文逐一证明：其一，**Intent 跨进程后形状不变**（ch8 的字段定序是证据），所以服务端看到的就是应用构造的那张清单；其二，**匹配是「意图清单 × 过滤器清单」的集合运算**，action 是存在性检查、category 是子集检查、data/type 是分段检查（ch5）；其三，**「隐式启动只认带 DEFAULT 的过滤器」不是 match() 的规则，而是解析链查询侧的闸门**（ch6，IntentResolver.java:907）——这条与 category 子集规则合起来，才是「DEFAULT 必须写进 manifest」的完整因果。

**跨树对照结论**【源码事实·两树对照】：Intent.java 与 IntentFilter.java 的逐行 diff 中，非注释差异仅四处。雅迪树两处偏新：toUri 写 scheme 段时做 `Uri.encode(scheme)`（学习树 Intent.java:11872 为裸 append），parseUri 解析键值对时多一道 `semi < 0` 守卫（雅迪树 Intent.java:7605-7607，缺 `end` 段时抛 URISyntaxException 而非越界）。学习树两处偏新：多 `EXTRA_CHOOSER_CUSTOM_ACTIONS`、`EXTRA_CHOOSER_PAYLOAD_RESELECTION_ACTION` 两个常量与 ChooserAction 导入，IntentFilter 多 `checkDataPathAndSchemeSpecificParts` 方法。本篇机制陈述对两树同时成立，不再逐处标注。

---

## 1. 字段解剖：一个 Intent 的十六个口袋

### 1.1 字段全景

[Intent.java#L7327](../../../../AAOS13_study/frameworks/base/core/java/android/content/Intent.java#L7327)

```java
private String mAction;
private Uri mData;
private String mType;
private String mIdentifier;
private String mPackage;
private ComponentName mComponent;
private int mFlags;
/** Set of in-process flags which are never parceled */
private int mLocalFlags;
private ArraySet<String> mCategories;
@UnsupportedAppUsage
private Bundle mExtras;
private Rect mSourceBounds;
private Intent mSelector;
private ClipData mClipData;
private int mContentUserHint = UserHandle.USER_CURRENT;
/** Token to track instant app launches. Local only; do not copy cross-process. */
private String mLaunchToken;
private Intent mOriginalIntent; // Used for the experimental "component alias" feature.
```

十六个字段按职责分四组：

| 组 | 字段 | 跨进程 | 职责 |
| --- | --- | --- | --- |
| 寻址 | mComponent、mPackage | 是 | 显式指名：直接点组件或限包 |
| 匹配清单 | mAction、mCategories、mData、mType、mIdentifier | 是 | 隐式寻址：交给过滤器比对的形状 |
| 载荷与授权 | mExtras、mClipData、mFlags | 是 | 数据搬运与 URI 授权、启动语义 |
| 辅助与本地 | mSourceBounds、mSelector、mContentUserHint、mOriginalIntent、mLocalFlags、mLaunchToken | 前四个是，后两个否 | 来源矩形、选择器改道、用户_hint_、别名链、进程内标志 |

注意第二个数据字段的名字是 **mData** 而不是 mUri：getter 叫 `getData()`，序列化按 Uri 走（ch8）。`mIdentifier` 是 opaque 字符串标识（参与 filterEquals，ch7），日常启动链几乎不用。

### 1.2 mData 与 mType：互斥设值器

[Intent.java#L9821](../../../../AAOS13_study/frameworks/base/core/java/android/content/Intent.java#L9821)

```java
public @NonNull Intent setData(@Nullable Uri data) {
    mData = data;
    mType = null;
    return this;
}
```

[Intent.java#L9882](../../../../AAOS13_study/frameworks/base/core/java/android/content/Intent.java#L9882)

```java
public @NonNull Intent setType(@Nullable String type) {
    mData = null;
    mType = type;
    return this;
}
```

[Intent.java#L9945](../../../../AAOS13_study/frameworks/base/core/java/android/content/Intent.java#L9945)

```java
public @NonNull Intent setDataAndType(@Nullable Uri data, @Nullable String type) {
    mData = data;
    mType = type;
    return this;
}
```

四问走一遍。**规则**：`setData` 清 type、`setType` 清 data、只有 `setDataAndType` 双留——三个设值器里前两个互清，第三个是唯一的例外出口。**设置者**：应用代码（或框架代设，如姊妹篇 ch6 的 resolve 回写只碰 mComponent，不碰这两字段）。**执行**：上面三处赋值体，各两行。**后果**：先 `setType("image/*")` 再 `setData(uri)` 的调用顺序会把 type 悄悄清掉——这是 Intent API 里最经典的顺序坑；想双设必须用 `setDataAndType`，而它的 javadoc 明言「should very rarely be used」，因为它绕过了「type 由 data 推断」的默认语义（推断机制见 4.2 节 resolveType）。

两个归一化变体顺带记入：`setDataAndNormalize`（Intent.java:9851）先 `normalizeScheme()` 再 setData，`setTypeAndNormalize`（Intent.java:9914）先 `normalizeMimeType()` 再 setType。它们存在的原因是框架的 scheme 与 MIME 匹配**大小写敏感**（setData 的 javadoc：「scheme matching in the Android framework is case-sensitive, unlike the formal RFC」）——写大写 scheme 的意图会匹配不上小写声明的过滤器【源码事实】。

### 1.3 mCategories：懒建 ArraySet、intern 驻留、空即置 null

[Intent.java#L10029](../../../../AAOS13_study/frameworks/base/core/java/android/content/Intent.java#L10029)

```java
public @NonNull Intent addCategory(String category) {
    if (mCategories == null) {
        mCategories = new ArraySet<String>();
    }
    mCategories.add(category.intern());
    return this;
}
```

[Intent.java#L10046](../../../../AAOS13_study/frameworks/base/core/java/android/content/Intent.java#L10046)

```java
public void removeCategory(String category) {
    if (mCategories != null) {
        mCategories.remove(category);
        if (mCategories.size() == 0) {
            mCategories = null;
        }
    }
}
```

[Intent.java#L8760](../../../../AAOS13_study/frameworks/base/core/java/android/content/Intent.java#L8760)

```java
public boolean hasCategory(String category) {
    return mCategories != null && mCategories.contains(category);
}
```

[Intent.java#L8775](../../../../AAOS13_study/frameworks/base/core/java/android/content/Intent.java#L8775)

```java
public Set<String> getCategories() {
    return mCategories;
}
```

四个细节都有下游后果。其一，**空集合与 null 同态**：removeCategory 把集合清回 null，于是「无 category」在序列化（ch8 写 0）、匹配（ch5 matchCategories 对 null 直接放行）、形状判定（ch2 isHomeIntent 的 `getCategories().size() == 1` 对 null 会 NPE——所以五关里它排在 hasCategory 之后短路）三处都按 null 分支走。其二，**getCategories 返回原引用且可能为 null**：javadoc 明言「Do not modify!」，调用方拿到 null 要自己兜。其三，**intern() 驻留**：addCategory 与 setAction 一样把字符串入常量池，跨实例同名字符串共享同一对象——匹配侧的集合比较因此既省内存又快（ArraySet 的 contains 走 equals，intern 只是把 equals 的常见情形变成同引用）。其四，**ArraySet 而非 HashSet**：保插入序、小集合内存紧凑；categories 的语义是「清单」不是「哈希桶」，顺序在 toUri 往返（1.9 节）里可见。

### 1.4 mAction：intern 与可空

[Intent.java#L9793](../../../../AAOS13_study/frameworks/base/core/java/android/content/Intent.java#L9793)

```java
public @NonNull Intent setAction(@Nullable String action) {
    mAction = action != null ? action.intern() : null;
    return this;
}
```

action 是单值字符串、可空。可空不是疏漏：ch5 会看到 match() 对「意图 action 为 null」直接豁免 action 闸（IntentFilter.java:1978 的 `action != null &&` 前缀），空 action 的意图只靠 category/data 匹配——这是「纯数据意图」的合法形态【源码事实】。

### 1.5 不过进程的两个字段

字段注释自证身份：`mLocalFlags` 是「Set of in-process flags which are never parceled」，`mLaunchToken` 是「Local only; do not copy cross-process」。ch8 的 writeToParcel 引文里两者确实缺席。mLocalFlags 的唯一已知写入点在反序列化构造函数：`Intent(Parcel in)` 打 `LOCAL_FLAG_FROM_PARCEL` 标记（Intent.java:12007），供后续「来自远进程的不安全启动」检测识别出身——**序列化不留痕，反序列化补痕**，方向相反却各尽其职【源码事实】。

### 1.6 mSelector：匹配改道而不改身份

setSelector 的 javadoc 把语义说尽（[Intent.java#L10056](../../../../AAOS13_study/frameworks/base/core/java/android/content/Intent.java#L10056) 起）：

```java
* This is a modification to the kinds of
* things the Intent will match.  If the selector is set, it will be used
* when trying to find entities that can handle the Intent, instead of the
* main contents of the Intent.  This allows you build an Intent containing
* a generic protocol while targeting it more specifically.
...
* <p>Setting a selector does not impact the behavior of
* {@link #filterEquals(Intent)} and {@link #filterHashCode()}.  This is part of the
* desired behavior of a selector -- it does not impact the base meaning
```

两句话两个性质：**匹配时选择器顶替本体**（解析链先拿 selector 去查候选，ch6 会见到 ResolveIntentHelper 对 selector 的特处理），**身份判定不看选择器**（filterEquals/filterHashCode 忽略它，ch7）。javadoc 给的典型用例是 CATEGORY_APP_BROWSER：本体建 MAIN 意图、选择器带「浏览器应用」类别，既定向到浏览器活动又不破坏「该活动的主入口身份」。车机日常链几乎不用 selector，事故链也不涉及，本篇备注到此【源码事实】。

### 1.7 其余字段一句话各归其位

- **mExtras**：Bundle 载荷，序列化最后写（ch8）；匹配与身份判定一律不看它——「带不同 extra 的同一意图」在 filterEquals 眼里是同一个意图（ch7 的后果）。
- **mClipData**：随意图搬运的剪贴数据，URI 授权递归遍历它（FLAG_GRANT_* 的 javadoc：「all URIs as well as recursive traversals through data or other ClipData in Intent items will be granted」，ch9）。
- **mSourceBounds**：来源矩形（点击坐标域），启动动画取用；序列化带哨兵位（ch8）。
- **mContentUserHint**：跨用户_hint_，默认 USER_CURRENT；序列化定序倒数第二（ch8）。
- **mIdentifier**：opaque 标识串，参与 filterEquals 与 filterHashCode（ch7），启动链不设。
- **mOriginalIntent**：组件别名实验特性的上一层意图链，@hide，备注级。

### 1.8 构造与链式 API

[Intent.java#L7463](../../../../AAOS13_study/frameworks/base/core/java/android/content/Intent.java#L7463)

```java
public Intent(String action) {
    setAction(action);
}
```

构造函数族只是设值器的薄封装：`Intent()` 全空、`Intent(String action)` 只设 action、`Intent(String action, Uri uri)` 设 action 与 data（注意走 setData，会清 type）、`Intent(String action, Uri uri, String type)` 用 setDataAndType 双留。所有设值器返回 this，于是 `new Intent(ACTION_VIEW).setData(uri).addCategory(CATEGORY_BROWSABLE).setFlags(...)` 一句成链——事故里 Monkey 与 goLauncher 的意图就是这种链式构造的产物（ch10 对照表）。

### 1.9 parseUri/toUri：字符串往返与两树差异

toUri 把意图写成 `intent:` 或 `android-app:` 字符串，内核是 toUriInner 的键值串（[Intent.java#L11869](../../../../AAOS13_study/frameworks/base/core/java/android/content/Intent.java#L11869) 起）：

```java
private void toUriInner(StringBuilder uri, String scheme, String defAction,
        String defPackage, int flags) {
    if (scheme != null) {
        uri.append("scheme=").append(scheme).append(';');
    }
    if (mAction != null && !mAction.equals(defAction)) {
        uri.append("action=").append(Uri.encode(mAction)).append(';');
    }
    ...
```

scheme、action、categories、type、flags、component 逐项编码，与 defAction/defPackage 相同的值省略——往返靠 parseUri（Intent.java:7664）按同样的键序解回。**这串字符串不是匹配语义的一部分**，它是意图的文本载体（快捷方式、调试 dump、跨设备分享都用它），但它的解析安全值得一句：URI_ALLOW_UNSAFE 的 javadoc 警告「If you do not trust the source of the URI being parsed ... when using it to start an activity you should usually add in CATEGORY_BROWSABLE to limit the activities that can handle it」（Intent.java:7318-7321）——**不可信字符串解析出的意图要用 BROWSABLE 收口**，这是 category 的安全语义第一次露面（3.4 节再展开）【源码事实】。

【源码事实·两树对照】往返实现两树有两处实质差异：雅迪树 toUri 写 scheme 段为 `uri.append("scheme=").append(Uri.encode(scheme)).append(';')`（学习树 Intent.java:11872 裸 append 未编码），且雅迪树 parseUri 在切键值对时多一道守卫：

```java
int semi = uri.indexOf(';', i);
if (semi < 0) {
    throw new URISyntaxException(uri, "uri end not found");
}
String value = eq < semi ? Uri.decode(uri.substring(eq + 1, semi)) : "";
```

（雅迪树 Intent.java:7604-7608。）学习树缺这两处时，畸形字符串（缺 `end` 段、scheme 含保留字符）的往返行为更宽松；事故链不经过 toUri/parseUri，此差异与双实例无关，仅记入跨树账【源码事实·两树对照】。

### 1.10 parseIntent：manifest 里的 `<intent>` 元素

parseIntent（[Intent.java#L12070](../../../../AAOS13_study/frameworks/base/core/java/android/content/Intent.java#L12070)）从 XML 的 `<intent>` 标签膨胀出 Intent：读 action/data/type/ident/component 属性，再遍历子标签吸收 `<category>` 与 `<extra>`。它与 IntentFilter 的 manifest 膨胀是镜像操作（后者在 PMS 扫包时把 `<intent-filter>` 变成 IntentFilter 对象，ch5/ ch6）；启动链上它服务于快捷方式、preferred activities 等 XML 声明场景，备注级【源码事实】。

### 1.11 本章小结

- 十六字段四组：寻址（component/package）、匹配清单（action/categories/data/type/identifier）、载荷授权（extras/clipData/flags）、辅助与本地（bounds/selector/userHint/originalIntent/localFlags/launchToken）。
- 互斥设值器是 data/type 的第一纪律：setData 清 type、setType 清 data、setDataAndType 是唯一双留出口。
- categories 空即 null、intern 驻留、原引用外露——三个实现细节分别喂养序列化、匹配性能与形状判定。
- mLocalFlags/mLaunchToken 不过进程；反序列化构造函数补打 LOCAL_FLAG_FROM_PARCEL 出身标记。
- toUri/parseUri 是文本载体不是匹配语义；两树在 scheme 编码与 semi 守卫上有实质差异。

---

## 2. action：入口语义与空值豁免

### 2.1 ACTION_MAIN：不期待数据的入口

[Intent.java#L669](../../../../AAOS13_study/frameworks/base/core/java/android/content/Intent.java#L669)

```java
/**
 *  Activity Action: Start as a main entry point, does not expect to
 *  receive data.
 *  <p>Input: nothing
 *  <p>Output: nothing
 */
@SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
public static final String ACTION_MAIN = "android.intent.action.MAIN";
```

javadoc 三行把 MAIN 的契约写死：**入口、无输入、无输出**。它不携带 data、不期待 type——这不是风格约定而是匹配事实：带 data 的 MAIN 意图在过滤器侧会撞 matchData 的 EMPTY 分支（ch5：过滤器无 data 声明时要求意图 type 与 data 双 null）。对照 ACTION_VIEW 的 javadoc（Intent.java:678-684）：「Display the data to the user ... the generic action you can use on a piece of data」——VIEW 是「对数据做最合理的事」，MAIN 是「不带数据地进门」。两者构成 action 语义的两极：**数据驱动**与**入口驱动**【源码事实】。

### 2.2 action 必须带命名空间

[Intent.java#L7467](../../../../AAOS13_study/frameworks/base/core/java/android/content/Intent.java#L7467)

```java
 * Create an intent with a given action and for a given data url.  Note
 * the action <em>must</em> be in a namespace because Intents are
 * used globally in the system -- for example the system VIEW action is
 * android.intent.action.VIEW; an application's custom action would be
 * something like com.google.app.myapp.CUSTOM_ACTION.
```

action 是全局扁平命名空间里的字符串，没有层级路由——冲突靠命名空间前缀规避。系统常量占 `android.intent.action.*`，应用自定义动作应带包名前缀。这条约定没有强制力（setAction 不校验），强制力在匹配侧：过滤器声明什么 action 就只接什么 action（2.4 节）【源码事实】。

### 2.3 action 闸与空值豁免

match() 的第一道闸（[IntentFilter.java#L1975](../../../../AAOS13_study/frameworks/base/core/java/android/content/IntentFilter.java#L1975) 起）：

```java
public final int match(String action, String type, String scheme,
        Uri data, Set<String> categories, String logTag, boolean supportWildcards,
        @Nullable Collection<String> ignoreActions) {
    if (action != null && !matchAction(action, supportWildcards, ignoreActions)) {
        if (false) Log.v(
            logTag, "No matching action " + action + " for " + this);
        return NO_MATCH_ACTION;
    }
    ...
```

matchAction 的私有实现收尾在存在性检查（[IntentFilter.java#L830](../../../../AAOS13_study/frameworks/base/core/java/android/content/IntentFilter.java#L830) 起）：

```java
private boolean matchAction(String action, boolean wildcardSupported,
        @Nullable Collection<String> ignoreActions) {
    if (wildcardSupported && WILDCARD.equals(action)) {
        if (ignoreActions == null) {
            return !mActions.isEmpty();
        }
        ...
    }
    if (ignoreActions != null && ignoreActions.contains(action)) {
        return false;
    }
    return hasAction(action);
}
```

四问走一遍。**规则**：意图 action 非空时，必须被过滤器的 action 列表包含（存在性检查）；意图 action 为 null 时整道闸豁免。**设置者**：意图侧 setAction、过滤器侧 manifest `<action>` 标签（PMS 扫包时 addAction）。**执行**：IntentFilter.java:1978 的 `action != null &&` 前缀与 :846 的 `return hasAction(action)`。**后果**有两条反直觉推论【源码事实推演】：其一，**不声明任何 action 的过滤器只接空 action 意图**——mActions 为空时 hasAction 恒假，一切非空 action 意图吃 NO_MATCH_ACTION；其二，**空 action 意图对任何过滤器都过 action 闸**，它的命运完全交给 category 与 data 两段——「纯数据意图」因此合法存在。通配符分支（`WILDCARD`）仅系统内部查询启用（supportWildcards 由 @hide 重载传入），应用侧匹配永远走精确存在性【源码事实】。

### 2.4 三个维度的检查形态对照

| 维度 | 意图侧字段 | 过滤器侧字段 | 检查形态 | 空意图侧语义 |
| --- | --- | --- | --- | --- |
| action | mAction（单值） | mActions（列表） | 存在性：列表包含意图值 | 豁免整闸 |
| category | mCategories（集合） | mCategories（列表） | 子集：意图集合 ⊆ 过滤器集合 | 直接过闸 |
| data/type | mData/mType | schemes/ssp/authorities/paths/types | 分段：scheme→ssp→authority→path→type | 见 ch4/ch5 |

action 与 category 的方向相反值得钉死：**action 是「过滤器里有没有我要的」，category 是「我要的是不是都在过滤器里」**。前者过滤器做主（声明即能力清单），后者意图做主（携带即要求清单）——addCategory 的 javadoc 原话（3.1 节引）把这套相反语义称为「the semantics is the opposite of actions」【源码事实】。

### 2.5 收口一：Monkey 的 MAIN 为何不等于 HOME 意图

事故里两次启动的 action 完全相同，日志原文为证【日志事实】：

```text
11-03 05:44:06.310 12877 12944 I ActivityTaskManager: START u0 {act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER] flg=0x10200000 cmp=com.yadea.launcher/.function.main.view.MainActivity} from uid 0, pid 13116
11-03 05:44:10.750 12877 13081 I ActivityTaskManager: START u0 {act=android.intent.action.MAIN cat=[android.intent.category.HOME] flg=0x10000000 cmp=com.yadea.launcher/.function.main.view.MainActivity} from uid 1000, pid 13232
```

（日志 106587 行与 122044 行；前者 Monkey 首启 A，后者 SystemUI goLauncher 启 B；完整时间线见姊妹篇 §8.1。）两行 act= 都是 `android.intent.action.MAIN`，cmp= 都是同一个 MainActivity——**action 维度与组件维度在这两次启动之间没有任何区分力**。服务端区分「这是回桌面还是点图标」靠的是形状判定双生子（[ActivityRecord.java#L2953](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ActivityRecord.java#L2953)）：

```java
private boolean isHomeIntent(Intent intent) {
    return ACTION_MAIN.equals(intent.getAction())
            && (intent.hasCategory(CATEGORY_HOME)
            || intent.hasCategory(CATEGORY_SECONDARY_HOME))
            && intent.getCategories().size() == 1
            && intent.getData() == null
            && intent.getType() == null;
}

// isMainIntent：判主入口意图。
static boolean isMainIntent(Intent intent) {
    return ACTION_MAIN.equals(intent.getAction())
            && intent.hasCategory(CATEGORY_LAUNCHER)
            && intent.getCategories().size() == 1
            && intent.getData() == null
            && intent.getType() == null;
}
```

代入两次启动【源码事实推演】：106587 行意图 categories=[LAUNCHER]，isHomeIntent 第二关 hasCategory(HOME) 即假 → 非 HOME 意图；isMainIntent 五关全过 → 是主入口意图。122044 行意图 categories=[HOME]，镜像结论。**区分力全部来自 category 集合**，而 `size() == 1` 那道闸保证两种形状互斥：谁想同时带 [HOME, LAUNCHER] 冒充两边，两个判定都会拒收。action 相同的表象下，category 是唯一的话事人——这就把问题完整移交给了下一章。

---

## 3. category：子集匹配与 DEFAULT 闸门

### 3.1 子集匹配：意图携带即要求

addCategory 的 javadoc 是 category 语义的宪法（[IntentFilter.java#L1783](../../../../AAOS13_study/frameworks/base/core/java/android/content/IntentFilter.java#L1783) 起）：

```java
/**
 * Add a new Intent category to match against.  The semantics of
 * categories is the opposite of actions -- an Intent includes the
 * categories that it requires, all of which must be included in the
 * filter in order to match.  In other words, adding a category to the
 * filter has no impact on matching unless that category is specified in the
 * intent.
 * ...
```

执行体是 matchCategories（[IntentFilter.java#L1851](../../../../AAOS13_study/frameworks/base/core/java/android/content/IntentFilter.java#L1851)）：

```java
public final String matchCategories(Set<String> categories) {
    if (categories == null) {
        return null;
    }

    Iterator<String> it = categories.iterator();

    if (mCategories == null) {
        return it.hasNext() ? it.next() : null;
    }

    while (it.hasNext()) {
        final String category = it.next();
        if (!mCategories.contains(category)) {
            return category;
        }
    }

    return null;
}
```

四问走一遍。**规则**：意图 categories 必须是过滤器 categories 的子集；返回值是「第一个不匹配的 category」或 null（全匹配）；返回值非 null 即 NO_MATCH_CATEGORY。**设置者**：意图侧 addCategory、过滤器侧 manifest `<category>` 标签。**执行**：上面这段遍历，逐元素 contains。**后果**三条【源码事实推演】：其一，**意图无 category（null）对任何过滤器过闸**——首分支直接返 null；其二，**过滤器无 category 时只接无 category 意图**——mCategories 为 null 而意图有类别，返回意图的第一个 category 即判负；其三，**给过滤器加 category 不缩小它的既有匹配面**（子集检查只看意图侧要什么），加 category 的真正作用是「接住携带该 category 的意图」与——下一节的查询侧闸门。

### 3.2 DEFAULT 闸门：规则不在 match() 里

CATEGORY_DEFAULT 的 javadoc 先泼冷水（[Intent.java#L5109](../../../../AAOS13_study/frameworks/base/core/java/android/content/Intent.java#L5109) 起）：

```java
/**
 * Set if the activity should be an option for the default action
 * (center press) to perform on a piece of data.  Setting this will
 * hide from the user any activities without it set when performing an
 * action on some data.  Note that this is normally -not- set in the
 * Intent when initiating an action -- it is for use in intent filters
 * specified in packages.
 */
@SdkConstant(SdkConstantType.INTENT_CATEGORY)
public static final String CATEGORY_DEFAULT = "android.intent.category.DEFAULT";
```

「normally -not- set in the Intent」——DEFAULT 是**过滤器侧**的类别，意图侧通常不带。那「隐式 startActivity 必须命中带 DEFAULT 的过滤器」这条人人会背的规则从哪来？答案在解析链查询侧的闸门（[IntentResolver.java#L902](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/IntentResolver.java#L902) 起）：

```java
match = intentFilter.match(action, resolvedType, scheme, data, categories, TAG);
if (match >= 0) {
    if (debug) Slog.v(TAG, "  Filter matched!  match=0x"
            + Integer.toHexString(match) + " hasDefault="
            + intentFilter.hasCategory(Intent.CATEGORY_DEFAULT));
    if (!defaultOnly || intentFilter.hasCategory(Intent.CATEGORY_DEFAULT)) {
        final R oneResult = newResult(computer, filter, match, userId, customFlags);
        ...
    } else {
        hasNonDefaults = true;
    }
}
```

以及调试态的自白（IntentResolver.java:936-941）：`resolveIntent failed: found match, but none with CATEGORY_DEFAULT`。defaultOnly 参数的来源之一正是 Intent 自己的解析入口（[Intent.java#L9694](../../../../AAOS13_study/frameworks/base/core/java/android/content/Intent.java#L9694)）：

```java
ResolveInfo info = pm.resolveActivity(
    this, PackageManager.MATCH_DEFAULT_ONLY);
```

四问走一遍。**规则**：defaultOnly 为真时，match 通过的过滤器还须 `hasCategory(CATEGORY_DEFAULT)` 才入结果列。**设置者**：调用方的 flags——`Intent.resolveActivity/resolveActivityInfo` 硬编码 MATCH_DEFAULT_ONLY；queryIntentActivities 等查询 API 由调用方自决。**执行**：IntentResolver.java:907 一行条件。**后果**：manifest 过滤器漏写 DEFAULT 的活动**隐式启动不可达**（match 全过却被查询闸丢弃），而显式 component 启动完全不受影响（显式不走解析链，ch6）；调试日志里「found match, but none with CATEGORY_DEFAULT」就是这条闸的指纹【源码事实】。注意 match() 本体对 DEFAULT 一无所知——把它当普通 category 做子集检查；**「DEFAULT 规则」是查询策略，不是匹配语义**，这是本章最容易被讲错的一句【源码事实推演】。

### 3.3 类别常量语义表与车机落位

| 常量 | 行号 | 语义要点（javadoc） |
| --- | --- | --- |
| CATEGORY_DEFAULT | Intent.java:5118 | 过滤器侧类别；隐式解析查询闸的钥匙（3.2 节） |
| CATEGORY_BROWSABLE | Intent.java:5130 | 浏览器可安全唤起的安全承诺（3.4 节） |
| CATEGORY_LAUNCHER | Intent.java:5180 | 「Should be displayed in the top-level launcher」：桌面图标清单的入场券 |
| CATEGORY_CAR_LAUNCHER | Intent.java:5194 | @hide；车机 Launcher 的首选入口，缺省时 Car launcher 可回退 LAUNCHER 或排除该应用 |
| CATEGORY_HOME | Intent.java:5219 | 「the first activity that is displayed when the device boots」：开机首屏 |
| CATEGORY_HOME_MAIN | Intent.java:5226 | @hide；setup 完成后就绪态的 home |
| CATEGORY_SECONDARY_HOME | Intent.java:5231 | 副屏 home（多屏车机的副驾屏桌面走它） |
| CATEGORY_INFO | Intent.java:5213 | 包信息页；无 LAUNCHER 时的「前门」 |
| CATEGORY_LEANBACK_LAUNCHER | Intent.java:5186 | Leanback（电视）桌面入口 |
| CATEGORY_VOICE | Intent.java:5138 | 语音交互活动，须能无 UI 运行 |

车机落位【源码事实·应用树】：雅迪 Launcher 的 MainActivity 过滤器（application/Launcher/src/main/AndroidManifest.xml:89-95）是一个 priority=1000 的单 filter，内含 MAIN action 与 HOME、LAUNCHER、DEFAULT 三个 category——**没有 CAR_LAUNCHER**，于是 AAOS 侧 CarLauncher 若在场会走 LAUNCHER 回退路径（javadoc 明示的 fallback）；雅迪实机桌面入口由 SystemUI 的 goLauncher 直接构造 HOME 形状意图承担（2.5 节日志 122044 行），不经过任何 launcher 的图标查询。这一个 filter 同时接住 HOME 形状与 LAUNCHER 形状两种意图（子集检查两边都过、data/type 双 null 过 EMPTY 分支）——**过滤器层面两种形状毫无区别，区别只发生在 WM 的形状判定与类型闸**（3.5 节）【源码事实推演】。

### 3.4 BROWSABLE：category 的安全语义

[Intent.java#L5119](../../../../AAOS13_study/frameworks/base/core/java/android/content/Intent.java#L5119)

```java
/**
 * Activities that can be safely invoked from a browser must support this
 * category.  For example, if the user is viewing a web page or an e-mail
 * and clicks on a link in the text, the Intent generated execute that
 * link will require the BROWSABLE category, so that only activities
 * supporting this category will be considered as possible actions.  By
 * supporting this category, you are promising that there is nothing
 * damaging (without user intervention) that can happen by invoking any
 * matching Intent.
 */
```

category 子集语义在这里变成安全机制：**发起方给意图加一个 category，就等于给候选集合加一道白名单**——浏览器链接意图带 BROWSABLE，未声明 BROWSABLE 的活动连候选都进不了（子集检查判负）。javadoc 把声明 BROWSABLE 定义为一种承诺（「you are promising that there is nothing damaging」）。它与 1.9 节 parseUri 的警告闭环：不可信字符串解析出的意图要补 BROWSABLE 收口，正是借用这道白名单【源码事实】。

### 3.5 收口二：差一个 category 如何翻转类型闸

把 2.5 节的两次启动代入 category 机制全链【源码事实推演，日志行号见 §2.5 引文】：

1. **过滤器侧**：两种形状的 categories（[LAUNCHER] 与 [HOME]）都是该单 filter 类别集合 {HOME, LAUNCHER, DEFAULT} 的子集，matchCategories 双过；match 三段全过，**PMS 解析回写同一个 cmp**（ch6）。
2. **WM 形状判定**：isHomeIntent 对 [LAUNCHER] 返假、对 [HOME] 返真（2.5 节代入表）。
3. **记录类型**：HOME 形状的记录在构造时定型 ACTIVITY_TYPE_HOME；非 HOME 记录匹配期恒为 UNDEFINED、入栈才补 STANDARD（姊妹篇核心结论一，ActivityRecord 构造与 onDescendantActivityAdded 两处）。
4. **findTask 类型闸**：B 的 HOME 请求遍历任务树时，`isCompatibleActivityType(HOME, STANDARD)` 为假，装着 A 的 Task 2 被直接跳过（姊妹篇 §4.5 矩阵：HOME 行 STANDARD 列打叉）。
5. **结局**：B 在 home 根任务下新建 Task 3，A 留在 Task 2——同组件、同 filter、同 launchMode 的两个实例并存。

整条链上唯一的输入差异是**意图 categories 集合里那一个字符串**：LAUNCHER 或 HOME。action 相同、cmp 相同、flags 差异（0x10200000 对 0x10000000）不参与任何一道形状闸——flags 只影响任务裁决（姊妹篇 ch3），不影响类型定型。category 的子集语义本身完全无辜（两种形状都合法匹配）；翻转发生在「category 集合被 WM 当作类型证据」这一步：**匹配语义与类型语义共用同一份 category 清单，却按两套规则消费它**——这是雅迪双实例事故在 Intent 域的最终归宿【源码事实推演】。

### 3.6 本章小结

- category 是子集检查：意图携带即要求，过滤器声明即能力；两个 null 分支（意图空、过滤器空）各自收窄匹配面。
- DEFAULT 规则不在 match() 里：它是查询侧 defaultOnly 闸门（IntentResolver.java:907），由 MATCH_DEFAULT_ONLY 驱动；Intent.resolveActivity 硬编码该 flag。
- 类别常量各有语义岗位：HOME/SECONDARY_HOME 管桌面、LAUNCHER/CAR_LAUNCHER 管入口清单、BROWSABLE 管安全白名单、DEFAULT 管隐式可达。
- 事故收口：category 集合是 isHomeIntent 的唯一话事输入；匹配语义与类型语义对同一份清单的两套消费规则，把「差一个 category」放大成「双实例并存」。

---

## 4. data 与 type：互斥设值与四段数据

### 4.1 三个读取器：getScheme、getType、resolveType

[Intent.java#L8619](../../../../AAOS13_study/frameworks/base/core/java/android/content/Intent.java#L8619)

```java
public @Nullable String getScheme() {
    return mData != null ? mData.getScheme() : null;
}
```

[Intent.java#L8634](../../../../AAOS13_study/frameworks/base/core/java/android/content/Intent.java#L8634)

```java
public @Nullable String getType() {
    return mType;
}
```

[Intent.java#L8693](../../../../AAOS13_study/frameworks/base/core/java/android/content/Intent.java#L8693)

```java
public @Nullable String resolveType(@NonNull ContentResolver resolver) {
    if (mType != null) {
        return mType;
    }
    if (mData != null) {
        if ("content".equals(mData.getScheme())) {
            return resolver.getType(mData);
        }
    }
    return null;
}
```

四问走一遍。**规则**：getScheme 不是字段而是 mData 的派生量；getType 直读显式字段；resolveType 三级回落——显式 type 优先，其次 content 方案的数据向 ContentProvider 现查 MIME，其余返 null。**设置者**：setType/setData（1.2 节互斥设值器）。**执行**：上面三段，resolveType 的 provider 查询是唯一可能触发跨进程调用的一环。**后果**两条【源码事实推演】：其一，**file:、http: 等方案的 data 推不出 type**——resolveType 只对 content 开口，于是「带 file: URI 且未显式 setType 的意图」在匹配侧 type 恒为 null，过滤器若声明了 mimeType 反而匹配不上（5.3 节 types 分支）；其二，匹配器拿到的 type 永远是 resolveType 的结果而非 getType——IntentFilter.match(resolver, intent, resolve=true) 与解析链传参都走 resolveType（5.2 节、ch6），**「type 是算出来的」是匹配语义的默认形态**。

### 4.2 Uri 四段与过滤器侧的四个清单

data 的 Uri 按匹配需要拆四段：**scheme**（content、file、http…）、**scheme-specific-part**（整串ssp）、**authority**（host 加 port）、**path**。过滤器侧一一对应四个清单字段（IntentFilter.java:308-311）：mDataSchemes、mDataSchemeSpecificParts、mDataAuthorities、mDataPaths，外加类型清单 mDataTypes 与静态/动态部分类型标志。声明约束由 matchData 的 javadoc 钉死（5.4 节引）：**authority 必须配 base scheme，path 必须配 scheme 加 authority**——孤立声明 path 的过滤器等于没声明（「If the filter does not specify any types or schemes that it matches against, it is considered to be empty」）【源码事实】。

### 4.3 互斥纪律的回声与大小写坑

1.2 节已立互斥设值器三兄弟；此处补匹配侧的回声：setDataAndType 双留之后，resolveType 直返显式 type、matchData 拿到的 scheme 来自 mData——**双留意图同时参与 type 段与 scheme 段的检查**，而单设意图总有一段恒 null。javadoc 对双留的态度是「should very rarely be used」，因为它把「type 由 data 推断」的默认契约绕开了：推断错是框架的责任，双设错是调用方的责任【源码事实】。大小写坑在匹配侧同样生效且扩展到 host：matchData 的 javadoc 明言「MIME type, Uri scheme, and host name matching in the Android framework is case-sensitive」（5.4 节引）——scheme、type、host 三处大小写不一致即判负，归一化变体（setDataAndNormalize/setTypeAndNormalize）是框架给的解药【源码事实】。

### 4.4 意图侧四组合预览

| 意图 data | 意图 type（resolveType 后） | 匹配侧形态 |
| --- | --- | --- |
| null | null | 只配「空过滤器」或「无 data 声明过滤器」的 EMPTY 分支（5.3 节） |
| null | 非 null | 无 scheme 可查；过滤器须以 type 清单接住（无 schemes 有 types 分支） |
| 非 null（content） | 推断或显式 | scheme 段加 type 段双查 |
| 非 null（file/http 等） | 显式或 null | scheme 段必查；type 段按显式值或 null 查 |

事故里两种意图形状都落在第一行：data 与 type 双 null，于是 MAIN 过滤器的 EMPTY 分支放行（5.7 节演算）——**桌面/图标意图从不携带 data，这是它们形状简洁的根源**【源码事实推演】。

### 4.5 本章小结

- getScheme 是派生量、getType 是直读、resolveType 是三级回落（显式 type、content 现查、null）；匹配器永远消费 resolveType 的结果。
- Uri 四段对应过滤器四清单；authority 依赖 scheme、path 依赖 scheme 加 authority，孤立声明视为空。
- file:/http: 数据推不出 type；双留设值绕过推断契约，javadoc 劝退。
- scheme/type/host 三处匹配大小写敏感，归一化变体是解药。

---

## 5. IntentFilter 与 match()：三段裁决与优先级

### 5.1 过滤器字段全景

[IntentFilter.java#L302](../../../../AAOS13_study/frameworks/base/core/java/android/content/IntentFilter.java#L302)

```java
private int mPriority;
@UnsupportedAppUsage
private int mOrder;
@UnsupportedAppUsage
private final ArrayList<String> mActions;
private ArrayList<String> mCategories = null;
private ArrayList<String> mDataSchemes = null;
private ArrayList<String> mDataSchemeSpecificParts = null;
private ArrayList<AuthorityEntry> mDataAuthorities = null;
private ArrayList<PatternMatcher> mDataPaths = null;
private ArrayList<String> mStaticDataTypes = null;
private ArrayList<String> mDataTypes = null;
private ArrayList<String> mMimeGroups = null;
private boolean mHasStaticPartialTypes = false;
private boolean mHasDynamicPartialTypes = false;
```

与 Intent 的十六口袋对照：过滤器把匹配清单摊成**六个可空清单加两个排序量**。mStaticDataTypes 与 mDataTypes 之分、两个 partial 标志服务于通配类型（image/* 之属）的快慢路径；mMimeGroups 是 A12 起的 MIME 组扩展（系统侧命名类型集合），应用 manifest 不可声明，备注级。mPriority 与 mOrder 都是排序量，但消费点不同（5.6 节）【源码事实】。

### 5.2 match() 主体：action、data、categories 三段序

[IntentFilter.java#L1975](../../../../AAOS13_study/frameworks/base/core/java/android/content/IntentFilter.java#L1975)

```java
public final int match(String action, String type, String scheme,
        Uri data, Set<String> categories, String logTag, boolean supportWildcards,
        @Nullable Collection<String> ignoreActions) {
    if (action != null && !matchAction(action, supportWildcards, ignoreActions)) {
        if (false) Log.v(
            logTag, "No matching action " + action + " for " + this);
        return NO_MATCH_ACTION;
    }

    int dataMatch = matchData(type, scheme, data, supportWildcards);
    if (dataMatch < 0) {
        ...
        return dataMatch;
    }

    String categoryMismatch = matchCategories(categories);
    if (categoryMismatch != null) {
        if (false) {
            Log.v(logTag, "No matching category " + categoryMismatch + " for " + this);
        }
        return NO_MATCH_CATEGORY;
    }

    ...
    return dataMatch;
}
```

四问走一遍。**规则**：三段序为 action 闸、data 闸（含 type）、category 闸；任一段负即返对应 NO_MATCH_*；全过则返回 **dataMatch 本身**——匹配质量由 data 段的档位决定，action 与 category 只贡献通过与否。**设置者**：manifest 的 intent-filter 膨胀（PMS 扫包）或代码 new IntentFilter。**执行**：本方法加三个子匹配器。**后果**三条【源码事实推演】：其一，**裁决顺序即短路顺序**——action 不过时 data 与 category 根本不被检查，调试「为什么匹配不上」要按此序排查；其二，返回值非布尔而是档位整数，负值四种（NO_MATCH_TYPE -1、NO_MATCH_DATA -2、NO_MATCH_ACTION -3、NO_MATCH_CATEGORY -4，IntentFilter.java:256-269），buildResolveList 的调试日志正是按这四个负值反查原因（ch6 引）；其三，引文中两处 `if (false)` 是编译期关闭的日志化石——日志语句保留在源码里但永不执行，读码时直接跳过【源码事实】。

### 5.3 matchData：分段裁决的执行体

[IntentFilter.java#L1702](../../../../AAOS13_study/frameworks/base/core/java/android/content/IntentFilter.java#L1702)

```java
private int matchData(String type, String scheme, Uri data, boolean wildcardSupported) {
    final boolean wildcardWithMimegroups = wildcardSupported && countMimeGroups() != 0;
    final List<String> types = mDataTypes;
    final ArrayList<String> schemes = mDataSchemes;

    int match = MATCH_CATEGORY_EMPTY;

    if (!wildcardWithMimegroups && types == null && schemes == null) {
        return ((type == null && data == null)
            ? (MATCH_CATEGORY_EMPTY+MATCH_ADJUSTMENT_NORMAL) : NO_MATCH_DATA);
    }

    if (schemes != null) {
        if (schemes.contains(scheme != null ? scheme : "")
                || wildcardSupported && WILDCARD.equals(scheme)) {
            match = MATCH_CATEGORY_SCHEME;
        } else {
            return NO_MATCH_DATA;
        }
        ...
    } else {
        // Special case: match either an Intent with no data URI,
        // or with a scheme: URI.  This is to give a convenience for
        // the common case where you want to deal with data in a
        // content provider, which is done by type, and we don't want
        // to force everyone to say they handle content: or file: URIs.
        if (scheme != null && !"".equals(scheme)
                && !"content".equals(scheme)
                && !"file".equals(scheme)
                && !(wildcardSupported && WILDCARD.equals(scheme))) {
            return NO_MATCH_DATA;
        }
    }
    ...
    if (wildcardWithMimegroups) {
        return MATCH_CATEGORY_TYPE;
    } else if (types != null) {
        if (findMimeType(type)) {
            match = MATCH_CATEGORY_TYPE;
        } else {
            return NO_MATCH_TYPE;
        }
    } else {
        // If no MIME types are specified, then we will only match against
        // an Intent that does not have a MIME type.
        if (type != null) {
            return NO_MATCH_TYPE;
        }
    }

    return match + MATCH_ADJUSTMENT_NORMAL;
}
```

逐段走读【源码事实】。**EMPTY 分支**：过滤器 types 与 schemes 双空时，意图必须 type 与 data 双 null 才过——4.4 节第一行的依据，事故两种形状在此放行。**schemes 分支**：scheme 必须在清单内（null 以空串代入比对），否则 NO_MATCH_DATA；过闸后依次试 ssp 清单、authority 加 path 清单（引文省略段：ssp 命中即定档 MATCH_CATEGORY_SCHEME_SPECIFIC_PART；否则 authority 命中后 path 清单非空则须 path 命中，逐级升档）。**无 schemes 便利分支**：过滤器只声明 types 不声明 schemes 时，意图 scheme 只允许 null、空串、content、file 四种——注释自陈动机：content provider 场景按 type 办事，不想逼每个人声明 content: 与 file:。**types 分支**：types 非空走 findMimeType；types 空则意图 type 必须为 null，否则 NO_MATCH_TYPE——4.1 节「file: 推不出 type」的后果在此闭环：带 file: URI 又不 setType 的意图，type 为 null，恰好能过「过滤器无 types」这一支。

ssp/authority/path 的子匹配器补两块引文。matchDataAuthority（[IntentFilter.java#L1638](../../../../AAOS13_study/frameworks/base/core/java/android/content/IntentFilter.java#L1638)）：

```java
public final int matchDataAuthority(Uri data, boolean wildcardSupported) {
    if (data == null || mDataAuthorities == null) {
        return NO_MATCH_DATA;
    }
    final int numDataAuthorities = mDataAuthorities.size();
    for (int i = 0; i < numDataAuthorities; i++) {
        final AuthorityEntry ae = mDataAuthorities.get(i);
        int match = ae.match(data, wildcardSupported);
        if (match >= 0) {
            return match;
        }
    }
    return NO_MATCH_DATA;
}
```

清单遍历、首个命中即返；data 为 null 直接判负——**声明了 authority 的过滤器拒收无 data 意图**。findMimeType（[IntentFilter.java#L2612](../../../../AAOS13_study/frameworks/base/core/java/android/content/IntentFilter.java#L2612) 起）：

```java
private final boolean findMimeType(String type) {
    final ArrayList<String> t = mDataTypes;

    if (type == null) {
        return false;
    }

    if (t.contains(type)) {
        return true;
    }

    // Deal with an Intent wanting to match every type in the IntentFilter.
    final int typeLength = type.length();
    if (typeLength == 3 && type.equals("*/*")) {
        return !t.isEmpty();
    }

    // Deal with this IntentFilter wanting to match every Intent type.
    if (hasPartialTypes() && t.contains("*")) {
        return true;
    }
    ...
```

精确包含优先，随后三个通配方向：意图侧 `*/*` 配任何非空类型清单、过滤器侧 `*` 配任何意图类型、过滤器侧前缀部分类型（如 image）配 image/* 族——通配是双向的【源码事实】。

### 5.4 matchData 的 javadoc：规则总纲与大小写

[IntentFilter.java#L1653](../../../../AAOS13_study/frameworks/base/core/java/android/content/IntentFilter.java#L1653) 起：

```java
* Match this filter against an Intent's data (type, scheme and path). If
* the filter does not specify any types and does not specify any
* schemes/paths, the match will only succeed if the intent does not
* also specify a type or data.  If the filter does not specify any schemes,
* it will implicitly match intents with no scheme, or the schemes "content:"
* or "file:" (basically performing a MIME-type only match).  If the filter
* does not specify any MIME types, the Intent also must not specify a MIME
* type.
*
* <p>Be aware that to match against an authority, you must also specify a base
* scheme the authority is in.  To match against a data path, both a scheme
* and authority must be specified.  If the filter does not specify any
* types or schemes that it matches against, it is considered to be empty
* (any authority or data path given is ignored, as if it were empty as
* well).
*
* <p><em>Note: MIME type, Uri scheme, and host name matching in the
* Android framework is case-sensitive, unlike the formal RFC definitions.
...
```

这段 javadoc 是 5.3 执行体的规范陈述，五条规则与执行体一一对应；最后的大小写注记把敏感面扩到 host（4.3 节）【源码事实】。

### 5.5 档位常量与负值族

| 常量 | 值 | 含义 |
| --- | --- | --- |
| MATCH_ADJUSTMENT_NORMAL | 0x8000 | 正常调整位，与档位相加成最终返回值 |
| MATCH_CATEGORY_EMPTY | 0x0100000 | 空对空匹配（最低档） |
| MATCH_CATEGORY_SCHEME | 0x0200000 | 仅 scheme 命中 |
| MATCH_CATEGORY_PATH | 0x0500000 | scheme 加 authority 加 path 命中 |
| MATCH_CATEGORY_SCHEME_SPECIFIC_PART | 0x0580000 | ssp 命中 |
| MATCH_CATEGORY_TYPE | 0x0600000 | type 命中（最高档） |
| NO_MATCH_TYPE / DATA / ACTION / CATEGORY | -1 / -2 / -3 / -4 | 四族负值，调试反查原因用 |

（IntentFilter.java:218-269。）档位是**数据匹配特异度**的编码：越具体的 data 声明命中越高档；action 与 category 不入档（5.2 节返回值即 dataMatch）【源码事实】。

### 5.6 priority：排序量的唯一消费点

[IntentFilter.java#L544](../../../../AAOS13_study/frameworks/base/core/java/android/content/IntentFilter.java#L544)

```java
public final void setPriority(int priority) {
    mPriority = priority;
}
...
public final int getPriority() {
    return mPriority;
}
```

[IntentResolver.java#L947](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/IntentResolver.java#L947)

```java
private static final Comparator mResolvePrioritySorter = new Comparator() {
    // compare：按过滤器优先级降序。
    public int compare(Object o1, Object o2) {
        final int q1 = ((IntentFilter) o1).getPriority();
        final int q2 = ((IntentFilter) o2).getPriority();
        return (q1 > q2) ? -1 : ((q1 < q2) ? 1 : 0);
    }
};
```

priority 不参与 match() 的任何一段——它只在解析链把结果列排序时出场（Collections.sort(results, mResolvePrioritySorter)，IntentResolver.java:642），降序。**match 管「进不进结果列」，priority 管「结果列里谁在前」**，两权分立【源码事实】。mOrder 字段（IntentFilter.java:304）在该排序器里不被读取，其消费点在 preferred activities 等系统路径，备注级。雅迪 MainActivity 过滤器的 priority=1000 因此只在多候选排序时 meaningful——事故链上 HOME 形状与 LAUNCHER 形状的候选都唯一，priority 未起作用【源码事实推演】。

### 5.7 演算：雅迪 MAIN 过滤器对三种意图

过滤器声明（application/Launcher/src/main/AndroidManifest.xml:89-95）：actions={MAIN}，categories={HOME, LAUNCHER, DEFAULT}，data 四清单与 types 全空，priority=1000。代入 match() 三段【源码事实推演】：

| 意图 | action 闸 | data 闸 | category 闸 | 返回值 |
| --- | --- | --- | --- | --- |
| Monkey 形：MAIN + [LAUNCHER]，无 data/type | 过（MAIN 在清单） | EMPTY 分支过（双 null） | 子集过 | MATCH_CATEGORY_EMPTY 加 NORMAL |
| goLauncher 形：MAIN + [HOME]，无 data/type | 过 | EMPTY 分支过 | 子集过 | 同上 |
| 假想形：VIEW + content://media/... + 推断 type image/jpeg | 负（VIEW 不在清单） | 不检查 | 不检查 | NO_MATCH_ACTION |

前两行返回值完全相同——**match() 层面两种事故形状不可区分**，与 3.5 节的结论互证：区分力在 WM 的形状判定，不在匹配器。第三行展示 action 闸的短路：data 与 category 再合适也不被检查。

### 5.8 本章小结

- 过滤器六清单加两排序量；match() 三段序 action、data、categories，返回值是 data 档位。
- matchData 五分支：EMPTY、schemes 加 ssp/authority/path、无 schemes 的 content/file 便利、types 的 findMimeType、无 types 要求意图 type 为 null。
- 通配双向：意图 `*/*`、过滤器 `*` 与前缀部分类型。
- 档位编码特异度，负值族供调试反查；priority 只管结果列排序，与 match 两权分立。
- 雅迪 MAIN 过滤器对两种事故形状返回同档——匹配器无罪，罪在形状消费的两套规则（3.5 节）。

---

## 6. 解析链：从 resolveActivity 到 buildResolveList 五关

### 6.1 链路与入口

隐式意图转显式组件的完整链路（行号均学习树）：

```text
Intent.resolveActivity(pm)            Intent.java:9689（3.2 节已引：硬编码 MATCH_DEFAULT_ONLY）
  -> ApplicationPackageManager.resolveActivityAsUser   ApplicationPackageManager.java:1619
       -> mPM.resolveIntent(intent, resolveTypeIfNeeded(...), flags, userId)   （Binder 进 system_server）
  -> ResolveIntentHelper.resolveIntentInternal          ResolveIntentHelper.java:121
       -> ComputerEngine.queryIntentActivitiesInternal  ComputerEngine.java:560/683/691
            -> selector 顶替（ComputerEngine.java:582-587）
            -> IntentResolver.buildResolveList          IntentResolver.java:837（五关）
            -> Collections.sort(mResolvePrioritySorter) IntentResolver.java:642（5.6 节已引）
       -> chooseBestActivity                            ResolveIntentHelper.java:166
  -> 回写 cmp（姊妹篇 §2.3：ActivityTaskSupervisor.resolveActivity）
```

[ApplicationPackageManager.java#L1619](../../../../AAOS13_study/frameworks/base/core/java/android/app/ApplicationPackageManager.java#L1619)

```java
public ResolveInfo resolveActivityAsUser(Intent intent, ResolveInfoFlags flags, int userId) {
    try {
        return mPM.resolveIntent(
            intent,
            intent.resolveTypeIfNeeded(mContext.getContentResolver()),
            updateFlagsForComponent(flags.getValue(), userId, intent),
            userId);
    } catch (RemoteException e) {
        throw e.rethrowFromSystemServer();
    }
}
```

三个细节：resolvedType 在客户端就先算好（resolveTypeIfNeeded，4.1 节回落规则的按需版）随 Binder 过江，服务端不重查 provider；flags 经 updateFlagsForComponent 调整（显式组件与隐式意图的暴露面不同）；整条链对调用方是同步 Binder 调用，启动热路径上这一次往返就是隐式启动的解析成本【源码事实】。

### 6.2 resolveIntentInternal：查询加决胜

[ResolveIntentHelper.java#L121](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/pm/ResolveIntentHelper.java#L121)

```java
public ResolveInfo resolveIntentInternal(Computer computer, Intent intent, String resolvedType,
        @PackageManager.ResolveInfoFlagsBits long flags,
        @PackageManagerInternal.PrivateResolveFlags long privateResolveFlags, int userId,
        boolean resolveForStart, int filterCallingUid) {
    try {
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "resolveIntent");

        if (!mUserManager.exists(userId)) return null;
        final int callingUid = Binder.getCallingUid();
        flags = computer.updateFlagsForResolve(flags, userId, filterCallingUid, resolveForStart,
                computer.isImplicitImageCaptureIntentAndNotSetByDpc(intent, userId,
                        resolvedType, flags));
        computer.enforceCrossUserPermission(callingUid, userId, false /*requireFullPermission*/,
                false /*checkShell*/, "resolve intent");
        ...
        final List<ResolveInfo> query = computer.queryIntentActivitiesInternal(intent,
                resolvedType, flags, privateResolveFlags, filterCallingUid, userId,
                resolveForStart, true /*allowDynamicSplits*/);
        ...
        final ResolveInfo bestChoice = chooseBestActivity(computer, intent, resolvedType, flags,
                privateResolveFlags, query, userId, queryMayBeFiltered);
        ...
        return bestChoice;
    } finally {
        Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
    }
}
```

四问走一遍（整链）。**规则**：flags 先经 updateFlagsForResolve 归一（用户存在性、跨用户权限、隐式拍照意图的特例），再全量查询候选列，最后决胜出唯一 ResolveInfo 或 null。**设置者**：客户端 flags（MATCH_DEFAULT_ONLY 由 Intent.resolveActivity 硬编码，3.2 节）与服务端归一逻辑双方。**执行**：本方法串起 query 与 chooseBest 两段。**后果**：resolve 的产物是**单个** bestChoice——隐式启动的「多候选」命运（ResolverActivity 选择器界面）也在这里决定（6.5 节第四条）【源码事实】。

### 6.3 selector 在查询入口顶替本体

[ComputerEngine.java#L578](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/pm/ComputerEngine.java#L578)

```java
final String pkgName = intent.getPackage();
Intent originalIntent = null;
ComponentName comp = intent.getComponent();
if (comp == null) {
    if (intent.getSelector() != null) {
        originalIntent = intent;
        intent = intent.getSelector();
        comp = intent.getComponent();
    }
}
```

1.6 节预告的「匹配时选择器顶替本体」在此执行：**仅当意图无显式组件时**，查询拿 selector 当匹配清单，原意图存进 originalIntent 备后续授权等用途。ResolveIntentHelper.java:368、478 两处同款顶替服务于其他查询入口。事故链无 selector，此节为机制完整性而设【源码事实】。

### 6.4 buildResolveList 五关

[IntentResolver.java#L861](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/IntentResolver.java#L861) 起：

```java
for (i=0; i<N && (filter=src[i]) != null; i++) {
    int match;
    ...
    if (excludingStopped && isFilterStopped(computer.getPackageStateInternal(packageName),
            userId)) {
        ...
        continue;
    }

    // Is delivery being limited to filters owned by a particular package?
    if (packageName != null && !isPackageForFilter(packageName, filter)) {
        ...
        continue;
    }
    ...
    // Do we already have this one?
    if (!allowFilterResult(filter, dest)) {
        ...
        continue;
    }

    match = intentFilter.match(action, resolvedType, scheme, data, categories, TAG);
    if (match >= 0) {
        ...
        if (!defaultOnly || intentFilter.hasCategory(Intent.CATEGORY_DEFAULT)) {
            final R oneResult = newResult(computer, filter, match, userId, customFlags);
            ...
            dest.add(oneResult);
            ...
        } else {
            hasNonDefaults = true;
        }
    }
    ...
}
```

五关按序【源码事实】：

1. **停用关**：intent.isExcludingStopped() 为真时（FLAG_EXCLUDE_STOPPED_PACKAGES 语义，ch9），已停止包内的过滤器跳过——刚安装未启动过的应用收不到隐式广播的根源即此。
2. **包限定关**：意图带 mPackage 时，非该包的过滤器跳过——setPackage 的「限包隐式」语义执行点。
3. **去重关**：allowFilterResult 防止同一目标组件重复入列（一个组件多个 filter 命中时只出一条 ResolveInfo）。
4. **匹配关**：intentFilter.match 三段裁决（ch5），负值按 NO_MATCH_* 族记入调试日志。
5. **DEFAULT 关**：defaultOnly 为真时须过滤器带 CATEGORY_DEFAULT（3.2 节规则的执行行，IntentResolver.java:907）；被此关挡下的匹配记 hasNonDefaults，调试态打印「resolveIntent failed: found match, but none with CATEGORY_DEFAULT」（IntentResolver.java:938）。

四问之**后果**：候选列是「过了五关的过滤器」对应的 ResolveInfo 集合；五关全在查询侧，match() 保持纯匹配语义——**策略与语义分离**是本章与 ch5 的分界【源码事实推演】。

### 6.5 chooseBestActivity：多候选决胜

[ResolveIntentHelper.java#L166](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/pm/ResolveIntentHelper.java#L166)

```java
private ResolveInfo chooseBestActivity(Computer computer, Intent intent, String resolvedType,
        @PackageManager.ResolveInfoFlagsBits long flags,
        @PackageManagerInternal.PrivateResolveFlags long privateResolveFlags,
        List<ResolveInfo> query, int userId, boolean queryMayBeFiltered) {
    if (query != null) {
        final int n = query.size();
        if (n == 1) {
            return query.get(0);
        } else if (n > 1) {
            final boolean debug = ((intent.getFlags() & Intent.FLAG_DEBUG_LOG_RESOLUTION) != 0);
            // If there is more than one activity with the same priority,
            // then let the user decide between them.
            ResolveInfo r0 = query.get(0);
            ResolveInfo r1 = query.get(1);
            ...
            // If the first activity has a higher priority, or a different
            // default, then it is always desirable to pick it.
            if (r0.priority != r1.priority
                    || r0.preferredOrder != r1.preferredOrder
                    || r0.isDefault != r1.isDefault) {
                return query.get(0);
            }
            // If we have saved a preference for a preferred activity for
            // this Intent, use that.
            ResolveInfo ri = mPreferredActivityHelper.findPreferredActivityNotLocked(computer,
                    intent, resolvedType, flags, query, true, false, debug,
                    userId, queryMayBeFiltered);
            if (ri != null) {
                return ri;
            }
            ...
```

决胜序：唯一候选直返；头两名在 priority/preferredOrder/isDefault 上任一不同则取排序后的头名（排序器见 5.6 节）；否则查用户保存的 preferred activity（「总是用某应用打开」的设置）；再否则即用应用的域名校验候选；全未决则返回系统解析器活动（ResolverActivity，选择器界面）并装饰之【源码事实，尾段省略】。注意 FLAG_DEBUG_LOG_RESOLUTION 在此与 buildResolveList 的 debug 分支同源：意图自带该 flag 即可让整条解析链吐 VERBOSE 日志——**排查「为什么解析到它」的第一工具**【源码事实】。

### 6.6 收口三：B 的 cmp= 从何而来

回看日志 122044 行【日志事实】：

```text
11-03 05:44:10.750 12877 13081 I ActivityTaskManager: START u0 {act=android.intent.action.MAIN cat=[android.intent.category.HOME] flg=0x10000000 cmp=com.yadea.launcher/.function.main.view.MainActivity} from uid 1000, pid 13232
```

goLauncher 的源码（SystemUI ActivityStarter.kt:84-95，姊妹篇 §1.6 已引）构造的是**不带 component 的隐式 HOME 意图**；日志里却明晃晃写着 cmp=。代入本章全链【源码事实推演】：

1. 意图进 ATS.resolveActivity（姊妹篇 §2.3）→ Intent.resolveActivity 硬编码 MATCH_DEFAULT_ONLY（§3.2）。
2. Binder 进 system_server，resolveIntentInternal 归一 flags 后全量查询（6.2 节）。
3. selector 顶替不触发（无 selector、无 component）。
4. buildResolveList 五关：停用关过（Launcher 进程活着、包未 stopped）；包限定关过（mPackage 为 null）；去重关过（首条）；匹配关过（5.7 节演算第二行，返 EMPTY 档）；DEFAULT 关过（defaultOnly 为真且过滤器声明了 DEFAULT，manifest 第 94 行）。
5. 候选唯一（实机上声明 MAIN+HOME 过滤器的活动仅雅迪 MainActivity 一家【日志事实推演，dumpsys 可复核】）→ chooseBestActivity 的 n==1 分支直返。
6. ResolveInfo 回客户端，ATS 把组件写回 intent（姊妹篇 §2.3 引文），START 日志在回写之后打印——**cmp= 是解析的回写痕，不是调用方的指名**。

Monkey 首启（106587 行）同链同痕：LAUNCHER 形状也唯一命中同一过滤器。**两行日志 cmp 相同、cat 不同**——解析链对两种形状一视同仁地回写同一组件，形状的差异被完整保留到 WM 侧消费（3.5 节）。这就是「隐式意图的 START 日志带 cmp=」这一阅读陷阱的完整因果（姊妹篇 §2.3 首次指出，本章给出全链）【源码事实推演加日志事实】。

### 6.7 本章小结

- 链路六跳：Intent.resolveActivity（硬编码 DEFAULT_ONLY）→ APM（type 先算、flags 归一）→ ResolveIntentHelper（query 加决胜）→ ComputerEngine（selector 顶替）→ IntentResolver（五关加排序）→ 回写 cmp。
- 五关全在查询侧：停用、包限定、去重、match、DEFAULT；策略与匹配语义分离。
- chooseBestActivity 决胜序：唯一、排序差、preferred、即用校验、ResolverActivity。
- 收口三：START 日志的 cmp= 是解析回写痕；两种事故形状在同一条链上得到同一个 cmp，形状差异原样留给 WM。

---

## 7. 身份判定：filterEquals、equals 与同形状

### 7.1 四级身份阶梯

| 级别 | 判据 | 实现 | 比什么 |
| --- | --- | --- | --- |
| 引用相等 | == | Object 默认 | 同一对象 |
| 形状相等 | filterEquals | Intent.java:11451 | 七字段（7.2 节） |
| 形状哈希 | filterHashCode | Intent.java:11490 | 同语义哈希 |
| 业务形状 | isHomeIntent/isMainIntent | ActivityRecord.java:2953/2963 | 五关特化（2.5 节） |

**Intent 类本身不覆写 equals 与 hashCode**：全文 grep 仅见内部类 FilterComparison 的 hashCode（Intent.java:11431），Intent 类体无覆写【源码事实】。于是「两个 Intent 相等」在 Java 语义下就是引用相等——把 Intent 塞进 HashSet 去重是按对象去重，不是按形状；框架给的形状钥匙盒是 FilterComparison（[Intent.java#L11395](../../../../AAOS13_study/frameworks/base/core/java/android/content/Intent.java#L11395) 起）：

```java
public static final class FilterComparison {
    ...
    public FilterComparison(Intent intent) {
        mIntent = intent;
        mHashCode = intent.filterHashCode();
    }
    ...
    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof FilterComparison) {
            Intent other = ((FilterComparison)obj).mIntent;
            return mIntent.filterEquals(other);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return mHashCode;
    }
}
```

构造时缓存 filterHashCode、equals 委托 filterEquals——**不可变包装器把形状语义适配进 Java 集合契约**，哈希容器按形状归组的正确姿势【源码事实】。

### 7.2 filterEquals：七字段与一个豁免

[Intent.java#L11451](../../../../AAOS13_study/frameworks/base/core/java/android/content/Intent.java#L11451)

```java
public boolean filterEquals(Intent other) {
    if (other == null) {
        return false;
    }
    if (!Objects.equals(this.mAction, other.mAction)) return false;
    if (!Objects.equals(this.mData, other.mData)) return false;
    if (!Objects.equals(this.mType, other.mType)) return false;
    if (!Objects.equals(this.mIdentifier, other.mIdentifier)) return false;
    if (!(this.hasPackageEquivalentComponent() && other.hasPackageEquivalentComponent())
            && !Objects.equals(this.mPackage, other.mPackage)) {
        return false;
    }
    if (!Objects.equals(this.mComponent, other.mComponent)) return false;
    if (!Objects.equals(this.mCategories, other.mCategories)) return false;

    return true;
}
```

[Intent.java#L11475](../../../../AAOS13_study/frameworks/base/core/java/android/content/Intent.java#L11475)

```java
private boolean hasPackageEquivalentComponent() {
    return mComponent != null
        && (mPackage == null || mPackage.equals(mComponent.getPackageName()));
}
```

七字段按序：action、data、type、identifier、package（带豁免）、component、categories。四问走一遍。**规则**：七字段全等即形状相等；package 比较带豁免——双方都「组件非空且包名与组件同包或包名为空」时跳过 package 比较（hasPackageEquivalentComponent），因为此时 package 不提供超出 component 的信息。**设置者**：各设值器。**执行**：上面十行。**后果**三条【源码事实推演】：其一，**flags、extras、selector、sourceBounds、clipData 全部不可见**——同形状不同 flags 的两个意图 filterEquals 为真（flags 差异在任务裁决侧消费，姊妹篇 ch3）；其二，**categories 按集合相等比较**——[HOME] 与 [LAUNCHER] 不等、[HOME, DEFAULT] 与 [DEFAULT, HOME] 相等（ArraySet.equals 与顺序无关）；其三，javadoc 自陈一处与 match() 的微妙分歧（Intent.java:11440-11447）：「technically when actually matching against an IntentFilter the identifier is ignored, while here it is directly compared」——**mIdentifier 进身份判定但不进匹配**，同一意图在「匹配」与「身份」两套语义下粒度不同【源码事实】。

### 7.3 使用点地图：谁在消费形状相等

- **PendingIntent 身份**：PendingIntentRecord 的哈希用 filterHashCode（PendingIntentRecord.java:142）、相等用 filterEquals（:185）——「同一个 PendingIntent」的定义就是请求意图形状相同加 requestCode 等外围字段【源码事实】。
- **快照起始窗准入**：ActivityRecord.allowTaskSnapshot（[ActivityRecord.java#L9968](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ActivityRecord.java#L9968) 起）遍历 pending newIntents：main 形状（isMainIntent）直接跳过，其余须与 mLastNewIntent（或本记录意图）filterEquals 才放行任务快照起始窗——注释自陈理由：「If the intent is something else, likely the app is going to show some specific page or view, instead of what's left last time.」**形状判定与身份判定在此合流**【源码事实】。
- **singleInstance 专线**：RootWindowContainer.java:2967，compareIntentFilters 为真时 matchesActivity 用 filterEquals 比对（姊妹篇 §5.4 引文）——HOME 型 singleInstance 按完整形状匹配。
- **任务身份**：Task.java:1197 以 filterEquals 比对任务意图；RecentTasks.java:1718 用于最近任务去重【源码事实】。

### 7.4 收口四：filterEquals 为何救不了 A/B

两层否定，逐层代入【源码事实推演】。

**第一层：A 与 B 的意图根本不 filterEquals。** 七字段代入：action 同（MAIN）、data 同（null）、type 同（null）、identifier 同（null）、package 豁免同、component 同（回写后同 cmp）——**categories 一栏 [LAUNCHER] 对 [HOME] 判负**。形状相等这把尺子量出的结论与 isHomeIntent 一致：两个意图不同形状。

**第二层：就算 filterEquals 为真，启动链上也没有任何一道闸按身份做全局去重。** 逐闸清点：findTask 的闸是类型、用户、finishing、栈顶组件（姊妹篇 ch4），不看请求与存量记录的身份相等；complyActivityFlags 的清栈发生在**已匹配任务内部**（姊妹篇 §3.7），跨任务无管辖；唯一的全局组件搜索是 singleInstance 的 findActivity 专线（姊妹篇 §5.4），而 MainActivity 是 singleTask，不进专线。**filterEquals 的消费者名单（7.3 节）里没有启动去重这一项**——PendingIntent、快照窗、singleInstance 专线、任务身份，各管各的域。

合起来：filterEquals 对 A/B 既**不被调用**（singleTask 走 findTask），也**通不过**（categories 不等）。双实例并存不违反任何身份规则，因为身份规则从不承诺全局唯一——这条结论与姊妹篇 §4.7 的问答互证：「全局唯一性从来不是 launchMode 的承诺，是匹配器可见性的副产品」【源码事实推演】。

### 7.5 本章小结

- 四级身份阶梯：引用、filterEquals 七字段、filterHashCode、业务形状五关；Intent 不覆写 equals，形状钥匙盒是 FilterComparison。
- filterEquals 对 flags/extras/selector 盲；categories 按集合相等；mIdentifier 进身份不进匹配。
- 消费点四域：PendingIntent 身份、快照起始窗准入、singleInstance 专线、任务与最近任务身份。
- 收口四：A/B 意图 categories 不等故 filterEquals 本就不过；启动链亦无按身份全局去重之闸——双实例与身份规则无冲突。

---

## 8. Parcelable 序列化：跨进程形状不变的证据

### 8.1 writeToParcel：十四槽定序

[Intent.java#L11938](../../../../AAOS13_study/frameworks/base/core/java/android/content/Intent.java#L11938)

```java
public void writeToParcel(Parcel out, int flags) {
    out.writeString8(mAction);
    Uri.writeToParcel(out, mData);
    out.writeString8(mType);
    out.writeString8(mIdentifier);
    out.writeInt(mFlags);
    out.writeString8(mPackage);
    ComponentName.writeToParcel(mComponent, out);

    if (mSourceBounds != null) {
        out.writeInt(1);
        mSourceBounds.writeToParcel(out, flags);
    } else {
        out.writeInt(0);
    }

    if (mCategories != null) {
        final int N = mCategories.size();
        out.writeInt(N);
        for (int i=0; i<N; i++) {
            out.writeString8(mCategories.valueAt(i));
        }
    } else {
        out.writeInt(0);
    }

    if (mSelector != null) {
        out.writeInt(1);
        mSelector.writeToParcel(out, flags);
    } else {
        out.writeInt(0);
    }

    if (mClipData != null) {
        out.writeInt(1);
        mClipData.writeToParcel(out, flags);
    } else {
        out.writeInt(0);
    }
    out.writeInt(mContentUserHint);
    out.writeBundle(mExtras);

    if (mOriginalIntent != null) {
        out.writeInt(1);
        mOriginalIntent.writeToParcel(out, flags);
    } else {
        out.writeInt(0);
    }
}
```

槽位定序表：

| 序 | 槽 | 编码 | 空态表示 |
| --- | --- | --- | --- |
| 1 | mAction | string8 | null 串 |
| 2 | mData | Uri.writeToParcel | 内建 null 安全 |
| 3 | mType | string8 | null 串 |
| 4 | mIdentifier | string8 | null 串 |
| 5 | mFlags | int | 0 |
| 6 | mPackage | string8 | null 串 |
| 7 | mComponent | ComponentName.writeToParcel | 内建 null 安全 |
| 8 | mSourceBounds | 哨兵 int 加 Rect | 哨兵 0 |
| 9 | mCategories | 计数 int 加逐条 string8 | 计数 0 |
| 10 | mSelector | 哨兵加递归 Intent | 哨兵 0 |
| 11 | mClipData | 哨兵加递归 ClipData | 哨兵 0 |
| 12 | mContentUserHint | int | 默认值 |
| 13 | mExtras | Bundle | null 安全 |
| 14 | mOriginalIntent | 哨兵加递归 Intent | 哨兵 0 |

三个协议细节：**哨兵位**（8/10/11/14 槽）用前置 int 区分「无」与「有」，可空复合对象不必自携 null 标记；**categories 按 ArraySet 的 valueAt 序逐条写**——插入序过江，读侧按同序重建（8.2 节），集合内容连同顺序一起不变；**mFlags 整字过江**——事故日志里的 flg=0x10200000 就是 Monkey 进程 setFlags 的原值，一位不改【日志事实，行号见 §2.5 引文】。

### 8.2 readFromParcel：镜像还原与出身标记

[Intent.java#L12004](../../../../AAOS13_study/frameworks/base/core/java/android/content/Intent.java#L12004)

```java
protected Intent(Parcel in) {
    // Remember that we came from a remote process to help detect security
    // issues caused by later unsafe launches
    mLocalFlags = LOCAL_FLAG_FROM_PARCEL;
    readFromParcel(in);
}
```

[Intent.java#L12014](../../../../AAOS13_study/frameworks/base/core/java/android/content/Intent.java#L12014)

```java
public void readFromParcel(Parcel in) {
    setAction(in.readString8());
    mData = Uri.CREATOR.createFromParcel(in);
    mType = in.readString8();
    mIdentifier = in.readString8();
    mFlags = in.readInt();
    mPackage = in.readString8();
    mComponent = ComponentName.readFromParcel(in);

    if (in.readInt() != 0) {
        mSourceBounds = Rect.CREATOR.createFromParcel(in);
    }

    int N = in.readInt();
    if (N > 0) {
        mCategories = new ArraySet<String>();
        int i;
        for (i=0; i<N; i++) {
            mCategories.add(in.readString8().intern());
        }
    } else {
        mCategories = null;
    }
    ...
```

读序与写序逐槽镜像（省略段为四个哨兵槽与末尾三槽）；categories 读回时逐条 intern，与写侧 addCategory 的 intern 汇合于同一常量池对象。注意读侧走 `setAction(in.readString8())` 而非直赋 mAction——**反序列化也过设值器**，intern 纪律对远来字符串同样生效【源码事实】。

LOCAL_FLAG 家族五位（Intent.java:7187-7213）：FROM_COPY（1<<0）、FROM_PARCEL（1<<1）、FROM_PROTECTED_COMPONENT（1<<2）、UNFILTERED_EXTRAS（1<<3）、FROM_URI（1<<4），**全部不过江**（1.5 节）。FROM_PARCEL 由反序列化构造函数独写；FROM_PROTECTED_COMPONENT 的 javadoc 特别警告不可递归施加于内嵌意图（「a malicious app may have controlled them via fillIn」）——本地标志是进程内的出身档案，序列化边界就是它的国界【源码事实】。

### 8.3 形状不变：服务端判定可信的物理基础

把 8.1/8.2 合起来得本篇第八条核心结论：**Intent 跨 Binder 后形状不变**——十四槽定序加哨兵协议保证字段不丢不串，categories 顺序与 intern 态保持，flags 原值过江【源码事实推演】。它的直接后果是 ch2/ch3 全部形状判定的合法性：system_server 里 isHomeIntent 量到的 categories 集合，就是应用进程 addCategory 放进去的那个集合；START 日志里的 cat=[...] 与 flg=0x... 就是调用方意图的原貌【日志事实】。

反向推论同样有用：**本进程构造的意图没有 FROM_PARCEL 标记**。system_server 自造的意图（如 RootWindowContainer.startHomeActivity 构造的 HOME 意图，姊妹篇 §1.4）mLocalFlags 为 0，与远来意图可区分——不安全启动检测正是靠这条界线识别「远道而来却被当作本地信任」的意图【源码事实推演】。

### 8.4 本章小结

- 十四槽定序加四哨兵；categories 插入序过江、读回 intern；flags 原值过江。
- 反序列化过设值器；LOCAL_FLAG 五位全部不过江，FROM_PARCEL 是远来意图的出身标记。
- 形状不变是服务端形状判定与 START 日志可信的物理基础；本进程意图以 mLocalFlags=0 与远来意图划界。

---

## 9. flags 差集与 selector/ClipData/extras 备注

### 9.1 分工声明：FLAG_ACTIVITY 族归姊妹篇

FLAG_ACTIVITY_NEW_TASK/CLEAR_TOP/SINGLE_TOP 等启动语义族的任务裁决后果，姊妹篇 ch3/ch5 已逐支推导，本篇不重复。本章只写 Intent 域内自洽的差集：授权族、stopped 族、调试位、广播侧位与可变性注解。

### 9.2 授权族：URI 权限随意图搬运

[Intent.java#L6655](../../../../AAOS13_study/frameworks/base/core/java/android/content/Intent.java#L6655)

```java
/**
 * If set, the recipient of this Intent will be granted permission to
 * perform read operations on the URI in the Intent's data and any URIs
 * specified in its ClipData.  When applying to an Intent's ClipData,
 * all URIs as well as recursive traversals through data or other ClipData
 * in Intent items will be granted; only the grant flags of the top-level
 * Intent are used.
 */
public static final int FLAG_GRANT_READ_URI_PERMISSION = 0x00000001;
```

四问走一遍（授权族整体）。**规则**：GRANT_READ（0x1）/GRANT_WRITE（0x2）把 data 与 ClipData 内全部 URI 的读写权限授给接收方；递归遍历内嵌 ClipData，但**授权位只认顶层意图**（内嵌意图自带的 grant 位不算）。**设置者**：调用方 addFlags。**执行**：服务端 startActivity/sendBroadcast 路径的 URI 授权环节（grantUriPermissionUncheckedFromIntent，姊妹篇 §3.7 deliverNewIntentLocked 引文出现过调用点）。**后果**：分享图片、跨应用打开文档的「临时授权」全靠这两位；PERSISTABLE（0x40）叠加后授权可跨重启存续直至显式 revoke，PREFIX（0x80）叠加后授权扩到前缀匹配的 URI 族【源码事实】。

### 9.3 stopped 族：广播默认不吵醒stopped 应用

[Intent.java#L6690](../../../../AAOS13_study/frameworks/base/core/java/android/content/Intent.java#L6690)

```java
/**
 * If set, this intent will always match any components in packages that
 * are currently stopped.  This is the default behavior when
 * {@link #FLAG_EXCLUDE_STOPPED_PACKAGES} is not set.  If both of these
 * flags are set, this one wins (it allows overriding of exclude for
 * places where the framework may automatically set the exclude flag).
 */
public static final int FLAG_INCLUDE_STOPPED_PACKAGES = 0x00000020;
```

[Intent.java#L9591](../../../../AAOS13_study/frameworks/base/core/java/android/content/Intent.java#L9591)

```java
public boolean isExcludingStopped() {
    return (mFlags&(FLAG_EXCLUDE_STOPPED_PACKAGES|FLAG_INCLUDE_STOPPED_PACKAGES))
            == FLAG_EXCLUDE_STOPPED_PACKAGES;
}
```

[ActivityManagerService.java#L15055](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java#L15055)

```java
// By default broadcasts do not go to stopped apps.
intent.addFlags(Intent.FLAG_EXCLUDE_STOPPED_PACKAGES);
```

机制三层【源码事实】：isExcludingStopped 的位运算实现「仅 EXCLUDE 置位才排除」——双置位时 INCLUDE 赢（javadoc 明言 this one wins）；广播路径在 AMS 无条件 addFlags(EXCLUDE)，注释自陈「By default broadcasts do not go to stopped apps」，调用方想吵醒 stopped 应用须显式 INCLUDE 压回；消费点是 buildResolveList 第一关（6.4 节）与广播投递的同源检查。**后果**：刚安装未启动的应用收不到开机广播等隐式广播（stopped 态），这是「安装后须用户启动一次」体验的框架根源；活动解析侧调用方不设旗时 excludingStopped 为假，stopped 包的活动仍可被隐式解析命中——**广播与活动对 stopped 的默认态度相反**，别记混【源码事实推演】。

### 9.4 调试位与广播侧位备注

- **FLAG_DEBUG_LOG_RESOLUTION（0x8）**：意图自带即让 buildResolveList 与 chooseBestActivity 全链吐 VERBOSE 日志（6.4/6.5 节的 debug 分支同源），排查「解析为什么命中它」的第一工具【源码事实】。
- **FLAG_FROM_BACKGROUND（0x4）**：声明意图来自后台操作而非用户直接交互，供策略侧参考【源码事实】。
- **FLAG_RECEIVER_REGISTERED_ONLY（0x40000000）与 ..._BEFORE_BOOT（0x04000000）**：广播侧位——前者限定只投动态注册接收者（AMS 开机未就绪时也会自动加，见 15058-15060 引文邻行），后者限定开机升级广播，备注级【源码事实】。
- **MutableFlags 注解**（Intent.java:6650-6653）：标记「可变标志子集」——全集去掉授权位、另收进程内改写位；fillIn 等合并操作只允许动子集内的位，授权位不可被合并方篡改【源码事实】。

### 9.5 selector/ClipData/extras 三备注

- **selector**：匹配顶替本体（1.6 节 javadoc、6.3 节执行行），身份判定不可见（ch7）；车机链罕见，机制完整性备注。
- **ClipData**：授权族的递归遍历对象（9.2 节）加序列化哨兵槽（8.1 节第 11 槽）；启动链上常为空。
- **extras**：匹配与身份双盲（ch7 后果一），但它是 onNewIntent 的业务载荷——ActivityRecord.allowTaskSnapshot 的特判（7.3 节）里 `intent.getExtras() != null` 即「带载荷的新意图视为要展示新页面」，extras 在形状语义之外另有一套业务语义【源码事实】。

### 9.6 本章小结

- 授权族四位（READ/WRITE/PERSISTABLE/PREFIX）：递归遍历 ClipData、只认顶层授权位。
- stopped 族：INCLUDE 赢双置位；广播路径默认加 EXCLUDE；活动解析默认不排除——两侧态度相反。
- DEBUG_LOG_RESOLUTION 是解析链的自诊开关；MutableFlags 把授权位划出可变子集。
- selector/ClipData/extras 各有跨界角色：匹配顶替、授权递归、业务载荷。

---

## 10. 事故意图形状总演算

### 10.1 两意图逐字段对照

以 §2.5 引文的两行 START 日志为输入（106587 行 Monkey 首启、122044 行 goLauncher），字段级对照：

| 字段 | Monkey 形（A） | goLauncher 形（B） | 差异 |
| --- | --- | --- | --- |
| mAction | android.intent.action.MAIN | 同 | 无 |
| mCategories | [android.intent.category.LAUNCHER] | [android.intent.category.HOME] | **唯一输入差异** |
| mData | null | null | 无 |
| mType | null | null | 无 |
| mComponent（调用方构造时） | null（隐式） | null（隐式） | 无 |
| mComponent（解析回写后） | com.yadea.launcher/.function.main.view.MainActivity | 同 | 无 |
| mPackage | null | null | 无 |
| mFlags | 0x10200000（NEW_TASK 加 RESET_TASK_IF_NEEDED） | 0x10000000（NEW_TASK） | 有，但不进任何形状闸 |
| 调用方 | uid 0、pid 13116（Monkey） | uid 1000、pid 13232（SystemUI） | 有，进 canLaunchHomeActivity 而非形状判定 |
| isMainIntent | 真 | 假 | 分叉 |
| isHomeIntent | 假 | 真 | 分叉 |

### 10.2 分叉点主表：同一形状清单的两套消费

| 阶段 | Monkey 形 | goLauncher 形 | 是否分叉 |
| --- | --- | --- | --- |
| match() 三段（ch5） | EMPTY 档过 | EMPTY 档过 | 否（5.7 节演算） |
| buildResolveList 五关（ch6） | 全过 | 全过 | 否 |
| chooseBestActivity（ch6） | n==1 直返 | n==1 直返 | 否 |
| cmp 回写（ch6） | 同组件 | 同组件 | 否 |
| isMainIntent/isHomeIntent（ch2） | 真/假 | 假/真 | **是** |
| 记录类型定型（姊妹篇 ch2） | UNDEFINED 后补 STANDARD | HOME | **是** |
| findTask 类型闸（姊妹篇 ch4） | UNDEFINED 泛匹配 | HOME 排他，看不见 Task 2 | **是** |
| 任务结局（姊妹篇 ch8） | 新建 STANDARD 根 Task 2 | Task 1 下新建子 Task 3 | **是** |

分叉始于形状判定、终于任务拓扑；匹配与解析两段对两种形状完全同视。**Intent 机制本身无过错：它如实搬运形状（ch8）、如实匹配（ch5）、如实解析（ch6）；过错在 WM 把同一份 categories 清单按两套规则消费（3.5 节）**【源码事实推演】。

### 10.3 反事实四例（Intent 视角）

1. **Monkey 改 HOME 形**（categories=[HOME]）：首启即 HOME 类型入 Task 1 子任务；goLauncher 的 HOME 请求 findTask 时 HOME 乘 HOME 兼容、栈顶组件全等得理想解，复用 A 的任务——**无双实例**（姊妹篇 §8.10 反事实一）。
2. **goLauncher 改 LAUNCHER 形**：B 请求 UNDEFINED 泛匹配看见 Task 2、理想解 A、复用——**无双实例**（反事实二）。
3. **恢复 systemReady 的 HOME 链**：A 根本不会以 STANDARD 根任务形态抢跑，拓扑回归旧开机形态（反事实三，姊妹篇 §8.3）。
4. **goLauncher 意图加 DEFAULT**（categories=[HOME, DEFAULT]）：size==1 闸拒收，isHomeIntent 与 isMainIntent 双假——记录走 UNDEFINED 后补 STANDARD 路径，findTask 泛匹配看见 Task 2、理想解 A、复用——**同样无双实例**【源码事实推演，本篇新增反事实】。代价是它不再被 WM 认作桌面请求（home 进程登记、回桌面语义全失），属「事故消失但语义错误」的修法，不可投产，仅作机制演示。

四例共同指向：**改 categories 集合任一处都能翻转结局，改 launchMode 一处都不能**（姊妹篇 §5.6 问答）——因为类型闸在 launchMode 逻辑之前生效。

### 10.4 验证清单（Intent 域）

- `adb shell dumpsys package com.yadea.launcher`：核对 MainActivity 过滤器的 actions/categories/priority 与 manifest 一致（本篇 §3.3 引文）。
- `adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME` 与 `-c android.intent.category.LAUNCHER` 各打一次：对照 `wm_create_task` 事件数与 Task 类型（dumpsys activity containers），复现「同组件两任务」。
- 意图加 FLAG_DEBUG_LOG_RESOLUTION（0x8）启动：抓 buildResolveList 五关与 chooseBestActivity 的 VERBOSE 日志，验证 6.4/6.5 节。
- 双实例疑云排查四步：wm_create_task 计数、wm_new_intent 的 Token、dumpsys 任务类型、wm_on_create_called 的 ident 唯一性（姊妹篇 §8.11 清单，本篇提供其 Intent 域依据）。

### 10.5 终章回扣

回到 ch0 主线图：一个 Intent 被构造（ch1 十六口袋）、按十四槽过江（ch8）、在匹配器里过三段（ch5）、在解析链里过五关并回写组件（ch6）、在身份阶梯上被度量（ch7）——最后，它的 categories 集合被 WM 再读一次，这一次读出的不是匹配资格而是**任务类型**（ch2/ch3 收口）。雅迪事故的五环因果链（姊妹篇 §8.10）在 Intent 域的完整表述是：**同一份形状清单、两套消费规则、一个 size==1 的互斥闸、一次 HOME 真空期的抢跑、一条被类型闸截断的 singleTask 承诺**。Intent 无罪，形状即命运。

（全文完）
