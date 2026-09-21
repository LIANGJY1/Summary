#!/usr/bin/env python3
"""分屏截图与报告生成的回归测试。"""

import importlib.util
import os
import sys
import tempfile
import unittest
from pathlib import Path


SCREENSHOT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCREENSHOT_DIR))


def load_module(name: str, filename: str):
    spec = importlib.util.spec_from_file_location(name, SCREENSHOT_DIR / filename)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


compare = load_module("compare_split_report_for_test", "compare_split_report.py")
capture = load_module("capture_appstore_splitscreenshots_for_test", "capture_appstore_splitscreenshots.py")


class ScanIndexedTests(unittest.TestCase):
    def test_duplicate_index_uses_most_recent_capture(self):
        """分类迁移留下旧文件时，同序号必须采用最近生成的截图。"""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            old_capture = root / "mine" / "017_设置页.png"
            new_capture = root / "setting" / "017_设置页.png"
            old_capture.parent.mkdir()
            new_capture.parent.mkdir()
            old_capture.write_bytes(b"old")
            new_capture.write_bytes(b"new")
            os.utime(old_capture, ns=(1_000_000_000, 1_000_000_000))
            os.utime(new_capture, ns=(2_000_000_000, 2_000_000_000))

            indexed = compare.scan_indexed(root)

            self.assertEqual(new_capture, indexed["017"])


class CopyAssetTests(unittest.TestCase):
    def test_existing_report_asset_is_replaced_even_when_its_mtime_is_newer(self):
        """报告生成时间不能阻止内容已变化的源截图覆盖旧资产。"""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / "017_设置页.png"
            assets = root / "report" / "assets"
            destination = assets / "017_cap.png"
            assets.mkdir(parents=True)
            source.write_bytes(b"new capture")
            destination.write_bytes(b"old report asset")
            os.utime(source, ns=(1_000_000_000, 1_000_000_000))
            os.utime(destination, ns=(2_000_000_000, 2_000_000_000))

            compare.copy_asset(source, assets, destination.name)

            self.assertEqual(source.read_bytes(), destination.read_bytes())


class SplitCategoryTests(unittest.TestCase):
    def test_mine_and_setting_pages_have_separate_categories(self):
        """我的应用与设置页必须可独立选择并写入不同输出目录。"""
        groups = capture.build_groups()
        capture.attach_tasks(groups)
        tasks = [task for group in groups for task in group.tasks]

        self.assertEqual("mine", groups[3].category)
        self.assertEqual("setting", groups[4].category)
        self.assertEqual({"mine"}, {task.category for task in tasks[12:16]})
        self.assertEqual({"setting"}, {task.category for task in tasks[16:20]})


if __name__ == "__main__":
    unittest.main()
