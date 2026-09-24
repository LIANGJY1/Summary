#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""HC 27HM Logcat 日志一键解密解压（Linux 原生，无需 wine）

替代「解压压缩包 → wine 运行 27HM_Log解密解压工具_V5.exe」的手工流程。
解密算法从该工具（PyInstaller 打包的 Python 3.12 程序）中还原，逐字节一致，
并已与工具的实际输出目录 diff 比对验证。

用法:
    python3 hc_log_auto.py <压缩包或已解压目录> [--key dev|prod]

支持输入:
    *.zip / *.7z / *.tar[.gz/.bz2/.xz] / *.rar 压缩包，或已解压的文件夹
输出:
    <Logcat 所在目录>/解密_解压_Logcat/   （与 Windows 工具输出命名一致）

依赖: pip3 install cryptography lz4   （7z/rar 需系统装有 7z、unrar）
"""

import argparse
import os
import shutil
import subprocess
import sys
import tarfile
import zipfile
from pathlib import Path

from cryptography.hazmat.backends import default_backend
from cryptography.hazmat.primitives import padding
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
import lz4.frame

# ===== 密钥配置（与工具内 AES_CONFIGS 一致；拿到量产秘钥后填到 prod） =====
AES_CONFIGS = {
    "dev": {
        "name": "27HM 开发秘钥",
        "key": "a5b701df22857a120a03a470d2143a80",
        "iv": "00000000000000000000000000000000",
    },
    "prod": {
        "name": "27HM 量产秘钥",
        "key": "",   # TODO: 填入量产秘钥 hex
        "iv": "",    # TODO: 填入量产 IV hex
    },
}

CHUNK_SIZE = 32784  # 与工具一致：32768 载荷 + 16 填充


def decrypt_aes_cbc(input_path: Path, output_path: Path, key: bytes, iv: bytes) -> bool:
    """按 32784 字节分块 AES-CBC 解密，每块独立 PKCS7 unpad，固定 IV。

    与工具实现一致：unpad 失败（ValueError）时回写未去填充的解密数据。
    """
    try:
        with open(input_path, "rb") as fin, open(output_path, "wb") as fout:
            while True:
                chunk = fin.read(CHUNK_SIZE)
                if not chunk:
                    break
                decryptor = Cipher(algorithms.AES(key), modes.CBC(iv),
                                   backend=default_backend()).decryptor()
                decrypted = decryptor.update(chunk) + decryptor.finalize()
                try:
                    unpadder = padding.PKCS7(algorithms.AES.block_size).unpadder()
                    out = unpadder.update(decrypted) + unpadder.finalize()
                except ValueError:
                    out = decrypted
                fout.write(out)
        return True
    except Exception as e:
        print(f"  [解密失败] {input_path.name}: {e}")
        if output_path.exists():
            output_path.unlink()
        return False


def decompress_lz4(input_path: Path, output_path: Path) -> bool:
    try:
        with open(input_path, "rb") as fin:
            data = lz4.frame.decompress(fin.read())
        with open(output_path, "wb") as fout:
            fout.write(data)
        return True
    except Exception as e:
        print(f"  [解压失败] {input_path.name}: {e}")
        return False


def extract_archive(archive: Path, dest: Path) -> None:
    """解压压缩包到 dest，zip 中文文件名自动修复 GBK 乱码。"""
    dest.mkdir(parents=True, exist_ok=True)
    ext = archive.suffix.lower()
    if ext == ".zip":
        with zipfile.ZipFile(archive) as zf:
            for zi in zf.infolist():
                name = zi.filename
                if not (zi.flag_bits & 0x800):  # 无 UTF-8 标记 → Windows 下多为 GBK
                    try:
                        name = zi.filename.encode("cp437").decode("gbk")
                    except (UnicodeEncodeError, UnicodeDecodeError):
                        pass
                target = dest / name
                if zi.is_dir():
                    target.mkdir(parents=True, exist_ok=True)
                    continue
                target.parent.mkdir(parents=True, exist_ok=True)
                with zf.open(zi) as src, open(target, "wb") as fout:
                    shutil.copyfileobj(src, fout)
    elif ext == ".7z":
        subprocess.run(["7z", "x", "-y", f"-o{dest}", str(archive)],
                       check=True, stdout=subprocess.DEVNULL)
    elif ext == ".rar":
        subprocess.run(["unrar", "x", "-o+", str(archive), str(dest) + "/"],
                       check=True, stdout=subprocess.DEVNULL)
    elif ext in (".tar", ".gz", ".bz2", ".xz"):
        with tarfile.open(archive) as tf:
            tf.extractall(dest)
    else:
        raise ValueError(f"不支持的压缩格式: {archive.name}")


def find_source_dir(base: Path) -> Path:
    """定位含 .enc 文件的最深公共目录（通常即 Logcat 目录）。"""
    encs = [p for p in base.rglob("*.enc") if p.is_file()]
    if not encs:
        raise FileNotFoundError(f"{base} 下未找到任何 .enc 文件")
    parents = [p.parent for p in encs]
    return Path(os.path.commonpath([str(p) for p in parents]))


def process(source_dir: Path, key: bytes, iv: bytes) -> None:
    output_dir = source_dir.parent / f"解密_解压_{source_dir.name}"
    if output_dir.exists():
        print(f"删除旧输出目录: {output_dir}")
        shutil.rmtree(output_dir)
    output_dir.mkdir(parents=True)

    decrypted = decompressed = failed = 0
    for enc in sorted(source_dir.rglob("*.enc")):
        rel = enc.relative_to(source_dir)
        mid = output_dir / rel.with_suffix("")          # 去掉 .enc
        mid.parent.mkdir(parents=True, exist_ok=True)
        if not decrypt_aes_cbc(enc, mid, key, iv):
            failed += 1
            continue
        decrypted += 1
        if mid.suffix.lower() == ".lz4":                 # 再去掉 .lz4
            final = mid.with_suffix("")
            if decompress_lz4(mid, final):
                mid.unlink()
                decompressed += 1
            else:
                failed += 1

    print(f"完成: 解密 {decrypted} 个, 解压 {decompressed} 个 .lz4, 失败 {failed} 个")
    print(f"输出目录: {output_dir}")
    if failed:
        sys.exit(1)


def main() -> None:
    ap = argparse.ArgumentParser(description="27HM Logcat 日志一键解密解压")
    ap.add_argument("input", help="压缩包或已解压目录")
    ap.add_argument("--key", choices=list(AES_CONFIGS), default="dev",
                    help="密钥类型 (默认 dev)")
    args = ap.parse_args()

    cfg = AES_CONFIGS[args.key]
    if not cfg["key"] or not cfg["iv"]:
        sys.exit(f"错误: {cfg['name']} 尚未配置，请在脚本顶部 AES_CONFIGS 中填入 key/iv")
    key, iv = bytes.fromhex(cfg["key"]), bytes.fromhex(cfg["iv"])

    src = Path(args.input).expanduser().resolve()
    if not src.exists():
        sys.exit(f"错误: 输入不存在: {src}")

    if src.is_dir():
        source_dir = find_source_dir(src)
    else:
        extract_dir = src.parent / src.stem
        print(f"解压 {src.name} -> {extract_dir}")
        try:
            extract_archive(src, extract_dir)
        except Exception as e:
            sys.exit(f"解压失败: {e}")
        source_dir = find_source_dir(extract_dir)

    print(f"密钥: {cfg['name']}    日志目录: {source_dir}")
    process(source_dir, key, iv)


if __name__ == "__main__":
    main()
