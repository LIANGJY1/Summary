# 无单号 · 修改 wifi 界面（刷新图标间距修正 + 清理无用系统属性方法）

- **提交**：`10e3adea` | 2026-07-02 | dufan | Setting | feature
- **关联单**：无

## 需求/目标
微调 Setting 的 Wi-Fi/连接列表界面：去掉刷新图标上多余的 3dp 上边距，同时删除 CommonTools 中不再被使用的 `getSentryMode()` 系统属性读取方法。

## 实现结构
- 修改 `application/Setting/src/main/res/layout/item_connect_title.xml`：`iv_refresh` 删除 `layout_marginTop`（1 行）。
- 修改 `component/CommonTools/src/main/java/com/yadea/common/utils/SysPropUtils.kt`：删除 `getSentryMode()`（4 行，含 `CONFIG_SENTRY_MODE` 用点）。

纯删除型小提交：布局微调 + 死代码清理，无数据流变化。

## 关键代码
```diff
--- a/application/Setting/src/main/res/layout/item_connect_title.xml
@@ -27,7 +27,6 @@
             android:id="@+id/iv_refresh"
             android:layout_width="@dimen/dp_24"
             android:layout_height="@dimen/dp_24"
-            android:layout_marginTop="@dimen/dp_3"
             android:src="@drawable/ic_refresh" />
```

```diff
--- a/component/CommonTools/src/main/java/com/yadea/common/utils/SysPropUtils.kt
@@ -134,8 +134,4 @@
-    fun getSentryMode(): Int {
-        return SystemProperties.getInt(CONFIG_SENTRY_MODE, -1)
-    }
```

实现讲解：`item_connect_title.xml` 是设置页"连接类目"条目的公共 item 布局，刷新图标与标题的垂直对齐靠 margins 微调，这次去掉 3dp 说明设计稿重新校准。`SysPropUtils` 是系统属性统一读取的 Façade，删除 `getSentryMode()`（哨兵模式）表明该功能在当前车型配置上被裁撤，连同属性读取入口一并下线。

## 复盘与要点
- 属性读取集中在 `SysPropUtils` object 中，删除方法即可彻底下线一个系统能力入口，封装收敛带来的清理红利。
- 纯 UI 间距修正和死代码清理合在一个提交里，标题只写"修改wifi界面"，对回溯 `getSentryMode` 去向不太友好——建议清理类改动单独提交。
- 删除公共方法需确认无调用点，这类跨模块删除（Setting 提交改 CommonTools）依赖全局搜索兜底。
