#!/usr/bin/env python3
"""Remove leading '//' from each line in clipboard content."""

import shutil
import subprocess
import sys


def get_clipboard() -> str:
    if shutil.which('xclip'):
        result = subprocess.run(
            ['xclip', '-selection', 'clipboard', '-o'],
            capture_output=True, text=True
        )
        if result.returncode == 0:
            return result.stdout
    if shutil.which('xsel'):
        result = subprocess.run(
            ['xsel', '--clipboard', '--output'],
            capture_output=True, text=True
        )
        if result.returncode == 0:
            return result.stdout
    return ''


def set_clipboard(text: str) -> bool:
    if shutil.which('xclip'):
        proc = subprocess.Popen(
            ['xclip', '-selection', 'clipboard', '-i'],
            stdin=subprocess.PIPE, text=True
        )
        proc.communicate(text)
        return proc.returncode == 0
    if shutil.which('xsel'):
        proc = subprocess.Popen(
            ['xsel', '--clipboard', '--input'],
            stdin=subprocess.PIPE, text=True
        )
        proc.communicate(text)
        return proc.returncode == 0
    return False


def remove_leading_slashes(text: str) -> str:
    lines = text.splitlines()
    processed = []
    for line in lines:
        stripped = line.lstrip()
        leading_spaces = line[:len(line) - len(stripped)]
        if stripped.startswith('//'):
            rest = stripped[2:]
            if rest.startswith(' '):
                rest = rest[1:]
            line = leading_spaces + rest
        processed.append(line)
    return '\n'.join(processed)


def main() -> None:
    content = get_clipboard()
    if not content:
        print("剪贴板为空，请先复制要处理的内容。")
        sys.exit(1)

    result = remove_leading_slashes(content)

    if set_clipboard(result):
        print("已处理并写回剪贴板，可直接粘贴。")
    else:
        print("未找到 xclip/xsel，请手动复制下方结果：")

    print('-' * 40)
    print(result)


if __name__ == '__main__':
    main()
