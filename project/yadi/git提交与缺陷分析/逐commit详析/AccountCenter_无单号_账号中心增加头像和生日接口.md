# 无单号 · 账号中心增加头像和生日接口

- **提交**：`f12617a9` | 2026-08-10 | liqingqing | AccountCenter | feature
- **关联单**：无

## 需求/目标
用户信息模型扩展头像（picUrl）与生日（birthDate）字段：个人中心用 Glide 展示圆形头像，头像 URL 与生日落全局设置供跨进程消费，登出时清理。

## 实现结构
- `data/model/UserInfoResponse.kt`：新增 `picUrl`、`birthDate` 两个 `@SerializedName` 字段
- `utils/AccountProfileUtils.kt`（新增）：`save(userInfo)` 把头像 URL 与生日写 Settings.Global；`clear()` 置空。类注释明确"Settings.Global 只存字符串，图片由各消费方按 URL 经 Glide 缓存加载"
- `CenterActivity.kt`：用户信息回调里 Glide 加载头像（placeholder/error/fallback 三级兜底默认图标 + `circleCrop()`），并 `AccountProfileUtils.save`；退出登录 `AccountProfileUtils.clear()`
- `LoginDialogActivity.kt`：登录成功同样 `save`
- `activity_center.xml`：头像 ImageView 补 id 与 scaleType
- `Constants.kt`：新增 `USER_AVATAR_URL`/`USER_BIRTH_DATE` 键

## 关键代码
```diff
--- a/application/AccountCenter/src/main/java/com/yadea/accountcenter/ui/center/CenterActivity.kt
@@ -152,7 +154,18 @@
+        binding?.ivUserAvatar?.let { avatarView ->
+            Glide.with(this)
+                .load(userInfo.picUrl)
+                .placeholder(R.mipmap.nomal_user_icon)
+                .error(R.mipmap.nomal_user_icon)
+                .fallback(R.mipmap.nomal_user_icon)
+                .circleCrop()
+                .into(avatarView)
+        }
+        AccountProfileUtils.save(userInfo)
```
实现讲解：与 `2aa850ca` 的 `AccountRoleUtils` 同构：小状态封装成 save/clear 对称的工具单例，登录/个人中心/登出三处成对调用；图片本体不落盘，只存 URL，消费方按 URL 走 Glide 缓存，避免大图进 settings_global.xml。

## 复盘与要点
- "URL 进全局设置、图像进图片库缓存"的职责切分清晰，是跨进程共享用户资料的低成本方案。
- Glide 三级占位（placeholder/error/fallback）+ circleCrop 的写法可直接复制到其他头像位。
- 遗留风险：生日仅持久化未展示 UI（本提交无生日控件），消费方待后续提交补齐。
