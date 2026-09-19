# SIR-3649 · 控制中心显示账号信息（功能实现，标题为 [fixbug] 实为需求开发）

- **提交**：`046595a1` | 2026-08-28 | caohongliang | SystemUI/AccountCenter | **feature（需求"账号信息展示"的实现，虽带 [fixbug] 前缀但无缺陷语义，wh/ho=NA，缺陷库为空）**
- **缺陷库**：未关联缺陷（defs 为空）

## 问题
不是缺陷修复。需求：控制中心（快捷面板）标题区展示当前登录账号的昵称与头像，未登录时回退默认样式。

## 改动概要
- `AccountProfileUtils`（AccountCenter）：登录成功 `save(UserInfoResponse)` 时把昵称写入 `Settings.Global`（新键 `Constants.CACHE.USER_NICK_NAME`），`clear()` 退出登录时清空，与头像 URL 等字段同样生命周期。
- `QuickSettingFragment`（SystemUI）：新增 `refreshUserProfile()`——按 `USER_ID > 0` 判定登录态；已登录且昵称非空时把面板标题 `quickTitleTv` 设为昵称，否则回退"快捷控制"文案；头像 `ivUserIcon` 用 Glide 加载 `USER_AVATAR_URL`，`circleCrop()` 圆形裁剪，placeholder/error/fallback 统一默认图，未登录 `Glide.clear()` 后复位默认图。面板可见（`onPageVisible`）与数据刷新两处入口调用。
- 资源：`quick_control` 字符串在中英文资源中均改为空格占位（标题默认不再显示"快捷控制/Quick Control"，由代码动态决定）；`quick_cavan_control` 保留原语义供其他界面使用。

## 评注
几个值得注意的实现细节：昵称/头像走 Settings.Global 跨进程共享，AccountCenter 写、SystemUI 读，避免进程间拉起依赖；每次面板可见都刷新（`onPageVisible` → `refreshUserProfile()`），保证在其他页面改头像后回来及时更新；`refreshUserProfile` 里区分"未登录"（清头像复位）与"已登录昵称为空"（仅标题回退）两种情形。风险点是 `quick_control` 字符串被复用为"空占位"，语义与命名不再一致。

## 复盘经验
- 跨模块（AccountCenter 产数据、SystemUI 展示）的轻量账号信息用 Settings.Global 键值对最省事，但键名要集中在 Constants 统一管理。
- 展示型需求也要写"回退链"：登录态→昵称→默认标题，头像→默认图，每层都有兜底。
- 占位字符串改名要谨慎，`quick_control` 变空格后其原语义已丢失，更好做法是新增 `quick_title_placeholder` 资源。
