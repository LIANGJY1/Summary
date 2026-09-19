# 无单号 · 修改手车互联连接状态同步（Launcher 侧互斥）

- **提交**：`5c7021bf` | 2026-07-20 | dufan | Launcher | feature
- **关联单**：无

## 需求/目标
修正手车互联（CarPlay / HiCar / 亿连 CarLink）连接状态互斥展示：任一通道连接成功时，同步清掉另外两个通道的连接标志，避免状态标志与 UI 文案不一致。

## 实现结构
单文件 14+/10-：`function/applist/CarConnectFragment.kt` 的 `onDeviceStatusChanged` when 分支调整：
- CARPLAY/HICAR/CARLINK 三个分支在 `isConnected == true` 时统一增加 `isXxxConn = false` 的对另两路标志复位；
- CARLINK 分支从 `HICAR` 之前移到之后（原代码 CARLINK 分支处于中间，HICAR 分支落在其后、`else -> {}` 前）；
- 该修复实质是状态同步缺陷的修补（标题写 feature，按 diff 定性为状态互斥逻辑补全）。

## 关键代码
```diff
--- a/application/Launcher/src/main/java/com/yadea/launcher/function/applist/CarConnectFragment.kt
             DeviceConnectManager.CARPLAY -> {
                 isCarPlayConn = isConnected
                 if (isConnected) {
+                    isHicarConn = false
+                    isCarLinkConn = false
                     mBinding.tvCarplayStatus.setText(R.string.status_connected)
                     mBinding.tvHicarStatus.setText(R.string.status_disconnected)
                     mBinding.tvCarlinkStatus.setText(R.string.status_disconnected)
```
```diff
             DeviceConnectManager.HICAR -> {
                 isHicarConn = isConnected
                 if (isConnected) {
+                    isCarPlayConn = false
+                    isCarLinkConn = false
                     mBinding.tvHicarStatus.setText(R.string.status_connected)
                     mBinding.tvCarlinkStatus.setText(R.string.status_disconnected)
                     mBinding.tvCarplayStatus.setText(R.string.status_disconnected)
```
实现讲解：原来"另一个通道断开"的广播事件若未到达，Boolean 标志会停留在 true，与三行 UI 文案（强制写 disconnected）脱节，导致依赖标志位而非文案的逻辑（如菜单显隐、状态上报）出错。修复思路是把"连接"视为三选一事件，连接成功即灭掉其余两路的标志。

## 复盘与要点
- 一个枚举状态被三个独立 Boolean 表示是本类 bug 的根源；更彻底的做法是收敛为单一 `currentConnectType` 字段，UI 一律由它派生。
- 同样的互斥修复同日在 Setting 侧 `70302dd8`/`13ba9d1d` 也出现，说明手车互联三通道互斥是两端共同的契约，修复需成对验证。
- `else -> {}` 兜底分支保留，未知 deviceType 依旧静默忽略，日志可再加强。
