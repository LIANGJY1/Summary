# SIR-7984 · 账号中心页面比其他应用多出一节（缺顶部边距）

- **提交**：`5d748435` | 2026-09-10 | liqingqing | AccountCenter | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
打开账号中心，其页面（背景卡片）比其他应用整体多出一节——顶部顶到了窗口最上沿，视觉高度与其他应用不一致。

## 根因分析
车机端各应用遵循统一的窗口内边距规范：应用背景卡片相对窗口顶部要留出固定间距（配合系统栏/统一应用框），其他应用都在根布局上设了 `layout_marginTop`。AccountCenter 的 `activity_center.xml` 与 `activity_login.xml` 的根布局 `root_layout`（match_parent + `@drawable/bg_main` 背景）**漏掉了这个 10dp 顶部边距**（缺陷库记"高度不对/修改页面高度"），背景直接铺满窗口，比别的应用"高出一截"。

## 关键代码修改
改动文件：application/AccountCenter/src/main/res/layout/activity_center.xml、application/AccountCenter/src/main/res/layout/activity_login.xml（2 文件 +2/-0）
```diff
--- application/AccountCenter/src/main/res/layout/activity_center.xml
         android:id="@+id/root_layout"
         android:layout_width="match_parent"
         android:layout_height="match_parent"
+        android:layout_marginTop="@dimen/dp_10"
         android:background="@drawable/bg_main"
--- application/AccountCenter/src/main/res/layout/activity_login.xml
         android:id="@+id/root_layout"
         android:layout_width="match_parent"
         android:layout_height="match_parent"
+        android:layout_marginTop="@dimen/dp_10"
         android:background="@drawable/bg_main"
```

## 为什么能修复
两个页面（已登录中心页、登录页）根布局补上与其他应用一致的 10dp 顶部外边距后，背景卡片顶部与其他应用对齐，"多出一节"的观感消除。改动仅两行、无逻辑风险；需要注意 activity_login 页若由独立窗口/弹窗承载，边距语义应与其宿主规范核对，避免重复叠加。

## 复盘与经验
- 车机多应用"页面框架一致性"依赖每个应用自觉遵守统一的根布局边距规范，最有效的防漏手段是把统一边距沉到公共基类布局或主题（windowBackground/ 内嵌 include），而不是各 layout 手抄。
- "和别的应用不一样"类缺陷，修复前先找参照应用比对根布局属性差异，一行 diff 往往就定位。
- 登录/主页两个入口布局都要改，只改一处会造成"登录页对、中心页错"的另一半 bug——同类页面属性变更必须批量核查。
