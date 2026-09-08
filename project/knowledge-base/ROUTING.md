# 知识路由（ROUTING）

> 沉淀一条知识前先查此表定**唯一候选大类**；这条知识够不够格进它，以该大类头部「边界」与「规则」为准。
> 维护者列表示责任/复核标记，不改变主题路由；所有写入者遵守共享知识库契约。新立大类后必须回填本表。

## 主判据链

1. 讲"编程语言本身"——某门语言的语法、类型系统、集合、并发、反射等语言特性与用法 → 先入 [language/](./language/)，再按语言分发到子目录（`C++/` `java/` `kotlin/`；新语言建同级目录）
2. 讲"怎么做"——与设计/构建 SDK 直接相关的模式与可执行做法 → [sdk-design.md](./sdk-design.md)
3. 讲"怎么想"——核心矛盾、权衡、可迁移的思维方式 → [design-principles.md](./design-principles.md)
4. 讲"怎么标注"——注释模式、结构组织、表达技巧等注解技术 → [annotation-techniques.md](./annotation-techniques.md)
5. Android UI 域（组件运行期修改 / 资源变体 / 约束适配）"改不动、改不对"的坑与解法 → [android-ui.md](./android-ui.md)
6. Android Provider 域（ContentProvider/系统级 Provider/ContentObserver 跨进程共享与监听）的机制事实与用法坑 → [android-provider.md](./android-provider.md)
7. Android 音频域（音频焦点/播放配置/音源识别）的机制事实与踩坑 → [android-audio.md](./android-audio.md)
8. 都不中 → 向用户提议新立大类（骨架与流程见 session-to-knowledge；引言 blockquote 标维护者、头部立「边界」）→ **建完回填本表**

## 重叠主题的裁决顺序

当一条候选同时命中多个判据，不按维护者、文件名或示例语言猜，固定按以下顺序裁决：

1. **知识内核**：语言机制优先 `language/`；具体 UI/Provider 运行期坑优先对应 Android 域。
2. **主要复习动作**：读者要执行一套 SDK/组件做法 → `sdk-design.md`；要理解取舍/矛盾 → `design-principles.md`；要学习如何写注释 → `annotation-techniques.md`。
3. **目标头部边界**：候选必须满足目标文档「边界」与「规则」；否则退回上一步继续判断。
4. **仍无法唯一决定**：列出候选及差异，暂停写入并询问用户；不复制到多个文件解决不确定性。

典型反例：Kotlin 示例讲“订阅后对账初值”时，知识内核是跨语言工程模式，去 `design-principles.md`，不进 `language/kotlin/`；Android View 的构造期属性未被 setter 重新消费时，知识内核是 UI 运行期坑，去 `android-ui.md`；同一条若另有可迁移设计权衡，只在 `design-principles.md` 写不同视角并互链。

## 路由表

| 大类 | 维护者 | 一句话判据 | 主题相邻时去哪 |
|---|---|---|---|
| [language/](./language/) | session-to-knowledge | 编程语言本身的知识（语法/类型/集合/并发/反射等特性与用法），先入本目录再按语言分发至子目录 | 语言无关的通用思想 → design-principles.md；SDK/组件设计 → sdk-design.md |
| [sdk-design.md](./sdk-design.md) | source-annotator | SDK/组件设计模式与可执行做法（含代码实例） | 重在权衡与思维方式 → design-principles.md |
| [design-principles.md](./design-principles.md) | source-annotator | 核心矛盾、权衡、可迁移的思考方式 | 重在做法与实例 → sdk-design.md |
| [annotation-techniques.md](./annotation-techniques.md) | source-annotator | 怎么写好源码注释的技术与实例 | 具体库的标注成果 → 该库沉淀文档（不进知识库） |
| [android-ui.md](./android-ui.md) | session-to-knowledge | Android UI 域踩坑与解法（跨同类项目仍成立） | 跨库可迁移的设计模式 → sdk-design.md |
| [android-provider.md](./android-provider.md) | session-to-knowledge | 跨进程数据共享与监听（Provider/系统 Provider/Observer）的架构事实与踩坑 | UI 域坑 → android-ui.md；权衡思维 → design-principles.md |
| [android-audio.md](./android-audio.md) | session-to-knowledge | Android 音频域（焦点/播放配置/音源识别）的机制事实与踩坑 | Provider/Observer → android-provider.md；权衡思维 → design-principles.md |

## 规则

- 本表只答"候选是哪个大类"；**准入**（复习者测试、收/不收细则、条目格式、归并规则）一律以目标大类头部为准。主题命中后不得因维护者不同另建近义大类。
- `language/` 是目录型特例（按语言分子目录的学习笔记，非条目大类）：二级分发、新语言准入与文件归并以其 [README](./language/README.md) 为准；语言子目录变动只登记其「目录」节，无需回填本表。
- 同一条知识只有在各目标大类明确要求不同视角时才分别写入，且互相链接、不复制；只够格一份时只写一份。
- 粒度相同的判据句子不得在本表与大类头部重复出现；发现重复即漂移，以头部为准修本表。
- 本表不收：skill 级路由（在各 skill 的 description 里）、条目内容规范（在大类「规则」节）。
- **回填**：新立大类完成判据之一 = 本表新增一行（大类 | 维护者 | 一句话判据 | 相邻去向）；大类废弃时删行并留一行说明去处。本表保持一屏内，超了先删后加。
