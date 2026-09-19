# 无单号 · CommonTools 黑白模式适配（SkinSwitchCardView 退出旧换肤体系）

- **提交**：`7366e05d` | 2026-06-30 | dufan | CommonTools | feature
- **关联单**：无

## 需求/目标
公共控件 `SkinSwitchCardView`（带二次确认覆盖层的 switch 开关卡片）从运行时换肤框架（ChangeSkinManager 按名取资源）切换到 uiMode 语义色资源体系。

## 实现结构
仅 1 个 Java 文件（+4/-3 行）：控件刷新方法 `updateXXX` 中三处资源获取方式替换——标题/副标题颜色由 `ChangeSkinManager.getColorResource(context, "text_default_color"/"text_secondary_color")` 改为 `ResourceUtils.getColor(R.color.text_default_default/press)`；提示图标由 `ChangeSkinManager.getDrawableId(context,"icon_about_tip")` + setImageResource 改为 `ResourceUtils.getDrawable(R.drawable.ic_about_info)` + setImageDrawable。switch 轨道仍走 ChangeSkinManager（未迁移完）。

数据流：uiMode 切换 → 语义色资源重解析 → 控件刷新时读取即得对应模式颜色；不再依赖换肤框架维护的皮肤包映射表。

## 关键代码
```diff
# component/CommonTools/src/main/java/com/yadea/common/widgets/SkinSwitchCardView.java
-        textTitle.setTextColor(ChangeSkinManager.getInstance().getColorResource(getContext(),"text_default_color"));
-        textSubtitle.setTextColor(ChangeSkinManager.getInstance().getColorResource(getContext(),"text_secondary_color"));
-        ivTip.setImageResource(ChangeSkinManager.getInstance().getDrawableId(getContext(),"icon_about_tip"));
+        textTitle.setTextColor(ResourceUtils.getColor(R.color.text_default_default));
+        textSubtitle.setTextColor(ResourceUtils.getColor(R.color.text_default_press));
+        ivTip.setImageDrawable(ResourceUtils.getDrawable(R.drawable.ic_about_info));
```

实现讲解：旧方案用字符串资源名做运行时映射（`getColorResource("text_default_color")`），编译期不可查、黑白映射表要人工维护；换成 `R.color` 常量引用后编译器兜底，夜间色由 `values-night` 自动提供。这 3 行是整套换肤迁移（对照 b58a35fc 删除 skin:tag、b226c24f 删旧色板）的一个缩影。

## 复盘与要点
- 可复用手法：从字符串资源名反射式取资源迁移到 R 常量，逐控件替换即可，不需要一次性切换框架——编译通过即迁移正确。
- 遗留风险：同名 `switch_track` 仍走 ChangeSkinManager，控件内两套机制并存；`SkinSwitchCardView` 类名里的 "Skin" 已名不副实，迁移收尾后应重命名避免误导。
- `ChangeSkinManager` 若无其他使用方，可在适配完成后整体下线，减一份运行时反射开销。
