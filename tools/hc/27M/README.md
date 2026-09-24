# hc_log_auto.py — 27HM 车机日志一键解密解压

Linux 原生替代「解压压缩包 → wine 运行 27HM_Log解密解压工具_V5.exe」的手工流程，
无需 wine。解密算法从该工具（PyInstaller 打包的 Python 3.12 程序）中还原，
已与工具实际输出目录 diff 逐字节比对一致（2026-09-24，两个真实样本 + zip 全链路）。

## 用法

```bash
python3 hc_log_auto.py <压缩包或已解压目录> [--key dev|prod]
```

- 压缩包支持 zip / 7z / tar(.gz/.bz2/.xz) / rar（依赖系统 `7z`、`unrar`）
- zip 内中文文件名自动修复 GBK 乱码
- 输出：`<Logcat 所在目录>/解密_解压_Logcat/`，命名与 Windows 工具一致

## 原理

`.enc` 文件按 32784 字节分块 AES-128-CBC 解密（固定 IV、每块独立 PKCS7
unpad、unpad 失败回写原文），结果若为 `.lz4` 再用 lz4.frame 解压。

## 依赖与密钥

- `pip3 install cryptography lz4`
- 密钥配置在脚本顶部 `AES_CONFIGS`：dev（开发秘钥）已内置；prod（量产秘钥）
  待拿到后填入，用 `--key prod` 切换。

## 注意

脚本内硬编码了开发秘钥原文，按本仓 AGENTS.md「密钥不入库」约定，
**git 提交前需先处理**：或将该文件加入 `.gitignore` 保持本地化，
或将密钥外置（env/外部配置）后再入库。
