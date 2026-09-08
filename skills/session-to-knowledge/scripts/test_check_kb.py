#!/usr/bin/env python3
"""回归夹具：验证 knowledge-base 结构校验的可观察契约。"""

import importlib.util
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("check_kb.py")
spec = importlib.util.spec_from_file_location("check_kb", SCRIPT)
check_kb = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(check_kb)


def write_doc(root: Path, name: str, body: str) -> Path:
    path = root / name
    path.write_text(body, encoding="utf-8")
    return path


BASE = """# 示例大类\n\n> 示例知识域。**维护者**：session-to-knowledge\n\n## 边界\n收可迁移知识。\n\n## 规则\n条目按规则书写。\n\n## 目录\n{toc}\n\n## {title}\n\n**现象**：x\n\n**原因**：y\n\n**误区**：z\n\n**解决方案**：q\n\n**启示**：r\n"""


class CheckKbContractTests(unittest.TestCase):
    def test_wrong_anchor_is_reported(self):
        with tempfile.TemporaryDirectory() as d:
            path = write_doc(Path(d), "demo.md", BASE.format(
                toc="- [命题](#错误锚点)", title="命题"))
            issues = check_kb.check_doc(path, profile="entry")
            self.assertTrue(any("锚点" in issue for issue in issues))

    def test_duplicate_heading_and_toc_are_reported(self):
        with tempfile.TemporaryDirectory() as d:
            body = BASE.format(toc="- [命题](#命题)", title="命题")
            body += "\n## 命题\n\n**现象**：重复\n"
            issues = check_kb.check_doc(write_doc(Path(d), "demo.md", body), profile="entry")
            self.assertTrue(any("重复" in issue for issue in issues))

    def test_duplicate_toc_entry_is_reported(self):
        with tempfile.TemporaryDirectory() as d:
            body = BASE.format(toc="- [命题](#命题)\n- [命题](#命题)", title="命题")
            issues = check_kb.check_doc(write_doc(Path(d), "demo.md", body), profile="entry")
            self.assertTrue(any("目录重复" in issue for issue in issues))

    def test_entry_skeleton_is_required(self):
        with tempfile.TemporaryDirectory() as d:
            path = write_doc(Path(d), "demo.md", "# 示例\n\n> 维护者：x\n\n## 目录\n")
            issues = check_kb.check_doc(path, profile="entry")
            self.assertTrue(any("缺少 `## 边界`" in issue for issue in issues))

    def test_bare_relative_link_is_checked(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            write_doc(root, "target.md", "# target")
            body = BASE.format(toc="- [命题](#命题)", title="命题")
            body += "\n参见 [目标](missing.md)。\n"
            issues = check_kb.check_doc(write_doc(root, "demo.md", body), profile="entry")
            self.assertTrue(any("链接" in issue for issue in issues))

    def test_language_profile_does_not_require_entry_skeleton(self):
        with tempfile.TemporaryDirectory() as d:
            path = write_doc(Path(d), "note.md", "# 学习笔记\n\n参见 [目标](target.md)。")
            write_doc(Path(d), "target.md", "# target")
            self.assertEqual(check_kb.check_doc(path, profile="language"), [])


if __name__ == "__main__":
    unittest.main()
