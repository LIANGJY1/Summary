#!/usr/bin/env python3
"""ADB Script Launcher - Configurable GUI for executing shell scripts."""

import datetime
import json
import os
import re
import subprocess
import sys
import threading
import queue
import time
import uuid
from pathlib import Path
from tkinter import (
    Tk, Frame, Label, Button, Text, Scrollbar, Entry, Toplevel, Canvas, LabelFrame,
    Listbox,
    END, BOTH, LEFT, RIGHT, TOP, BOTTOM, Y, X, WORD, DISABLED, NORMAL, W, E,
    StringVar, IntVar, BooleanVar, Menu, messagebox, filedialog, Checkbutton, OptionMenu
)
from tkinter import ttk
from tkinter.font import Font, families
from tkinter import PanedWindow


class FontManager:
    """Platform-aware font selection.

    Picks the first available family from a modern stack, so the UI looks
    native on Windows (Segoe UI), macOS (SF Pro) and Linux (Noto/DejaVu)
    without shipping any fonts.
    """
    _ui_family = None
    _mono_family = None

    UI_CANDIDATES = (
        'Segoe UI Variable', 'Segoe UI', 'SF Pro Display', 'SF Pro Text',
        'Inter', 'Roboto', 'Noto Sans', 'Ubuntu', 'DejaVu Sans',
        'Liberation Sans', 'Helvetica Neue', 'Helvetica',
    )
    MONO_CANDIDATES = (
        'Cascadia Code', 'JetBrains Mono', 'Fira Code', 'Consolas',
        'SF Mono', 'Menlo', 'Monaco', 'DejaVu Sans Mono',
        'Ubuntu Mono', 'Courier New', 'Courier',
    )

    @classmethod
    def init(cls, root=None):
        """Resolve font families. Needs a Tk root to enumerate families."""
        try:
            available = set(families())
        except Exception:
            available = set()

        for name in cls.UI_CANDIDATES:
            if name in available:
                cls._ui_family = name
                break
        if cls._ui_family is None:
            cls._ui_family = 'Helvetica'

        for name in cls.MONO_CANDIDATES:
            if name in available:
                cls._mono_family = name
                break
        if cls._mono_family is None:
            cls._mono_family = 'Courier'

    @classmethod
    def ui(cls, size=10, bold=False):
        family = cls._ui_family or 'Helvetica'
        if bold:
            return (family, size, 'bold')
        return (family, size)

    @classmethod
    def mono(cls, size=10):
        return (cls._mono_family or 'Courier', size)


# Unified type scale (FONT_*). Every text element must draw from this scale
# so the UI keeps a consistent, legible hierarchy in both light and dark themes.
FONT_XS = 8    # 8px  – tiny text: badges, footnotes, status dots
FONT_SM = 9    # 9px  – small text: buttons, labels, descriptions, status bar
FONT_MD = 10   # 10px – body: default text, inputs, script buttons, output panel
FONT_LG = 11   # 11px – subtitle: group titles, dialog field labels
FONT_XL = 12   # 12px – small title: dialog titles, settings title
FONT_2XL = 13  # 13px – title: main titles, dialog script names
FONT_3XL = 15  # 15px – large title: dialog script names
FONT_4XL = 20  # 20px – hero title: about dialog title


# Unified 4px spacing scale (SP = Spacing).
# Every layout gap must come from here so both themes stay rhythm-consistent.
SP = {
    'xs': 4,    # 4px  – tight gaps, indicator bars
    'sm': 8,    # 8px  – gaps between related controls / list items
    'md': 12,   # 12px – standard gap inside cards
    'lg': 16,   # 16px – gap between cards and sections
    'xl': 24,   # 24px – dialog / page padding
    '2xl': 32,  # 32px – major section separation
}


# AppStore 38 张 UI 参考截图（与 capture_appstore_screenshots.py 对齐）
UI_SCREENSHOT_SCRIPT_PATH = "/home/liang/Project/Reachauto/HC/27M/Honda27M/AppStore/tools/screenshot/capture_appstore_screenshots.py"

UI_SCREENSHOTS = [
    ("001", "001_1.1.1 功能入口"),
    ("002", "002_1.1.6 应用商店-按钮状态（全）"),
    ("003", "003_1.1.3 应用商店首页-加载中"),
    ("004", "004_1.1.4 应用商店首页-加载失败、接口异常"),
    ("005", "005_1.1.5 应用商店首页-Empty"),
    ("006", "006_1.1.8 应用商店-banner获取异常"),
    ("007", "007_1.1.9 应用商店首页-无banner位"),
    ("008", "008_1.1.10 应用商店首页_滚动"),
    ("009", "009_1.2.1 预装单个应用更新确认"),
    ("010", "010_1.2.2 预装组合包更新确认"),
    ("011", "011_1.2.3 预装应用安装前确认"),
    ("012", "012_1.2.4 预装应用更新完成提醒_Notificationg"),
    ("013", "013_1.3.1 应用搜索"),
    ("014", "014_1.3.3 搜索页面（loading）_点击搜索按钮键盘收起"),
    ("015", "015_1.3.4 搜索页面（搜索结果）"),
    ("016", "016_1.3.5 搜索页面（搜索结果未空）"),
    ("017", "017_1.3.6 搜索页面（搜索异常）"),
    ("018", "018_1.3.7 搜索页面（热门推荐无数据、无网络）"),
    ("019", "019_2.1.2 我的应用列表-全部更新"),
    ("020", "020_2.1.3 我的应用列表-加载中"),
    ("021", "021_2.1.4 我的应用列表-无网络、异常数据"),
    ("022", "022_2.1.6 我的应用列表-卸载中"),
    ("023", "023_2.1.7 我的应用列表-删除确认"),
    ("024", "024_2.2.1 设置页"),
    ("025", "025_2.2.3 自动更新弹窗"),
    ("026", "026_2.2.4 还原确认"),
    ("027", "027_2.2.5 Honda Connect Core 弹窗查看"),
    ("028", "028_2.2.6 Honda Connect Core 弹窗查看（Loading）"),
    ("029", "029_2.2.7 Honda Connect Core 弹窗查看（加载失败）"),
    ("030", "030_3.1.1 应用详情-后装-可更新"),
    ("031", "031_3.1.1 应用详情-后装-图片加载失败"),
    ("032", "032_3.1.2 应用详情-后装-可更新-下"),
    ("033", "033_3.1.3 应用详情-loading"),
    ("034", "034_3.1.4 应用详情-已安装-无网络、数据异常"),
    ("035", "035_3.1.5 应用详情-未安装-无网络、数据异常"),
    ("036", "036_3.1.7 应用详情 放大查看预览图"),
    ("037", "037_3.1.7 应用详情 放大查看预览图（图片加载失败）"),
    ("038", "038_4.1.1 三方应用通用走行限制"),
]


def _bind_hover(widget, base_bg, hover_bg):
    """Add a simple hover state to a flat widget."""
    widget.bind('<Enter>', lambda e: widget.configure(bg=hover_bg))
    widget.bind('<Leave>', lambda e: widget.configure(bg=base_bg))


def _make_button(parent, text, command, colors, kind='primary',
                 padx=SP['md'], pady=SP['sm'], font_size=FONT_SM, **kwargs):
    """Create a consistent flat button with hover/active feedback."""
    if kind == 'primary':
        base, hover, fg = colors['bg_button'], colors['bg_button_hover'], colors['fg_button']
        active, active_fg = colors['bg_button_pressed'], colors['fg_button']
    elif kind == 'secondary':
        base, hover, fg = colors['bg_group'], colors['bg_hover'], colors['fg']
        active, active_fg = colors['bg_active'], colors['fg']
    elif kind == 'ghost':
        base, hover, fg = colors['bg_secondary'], colors['bg_hover'], colors['fg_dim']
        active, active_fg = colors['bg_active'], colors['fg']
    else:  # danger
        base, hover, fg = colors['bg_button_stop'], colors['bg_button_stop_hover'], colors['fg_button']
        active, active_fg = colors['bg_button_stop'], colors['fg_button']

    btn = Button(
        parent,
        text=text,
        command=command,
        bg=base,
        fg=fg,
        activebackground=active,
        activeforeground=active_fg,
        relief='flat',
        padx=padx,
        pady=pady,
        cursor='hand2',
        font=FontManager.ui(font_size),
        highlightthickness=0,
        **kwargs
    )
    _bind_hover(btn, base, hover)
    return btn


def _create_entry(parent, colors, textvariable, font=None):
    """Create a flat entry with a subtle focus border."""
    return Entry(
        parent,
        textvariable=textvariable,
        bg=colors['bg_input'],
        fg=colors['fg'],
        insertbackground=colors['fg'],
        relief='flat',
        highlightthickness=1,
        highlightbackground=colors['border'],
        highlightcolor=colors['bg_button'],
        font=font or FontManager.ui(FONT_MD),
        selectbackground=colors['selection_bg'],
        selectforeground=colors['selection_fg']
    )


class ScriptParameter:
    def __init__(self, data: dict):
        self.name = data.get('name', '')
        self.label = data.get('label', self.name)
        self.type = data.get('type', 'text')
        self.default = data.get('default', '')
        self.description = data.get('description', '')
        self.required = data.get('required', False)


class ScriptConfig:
    def __init__(self, data: dict):
        self.id = data.get('id', '')
        self.name = data.get('name', 'Unnamed')
        self.description = data.get('description', '')
        self.group = data.get('group', 'Default')
        self.command = data.get('command', '')
        self.working_dir = data.get('working_dir')
        self.env = data.get('env')
        self.parameters = [ScriptParameter(p) for p in data.get('parameters', [])]
        self.enabled = data.get('enabled', True)


class GroupConfig:
    def __init__(self, data: dict):
        self.id = data.get('id', '')
        self.label = data.get('label', self.id)
        self.expanded = data.get('expanded', True)


class AppConfig:
    def __init__(self, data: dict):
        app = data.get('app', {})
        self.title = app.get('title', 'Script Launcher')
        self.width = app.get('width', 900)
        self.height = app.get('height', 600)
        self.theme = app.get('theme', 'dark')

        self.scripts = [ScriptConfig(s) for s in data.get('scripts', [])]
        self.groups = [GroupConfig(g) for g in data.get('groups', [])]

        existing_ids = {g.id for g in self.groups}
        for script in self.scripts:
            if script.group not in existing_ids:
                self.groups.append(GroupConfig({'id': script.group, 'label': script.group}))
                existing_ids.add(script.group)


class SettingsManager:
    def __init__(self, settings_path: Path):
        self.settings_path = settings_path
        self.settings = self._load()

    def _load(self) -> dict:
        if self.settings_path.exists():
            try:
                with open(self.settings_path, 'r', encoding='utf-8') as f:
                    return json.load(f)
            except:
                return {}
        return {}

    def save(self):
        with open(self.settings_path, 'w', encoding='utf-8') as f:
            json.dump(self.settings, f, indent=2, ensure_ascii=False)

    def get(self, script_id: str, param_name: str, default: str = '') -> str:
        return self.settings.get(script_id, {}).get(param_name, default)

    def set(self, script_id: str, param_name: str, value: str):
        if script_id not in self.settings:
            self.settings[script_id] = {}
        self.settings[script_id][param_name] = value
        self.save()

    def get_all(self, script_id: str) -> dict:
        return self.settings.get(script_id, {})

    def get_enabled(self, script_id: str, default: bool = True) -> bool:
        return self.settings.get('_enabled', {}).get(script_id, default)

    def set_enabled(self, script_id: str, value: bool):
        if '_enabled' not in self.settings:
            self.settings['_enabled'] = {}
        self.settings['_enabled'][script_id] = value
        self.save()


class ParameterDialog(Toplevel):
    def __init__(self, parent, script: ScriptConfig, colors: dict, settings: SettingsManager):
        super().__init__(parent)
        self.script = script
        self.colors = colors
        self.settings = settings
        self.result = None
        self.entries = {}

        self.title(f"Parameters - {script.name}")
        self.geometry("560x340")
        self.minsize(440, 280)
        self.resizable(True, True)
        self.transient(parent)
        self.grab_set()

        self.configure(bg=colors['bg'])
        self._create_widgets()

    def _create_widgets(self):
        main_frame = Frame(self, bg=self.colors['bg'])
        main_frame.pack(fill=BOTH, expand=True)

        # Top accent strip gives the dialog an identity bar
        Frame(main_frame, bg=self.colors['bg_button'], height=3).pack(fill=X)

        header = Frame(main_frame, bg=self.colors['bg'])
        header.pack(fill=X, padx=SP['xl'], pady=(SP['xl'], SP['md']))

        Label(
            header,
            text="RUN SCRIPT",
            bg=self.colors['bg'],
            fg=self.colors['bg_button'],
            font=FontManager.ui(FONT_XS, bold=True)
        ).pack(anchor=W)

        Label(
            header,
            text=self.script.name,
            bg=self.colors['bg'],
            fg=self.colors['fg_header'],
            font=FontManager.ui(FONT_3XL, bold=True)
        ).pack(anchor=W, pady=(SP['xs'], 0))

        separator = Frame(main_frame, bg=self.colors['border'], height=1)
        separator.pack(fill=X, padx=SP['xl'], pady=(SP['md'], SP['lg']))

        fields = Frame(main_frame, bg=self.colors['bg'])
        fields.pack(fill=BOTH, expand=True, padx=SP['xl'])

        for param in self.script.parameters:
            row = Frame(fields, bg=self.colors['bg'])
            row.pack(fill=X, pady=(SP['xs'], 0))

            label_text = param.label
            if param.required:
                label_text += " *"

            Label(
                row,
                text=label_text,
                bg=self.colors['bg'],
                fg=self.colors['fg'],
                font=FontManager.ui(FONT_LG),
                width=18,
                anchor=W
            ).pack(side=LEFT)

            saved_value = self.settings.get(self.script.id, param.name, param.default)
            var = StringVar(value=saved_value)
            self.entries[param.name] = var

            if param.type == 'file':
                file_frame = Frame(row, bg=self.colors['bg'])
                file_frame.pack(side=LEFT, fill=X, expand=True)

                entry = _create_entry(file_frame, self.colors, var)
                entry.pack(side=LEFT, fill=X, expand=True)

                _make_button(
                    file_frame,
                    text="Browse",
                    command=lambda v=var: self._browse_file(v),
                    colors=self.colors,
                    kind='secondary',
                    padx=SP['md'],
                    pady=SP['xs'],
                    font_size=FONT_SM
                ).pack(side=LEFT, padx=(SP['sm'], 0))
            elif param.type == 'directory':
                dir_frame = Frame(row, bg=self.colors['bg'])
                dir_frame.pack(side=LEFT, fill=X, expand=True)

                entry = _create_entry(dir_frame, self.colors, var)
                entry.pack(side=LEFT, fill=X, expand=True)

                _make_button(
                    dir_frame,
                    text="Browse",
                    command=lambda v=var: self._browse_directory(v),
                    colors=self.colors,
                    kind='secondary',
                    padx=SP['md'],
                    pady=SP['xs'],
                    font_size=FONT_SM
                ).pack(side=LEFT, padx=(SP['sm'], 0))
            else:
                entry = _create_entry(row, self.colors, var)
                entry.pack(side=LEFT, fill=X, expand=True)

            if param.description:
                Label(
                    fields,
                    text=f"  {param.description}",
                    bg=self.colors['bg'],
                    fg=self.colors['fg_dim'],
                    font=FontManager.ui(FONT_XS)
                ).pack(anchor=W, padx=(SP['lg'], 0), pady=(SP['xs'], SP['sm']))

        button_frame = Frame(main_frame, bg=self.colors['bg'])
        button_frame.pack(fill=X, padx=SP['xl'], pady=SP['xl'])

        _make_button(
            button_frame,
            text="Run",
            command=self._on_run,
            colors=self.colors,
            kind='primary',
            padx=SP['xl'],
            pady=SP['sm'],
            font_size=FONT_MD
        ).pack(side=RIGHT, padx=(SP['md'], 0))

        _make_button(
            button_frame,
            text="Cancel",
            command=self._on_cancel,
            colors=self.colors,
            kind='secondary',
            padx=SP['xl'],
            pady=SP['sm'],
            font_size=FONT_MD
        ).pack(side=RIGHT)

    def _browse_file(self, var: StringVar):
        filename = filedialog.askopenfilename()
        if filename:
            var.set(filename)

    def _browse_directory(self, var: StringVar):
        path = filedialog.askdirectory()
        if path:
            var.set(path)

    def _on_run(self):
        for param in self.script.parameters:
            if param.required and not self.entries[param.name].get().strip():
                messagebox.showwarning("Missing Parameter", f"{param.label} is required")
                return

        for name, var in self.entries.items():
            self.settings.set(self.script.id, name, var.get())

        self.result = {name: var.get() for name, var in self.entries.items()}
        self.destroy()

    def _on_cancel(self):
        self.result = None
        self.destroy()


class SettingsDialog(Toplevel):
    DEFAULT_KEYBINDINGS = {
        'search_open': {'key': 'Control-f', 'label': 'Open Search', 'description': 'Open the search bar in output panel'},
        'search_close': {'key': 'Escape', 'label': 'Close Search', 'description': 'Close the search bar'},
        'search_next': {'key': 'Return', 'label': 'Next Match', 'description': 'Jump to next search match'},
        'filter_apply': {'key': 'Return', 'label': 'Apply Filter', 'description': 'Apply logcat filter'},
    }

    def __init__(self, parent, config: AppConfig, colors: dict, settings: SettingsManager):
        super().__init__(parent)
        self.config = config
        self.colors = colors
        self.settings = settings
        self.entries = {}
        self.enabled_vars = {}
        self.keybinding_vars = {}
        self._listening_for = None

        self.title("Settings")
        self.geometry("2100x900")
        self.resizable(True, True)
        self.transient(parent)
        self.configure(bg=colors['bg'])
        self._create_widgets()

        self.update_idletasks()
        x = (self.winfo_screenwidth() - 2100) // 2
        y = (self.winfo_screenheight() - 900) // 2
        self.geometry(f"2100x900+{x}+{y}")

    def _create_widgets(self):
        container = Frame(self, bg=self.colors['bg'])
        container.pack(fill=BOTH, expand=True, padx=SP['xl'], pady=SP['xl'])

        canvas = Canvas(container, bg=self.colors['bg'], highlightthickness=0)
        vsb = Scrollbar(container, orient="vertical", command=canvas.yview)
        inner = Frame(canvas, bg=self.colors['bg'])

        inner.bind("<Configure>", lambda e: canvas.configure(scrollregion=canvas.bbox("all")))
        canvas.create_window((0, 0), window=inner, anchor="nw", tags="inner")
        canvas.configure(yscrollcommand=vsb.set)

        def resize_inner(event):
            canvas.itemconfig("inner", width=event.width)
        canvas.bind("<Configure>", resize_inner)

        canvas.pack(side=LEFT, fill=BOTH, expand=True)
        vsb.pack(side=RIGHT, fill=Y)

        def _on_mousewheel(event):
            canvas.yview_scroll(int(-1 * (event.delta / 120)), "units")

        canvas.bind_all("<MouseWheel>", _on_mousewheel)
        canvas.bind_all("<Button-4>", lambda e: canvas.yview_scroll(-3, "units"))
        canvas.bind_all("<Button-5>", lambda e: canvas.yview_scroll(3, "units"))

        Label(
            inner,
            text="Settings",
            bg=self.colors['bg'],
            fg=self.colors['fg_header'],
            font=FontManager.ui(FONT_XL, bold=True)
        ).pack(anchor=W, pady=(0, SP['sm']))

        Label(
            inner,
            text="Persisted defaults and enabled state for scripts",
            bg=self.colors['bg'],
            fg=self.colors['fg_dim'],
            font=FontManager.ui(FONT_SM)
        ).pack(anchor=W, pady=(0, SP['lg']))

        for script in self.config.scripts:
            script_card = Frame(
                inner,
                bg=self.colors['bg_secondary'],
                highlightthickness=1,
                highlightbackground=self.colors['border']
            )
            script_card.pack(fill=X, pady=SP['sm'])

            inner_card = Frame(script_card, bg=self.colors['bg_secondary'])
            inner_card.pack(fill=X, padx=SP['xl'], pady=SP['lg'])

            header_row = Frame(inner_card, bg=self.colors['bg_secondary'])
            header_row.pack(fill=X)

            Label(
                header_row,
                text=script.name,
                bg=self.colors['bg_secondary'],
                fg=self.colors['fg_header'],
                font=FontManager.ui(FONT_2XL, bold=True)
            ).pack(side=LEFT)

            enabled_var = IntVar(value=1 if self.settings.get_enabled(script.id, script.enabled) else 0)
            self.enabled_vars[script.id] = enabled_var

            Checkbutton(
                header_row,
                text="Enabled",
                variable=enabled_var,
                bg=self.colors['bg_secondary'],
                fg=self.colors['fg'],
                selectcolor=self.colors['bg_input'],
                activebackground=self.colors['bg_secondary'],
                activeforeground=self.colors['fg'],
                font=FontManager.ui(FONT_SM)
            ).pack(side=RIGHT)

            if script.description:
                Label(
                    inner_card,
                    text=script.description,
                    bg=self.colors['bg_secondary'],
                    fg=self.colors['fg_dim'],
                    font=FontManager.ui(FONT_SM)
                ).pack(anchor=W, pady=(SP['xs'], SP['lg']))
            else:
                Frame(inner_card, bg=self.colors['border'], height=1).pack(fill=X, pady=SP['md'])

            if not script.parameters:
                continue

            for param in script.parameters:
                param_frame = Frame(inner_card, bg=self.colors['bg_secondary'])
                param_frame.pack(fill=X, pady=SP['xs'])

                label_text = param.label
                if param.required:
                    label_text += " *"

                Label(
                    param_frame,
                    text=label_text,
                    bg=self.colors['bg_secondary'],
                    fg=self.colors['fg'],
                    font=FontManager.ui(FONT_LG),
                    width=20,
                    anchor=W
                ).pack(side=LEFT)

                saved_value = self.settings.get(script.id, param.name, param.default)
                var = StringVar(value=saved_value)
                self.entries[(script.id, param.name)] = var

                if param.type == 'file':
                    file_frame = Frame(param_frame, bg=self.colors['bg_secondary'])
                    file_frame.pack(side=LEFT, fill=X, expand=True)

                    entry = _create_entry(file_frame, self.colors, var)
                    entry.pack(side=LEFT, fill=X, expand=True)

                    _make_button(
                        file_frame,
                        text="Browse",
                        command=lambda v=var: self._browse_file(v),
                        colors=self.colors,
                        kind='secondary',
                        padx=SP['md'],
                        pady=SP['xs'],
                        font_size=FONT_SM
                    ).pack(side=LEFT, padx=(SP['sm'], 0))
                elif param.type == 'directory':
                    dir_frame = Frame(param_frame, bg=self.colors['bg_secondary'])
                    dir_frame.pack(side=LEFT, fill=X, expand=True)

                    entry = _create_entry(dir_frame, self.colors, var)
                    entry.pack(side=LEFT, fill=X, expand=True)

                    _make_button(
                        dir_frame,
                        text="Browse",
                        command=lambda v=var: self._browse_directory(v),
                        colors=self.colors,
                        kind='secondary',
                        padx=SP['md'],
                        pady=SP['xs'],
                        font_size=FONT_SM
                    ).pack(side=LEFT, padx=(SP['sm'], 0))
                else:
                    entry = _create_entry(param_frame, self.colors, var)
                    entry.pack(side=LEFT, fill=X, expand=True)

                if param.description:
                    Label(
                        inner_card,
                        text=f"    {param.description}",
                        bg=self.colors['bg_secondary'],
                        fg=self.colors['fg_dim'],
                        font=FontManager.ui(FONT_XS)
                    ).pack(anchor=W, padx=(SP['lg'], 0), pady=(0, SP['sm']))

        keybindings_card = Frame(
            inner,
            bg=self.colors['bg_secondary'],
            highlightthickness=1,
            highlightbackground=self.colors['border']
        )
        keybindings_card.pack(fill=X, pady=(SP['lg'], SP['sm']))

        keybindings_inner = Frame(keybindings_card, bg=self.colors['bg_secondary'])
        keybindings_inner.pack(fill=X, padx=SP['xl'], pady=SP['lg'])

        Label(
            keybindings_inner,
            text="Keyboard Shortcuts",
            bg=self.colors['bg_secondary'],
            fg=self.colors['fg_header'],
            font=FontManager.ui(FONT_2XL, bold=True)
        ).pack(anchor=W)

        Label(
            keybindings_inner,
            text="Click on a key binding and press the new key combination to change it",
            bg=self.colors['bg_secondary'],
            fg=self.colors['fg_dim'],
            font=FontManager.ui(FONT_SM)
        ).pack(anchor=W, pady=(SP['xs'], SP['lg']))

        for action_id, action_info in self.DEFAULT_KEYBINDINGS.items():
            row = Frame(keybindings_inner, bg=self.colors['bg_secondary'])
            row.pack(fill=X, pady=SP['xs'])

            Label(
                row,
                text=action_info['label'],
                bg=self.colors['bg_secondary'],
                fg=self.colors['fg'],
                font=FontManager.ui(FONT_LG),
                width=20,
                anchor=W
            ).pack(side=LEFT)

            saved_key = self.settings.get('_keybindings', action_id, action_info['key'])
            var = StringVar(value=saved_key)
            self.keybinding_vars[action_id] = var

            key_btn = Button(
                row,
                textvariable=var,
                bg=self.colors['bg_input'],
                fg=self.colors['fg'],
                relief='flat',
                padx=SP['md'],
                pady=SP['xs'],
                font=FontManager.mono(FONT_MD),
                width=20,
                anchor=W,
                cursor='hand2'
            )
            key_btn.pack(side=LEFT, padx=(SP['sm'], 0))
            key_btn.bind('<Button-1>', lambda e, a=action_id, b=key_btn: self._start_listen(a, b))
            key_btn.bind('<Key>', lambda e, a=action_id, b=key_btn: self._on_key_press(e, a, b))

            Label(
                row,
                text=action_info['description'],
                bg=self.colors['bg_secondary'],
                fg=self.colors['fg_dim'],
                font=FontManager.ui(FONT_XS)
            ).pack(side=LEFT, padx=(SP['md'], 0))

        logcat_card = Frame(
            inner,
            bg=self.colors['bg_secondary'],
            highlightthickness=1,
            highlightbackground=self.colors['border']
        )
        logcat_card.pack(fill=X, pady=(SP['lg'], SP['sm']))

        logcat_inner = Frame(logcat_card, bg=self.colors['bg_secondary'])
        logcat_inner.pack(fill=X, padx=SP['xl'], pady=SP['lg'])

        Label(
            logcat_inner,
            text="Logcat Export",
            bg=self.colors['bg_secondary'],
            fg=self.colors['fg_header'],
            font=FontManager.ui(FONT_2XL, bold=True)
        ).pack(anchor=W)

        Label(
            logcat_inner,
            text="Default export directory for log files",
            bg=self.colors['bg_secondary'],
            fg=self.colors['fg_dim'],
            font=FontManager.ui(FONT_SM)
        ).pack(anchor=W, pady=(SP['xs'], SP['lg']))

        export_row = Frame(logcat_inner, bg=self.colors['bg_secondary'])
        export_row.pack(fill=X, pady=SP['xs'])

        Label(
            export_row,
            text="Export Path",
            bg=self.colors['bg_secondary'],
            fg=self.colors['fg'],
            font=FontManager.ui(FONT_LG),
            width=20,
            anchor=W
        ).pack(side=LEFT)

        saved_export_path = self.settings.get('_logcat', 'export_path', '')
        self._export_path_var = StringVar(value=saved_export_path)
        self.entries[('_logcat', 'export_path')] = self._export_path_var

        export_entry = _create_entry(export_row, self.colors, self._export_path_var)
        export_entry.pack(side=LEFT, fill=X, expand=True)

        _make_button(
            export_row,
            text="Browse",
            command=self._browse_export_path,
            colors=self.colors,
            kind='secondary',
            padx=SP['md'],
            pady=SP['xs'],
            font_size=FONT_SM
        ).pack(side=LEFT, padx=(SP['sm'], 0))

        button_frame = Frame(self, bg=self.colors['bg'])
        button_frame.pack(fill=X, side=BOTTOM, padx=SP['xl'], pady=SP['lg'])

        _make_button(
            button_frame,
            text="Save All",
            command=self._on_save,
            colors=self.colors,
            kind='primary',
            padx=SP['xl'],
            pady=SP['sm'],
            font_size=FONT_MD
        ).pack(side=RIGHT, padx=(SP['md'], 0))

        _make_button(
            button_frame,
            text="Close",
            command=self.destroy,
            colors=self.colors,
            kind='secondary',
            padx=SP['xl'],
            pady=SP['sm'],
            font_size=FONT_MD
        ).pack(side=RIGHT)

    def _start_listen(self, action_id, btn):
        self._listening_for = action_id
        btn.configure(bg=self.colors['bg_button'], fg=self.colors['fg_button'])
        btn.focus_set()

    def _on_key_press(self, event, action_id, btn):
        if self._listening_for != action_id:
            return

        if event.keysym in ('Shift_L', 'Shift_R', 'Control_L', 'Control_R', 
                           'Alt_L', 'Alt_R', 'Super_L', 'Super_R'):
            return

        parts = []
        if event.state & 4:
            parts.append('Control')
        if event.state & 1:
            parts.append('Shift')
        if event.state & 8:
            parts.append('Alt')
        if event.state & 64:
            parts.append('Super')

        key = event.keysym
        if len(key) == 1:
            key = key.lower()

        parts.append(key)
        key_string = '-'.join(parts)

        self.keybinding_vars[action_id].set(key_string)
        btn.configure(bg=self.colors['bg_input'], fg=self.colors['fg'])
        self._listening_for = None

    def _browse_file(self, var: StringVar):
        filename = filedialog.askopenfilename()
        if filename:
            var.set(filename)

    def _browse_directory(self, var: StringVar):
        path = filedialog.askdirectory()
        if path:
            var.set(path)

    def _browse_export_path(self):
        path = filedialog.askdirectory(title="Select Export Directory")
        if path:
            self._export_path_var.set(path)

    def _on_save(self):
        for (script_id, param_name), var in self.entries.items():
            self.settings.set(script_id, param_name, var.get())

        for script_id, var in self.enabled_vars.items():
            self.settings.set_enabled(script_id, bool(var.get()))

        for action_id, var in self.keybinding_vars.items():
            self.settings.set('_keybindings', action_id, var.get())

        messagebox.showinfo("Saved", "Settings saved successfully", parent=self)
        self.destroy()


class ScriptRunner:
    def __init__(self, output_queue: queue.Queue):
        self.output_queue = output_queue
        self.process = None
        self.running = False
        self._thread = None

    def execute(self, script: ScriptConfig, base_dir: Path, params: dict = None):
        if self.running:
            self.output_queue.put(('error', 'Another script is running. Stop it first.\n'))
            return

        self.running = True
        self._thread = threading.Thread(
            target=self._run,
            args=(script, base_dir, params),
            daemon=True
        )
        self._thread.start()

    def _run(self, script: ScriptConfig, base_dir: Path, params: dict = None):
        try:
            command = script.command
            if params:
                for name, value in params.items():
                    command = command.replace(f"${{{name}}}", value)

            self.output_queue.put(('info', f'>>> {script.name}\n'))
            self.output_queue.put(('info', f'>>> Command: {command}\n'))
            if params:
                self.output_queue.put(('info', f'>>> Parameters: {params}\n'))
            self.output_queue.put(('info', '-' * 60 + '\n'))

            cwd = base_dir
            if script.working_dir:
                cwd = Path(script.working_dir)
                if not cwd.is_absolute():
                    cwd = base_dir / cwd

            env = os.environ.copy()
            if script.env:
                env.update(script.env)

            import fcntl

            self.process = subprocess.Popen(
                command,
                shell=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                cwd=str(cwd),
                env=env,
                bufsize=0
            )

            fd = self.process.stdout.fileno()
            fl = fcntl.fcntl(fd, fcntl.F_GETFL)
            fcntl.fcntl(fd, fcntl.F_SETFL, fl | os.O_NONBLOCK)

            buf = ''
            while self.running:
                try:
                    data = os.read(fd, 1024)
                    if not data:
                        if self.process.poll() is not None:
                            break
                        time.sleep(0.01)
                        continue
                    text = data.decode('utf-8', errors='replace')
                    for ch in text:
                        if ch in ('\r', '\n'):
                            if buf:
                                self.output_queue.put(('stdout', buf + '\n'))
                                buf = ''
                        else:
                            buf += ch
                except BlockingIOError:
                    time.sleep(0.01)
                except OSError:
                    break

            if buf:
                self.output_queue.put(('stdout', buf + '\n'))

            self.process.wait()

            if self.running:
                if self.process.returncode == 0:
                    self.output_queue.put(('success', f'\n✓ Completed (exit code 0)\n'))
                else:
                    self.output_queue.put(('error', f'\n✗ Failed (exit code {self.process.returncode})\n'))

        except Exception as e:
            self.output_queue.put(('error', f'\n✗ Error: {e}\n'))
        finally:
            self.running = False
            self.process = None

    def pause(self):
        if self.process and self.running:
            try:
                import signal
                os.kill(self.process.pid, signal.SIGSTOP)
            except (OSError, AttributeError):
                pass

    def resume(self):
        if self.process and self.running:
            try:
                import signal
                os.kill(self.process.pid, signal.SIGCONT)
            except (OSError, AttributeError):
                pass

    def stop(self):
        if self.process:
            self.running = False
            try:
                self.process.terminate()
                time.sleep(0.5)
                if self.process.poll() is None:
                    self.process.kill()
            except:
                pass
        self.running = False


class ThemeManager:
    """Tokyo Night (dark) and modern workspace (light) palettes.

    Dark  – Tokyo Night: deep night-blue canvas with neon cyan-blue accents,
            amber headings and vivid semantic colors for terminal feedback.
    Light – Figma/Linear inspired: warm-grey canvas, crisp white cards,
            blurple primary accent and a restrained semantic palette.
    """
    DARK = {
        # Canvas / surfaces (layered: bg < secondary < card < group)
        'bg': '#16161e',                    # app background (night)
        'bg_secondary': '#1a1b26',          # panels, list items, output header
        'bg_card': '#1f2335',               # elevated group card
        'bg_group': '#24283b',              # group header tint
        'bg_hover': '#292e42',              # hover state
        'bg_active': '#3b4261',             # pressed / selected
        'bg_input': '#101014',              # text inputs
        'bg_badge': '#2f3549',              # count badge pill
        'fg_on_badge': '#c0caf5',           # badge text
        # Primary accent (Tokyo Night blue)
        'bg_button': '#7aa2f7',
        'accent': '#7aa2f7',
        'bg_button_hover': '#89b4fb',
        'accent_hover': '#89b4fb',
        'bg_button_pressed': '#5d7ed9',
        'fg_on_accent': '#16161e',
        'fg_button': '#16161e',
        # Danger / stop
        'bg_button_stop': '#f7768e',
        'bg_button_stop_hover': '#ff9eb5',
        # Foreground
        'fg': '#c0caf5',
        'fg_header': '#e0af68',            # amber headings
        'fg_success': '#9ece6a',           # green
        'fg_error': '#f7768e',             # red
        'fg_warning': '#e0af68',           # orange
        'fg_info': '#7aa2f7',              # blue
        'fg_dim': '#565f89',               # muted
        # Borders & selection
        'border': '#2f3549',
        'selection_bg': '#33467c',
        'selection_fg': '#ffffff',
        # Output / terminal
        'output_bg': '#101014',
        'output_fg': '#c0caf5',
    }

    LIGHT = {
        # Canvas / surfaces (layered: bg < secondary = card < group)
        'bg': '#f2f3f5',                    # app background (warm grey)
        'bg_secondary': '#ffffff',          # panels, list items, output header
        'bg_card': '#ffffff',               # elevated group card
        'bg_group': '#f7f8fa',              # group header tint
        'bg_hover': '#e8eaed',              # hover state
        'bg_active': '#dbdde1',             # pressed / selected
        'bg_input': '#ffffff',              # text inputs
        'bg_badge': '#eceef1',              # count badge pill
        'fg_on_badge': '#3f4248',           # badge text
        # Primary accent (blurple)
        'bg_button': '#5865f2',
        'accent': '#5865f2',
        'bg_button_hover': '#6b76f5',
        'accent_hover': '#6b76f5',
        'bg_button_pressed': '#4753cf',
        'fg_on_accent': '#ffffff',
        'fg_button': '#ffffff',
        # Danger / stop
        'bg_button_stop': '#e5484d',
        'bg_button_stop_hover': '#f05a5e',
        # Foreground
        'fg': '#2b2d31',
        'fg_header': '#17181c',
        'fg_success': '#30a46c',           # green
        'fg_error': '#e5484d',             # red
        'fg_warning': '#b58105',           # amber
        'fg_info': '#5865f2',              # blue
        'fg_dim': '#8a8f98',               # muted
        # Borders & selection
        'border': '#e3e5e8',
        'selection_bg': '#dbe1ff',
        'selection_fg': '#1a1a1a',
        # Output / terminal
        'output_bg': '#ffffff',
        'output_fg': '#2b2d31',
    }

    @classmethod
    def get(cls, theme_name: str) -> dict:
        return cls.DARK if theme_name == 'dark' else cls.LIGHT


class ScriptButton(Frame):
    """List-item style entry: accent indicator bar + border + hover highlight."""

    def __init__(self, parent, script: ScriptConfig, colors: dict, on_click, enabled=True, **kwargs):
        self.script = script
        self.colors = colors
        self._hovered = False
        self.enabled = enabled

        super().__init__(
            parent,
            bg=colors['bg_secondary'],
            highlightthickness=1,
            highlightbackground=colors['border'],
            highlightcolor=colors['bg_button'],
            **kwargs
        )

        disabled_fg = colors['fg_dim']
        normal_fg = colors['fg']
        fg = normal_fg if enabled else disabled_fg
        cursor = 'hand2' if enabled else 'arrow'
        indicator_color = colors['accent'] if enabled else colors['border']

        # Left accent indicator — widens on hover for tactile feedback
        self.indicator = Frame(self, bg=indicator_color, width=SP['xs'])
        self.indicator.pack(side=LEFT, fill=Y)

        self._btn = Button(
            self,
            text=f"  ▸  {script.name}",
            command=(lambda: on_click(script)) if enabled else lambda: None,
            bg=colors['bg_secondary'],
            fg=fg,
            activebackground=colors['bg_hover'],
            activeforeground=fg,
            relief='flat',
            padx=SP['sm'],
            pady=SP['sm'],
            cursor=cursor,
            anchor='w',
            font=FontManager.ui(FONT_MD),
            highlightthickness=0,
            bd=0,
            state=NORMAL if enabled else DISABLED,
            disabledforeground=disabled_fg,
        )
        self._btn.pack(side=LEFT, fill=X, expand=True)

        # Hover state must cover the whole row (bar + label)
        if enabled:
            for w in (self, self._btn):
                w.bind('<Enter>', self._on_hover_enter)
                w.bind('<Leave>', self._on_hover_leave)

        self.tooltip = None
        self._tooltip_after_id = None
        if script.description and enabled:
            for w in (self, self._btn):
                w.bind('<Enter>', self._show_tooltip, add='+')
                w.bind('<Leave>', self._schedule_hide_tooltip, add='+')

    def _on_hover_enter(self, event):
        if self._hovered:
            return
        self._hovered = True
        self.configure(
            bg=self.colors['bg_hover'],
            highlightbackground=self.colors['bg_hover']
        )
        self._btn.configure(bg=self.colors['bg_hover'])
        self.indicator.configure(width=6)

    def _on_hover_leave(self, event):
        self._hovered = False
        self.configure(
            bg=self.colors['bg_secondary'],
            highlightbackground=self.colors['border']
        )
        self._btn.configure(bg=self.colors['bg_secondary'])
        self.indicator.configure(width=SP['xs'])

    def _show_tooltip(self, event):
        if self._tooltip_after_id:
            self.after_cancel(self._tooltip_after_id)
            self._tooltip_after_id = None

        if self.tooltip:
            return

        x = self.winfo_rootx() + self.winfo_width() + 5
        y = self.winfo_rooty()

        self.tooltip = Toplevel(self)
        self.tooltip.wm_overrideredirect(True)
        self.tooltip.wm_geometry(f"+{x}+{y}")

        label = Label(
            self.tooltip,
            text=self.script.description,
            bg=self.colors['bg_card'],
            fg=self.colors['fg'],
            relief='solid',
            borderwidth=1,
            padx=SP['md'],
            pady=SP['sm'],
            wraplength=260,
            justify='left',
            font=FontManager.ui(FONT_SM)
        )
        label.pack()

        self.tooltip.bind('<Leave>', self._schedule_hide_tooltip)

    def _schedule_hide_tooltip(self, event=None):
        if self._tooltip_after_id:
            self.after_cancel(self._tooltip_after_id)
        self._tooltip_after_id = self.after(100, self._hide_tooltip)

    def _hide_tooltip(self):
        if self.tooltip:
            self.tooltip.destroy()
            self.tooltip = None
        self._tooltip_after_id = None


class GroupFrame(Frame):
    """Card-style collapsible group with a live script-count badge."""

    def __init__(self, parent, group: GroupConfig, colors: dict, **kwargs):
        super().__init__(
            parent,
            bg=colors['bg_card'],
            highlightthickness=1,
            highlightbackground=colors['border'],
            highlightcolor=colors['bg_button'],
            **kwargs
        )

        self.colors = colors
        self.expanded = group.expanded
        self._group_label = group.label
        self._count = 0

        self.header = Frame(self, bg=colors['bg_group'], cursor='hand2')
        self.header.pack(fill=X)

        self.arrow_label = Label(
            self.header,
            text='▾' if self.expanded else '▸',
            bg=colors['bg_group'],
            fg=colors['fg_dim'],
            font=FontManager.ui(FONT_SM, bold=True),
            padx=SP['sm'],
            pady=SP['sm']
        )
        self.arrow_label.pack(side=LEFT)

        self.title_label = Label(
            self.header,
            text=group.label,
            bg=colors['bg_group'],
            fg=colors['fg_header'],
            font=FontManager.ui(FONT_LG, bold=True),
            anchor='w'
        )
        self.title_label.pack(side=LEFT)

        self.badge = Label(
            self.header,
            text=" 0 ",
            bg=colors['bg_badge'],
            fg=colors['fg_on_badge'],
            font=FontManager.ui(FONT_XS, bold=True),
            padx=SP['sm'],
            pady=SP['xs'] // 2
        )
        self.badge.pack(side=RIGHT, padx=SP['sm'], pady=SP['xs'] // 2)

        # Toggle + hover on the whole header row
        for w in (self.header, self.arrow_label, self.title_label):
            w.bind('<Enter>', self._on_hover_enter)
            w.bind('<Leave>', self._on_hover_leave)
            w.bind('<Button-1>', self._toggle)

        self.content = Frame(self, bg=colors['bg_card'])
        if self.expanded:
            self.content.pack(fill=X, padx=SP['sm'], pady=(SP['xs'], SP['sm']))

        self._script_buttons = []

    def _on_hover_enter(self, event=None):
        for w in (self.header, self.arrow_label, self.title_label):
            w.configure(bg=self.colors['bg_hover'])

    def _on_hover_leave(self, event=None):
        for w in (self.header, self.arrow_label, self.title_label):
            w.configure(bg=self.colors['bg_group'])

    def add_script_button(self, button: ScriptButton):
        button.pack(in_=self.content, fill=X, pady=SP['xs'])
        self._script_buttons.append(button)
        self._count += 1
        self.badge.config(text=f" {self._count} ")

    def _toggle(self, event=None):
        self.expanded = not self.expanded

        self.arrow_label.config(text='▾' if self.expanded else '▸')

        if self.expanded:
            self.content.pack(fill=X, padx=SP['sm'], pady=(SP['xs'], SP['sm']))
        else:
            self.content.pack_forget()


class OutputPanel(Frame):
    """Terminal-style output with a live status indicator dot."""

    def __init__(self, parent, colors: dict, settings: SettingsManager = None, 
                 on_stop=None, on_pause=None, on_resume=None, **kwargs):
        super().__init__(parent, bg=colors['bg_secondary'], **kwargs)

        self.colors = colors
        self.settings = settings
        self._on_stop = on_stop
        self._on_pause = on_pause
        self._on_resume = on_resume
        self._paused = False
        self._logcat_mode = False
        self._filter_active = False
        self._auto_scroll = True
        self._regex_mode = False
        self._log_level = 'V'
        self._tag_filter = ''
        self._search_highlight = False

        self.LOG_LEVELS = {
            'V': 0,
            'D': 1,
            'I': 2,
            'W': 3,
            'E': 4,
            'F': 5,
        }

        self.LOG_LEVEL_COLORS = {
            'V': colors['fg_dim'],
            'D': colors['fg_info'],
            'I': colors['fg_success'],
            'W': colors['fg_warning'],
            'E': colors['fg_error'],
            'F': '#ff0000',
        }

        header = Frame(self, bg=colors['bg_secondary'])
        header.pack(fill=X)

        self.status_dot = Label(
            header,
            text='●',
            bg=colors['bg_secondary'],
            fg=colors['fg_dim'],
            font=FontManager.ui(FONT_XS),
            padx=SP['md'],
            pady=SP['sm']
        )
        self.status_dot.pack(side=LEFT)

        Label(
            header,
            text="Output",
            bg=colors['bg_secondary'],
            fg=colors['fg_header'],
            font=FontManager.ui(FONT_MD, bold=True),
            pady=SP['sm']
        ).pack(side=LEFT)

        clear_btn = _make_button(
            header,
            text="Clear",
            command=self.clear,
            colors=colors,
            kind='ghost',
            padx=SP['md'],
            pady=SP['xs'],
            font_size=FONT_SM
        )
        clear_btn.pack(side=RIGHT, padx=SP['md'], pady=SP['xs'])

        copy_btn = _make_button(
            header,
            text="Copy All",
            command=self.copy_all,
            colors=colors,
            kind='ghost',
            padx=SP['md'],
            pady=SP['xs'],
            font_size=FONT_SM
        )
        copy_btn.pack(side=RIGHT, pady=SP['xs'])

        export_btn = _make_button(
            header,
            text="Export",
            command=self._export_log,
            colors=colors,
            kind='ghost',
            padx=SP['md'],
            pady=SP['xs'],
            font_size=FONT_SM
        )
        export_btn.pack(side=RIGHT, pady=SP['xs'])

        self._logcat_bar = Frame(self, bg=colors['bg_group'])

        logcat_top = Frame(self._logcat_bar, bg=colors['bg_group'])
        logcat_top.pack(fill=X, padx=SP['sm'], pady=(SP['xs'], 0))

        self._pause_btn = _make_button(
            logcat_top,
            text="Pause",
            command=self._toggle_pause,
            colors=colors,
            kind='secondary',
            padx=SP['md'],
            pady=SP['xs'],
            font_size=FONT_SM
        )
        self._pause_btn.pack(side=LEFT, padx=(0, SP['xs']))

        self._stop_btn = _make_button(
            logcat_top,
            text="Stop",
            command=self._stop_script,
            colors=colors,
            kind='danger',
            padx=SP['md'],
            pady=SP['xs'],
            font_size=FONT_SM
        )
        self._stop_btn.pack(side=LEFT, padx=(0, SP['sm']))

        self._auto_scroll_var = IntVar(value=1)
        auto_scroll_cb = Checkbutton(
            logcat_top,
            text="Auto-scroll",
            variable=self._auto_scroll_var,
            command=self._toggle_auto_scroll,
            bg=colors['bg_group'],
            fg=colors['fg'],
            selectcolor=colors['bg_input'],
            activebackground=colors['bg_group'],
            activeforeground=colors['fg'],
            font=FontManager.ui(FONT_SM)
        )
        auto_scroll_cb.pack(side=LEFT, padx=(0, SP['sm']))

        self._regex_var = IntVar(value=0)
        regex_cb = Checkbutton(
            logcat_top,
            text="Regex",
            variable=self._regex_var,
            command=self._toggle_regex,
            bg=colors['bg_group'],
            fg=colors['fg'],
            selectcolor=colors['bg_input'],
            activebackground=colors['bg_group'],
            activeforeground=colors['fg'],
            font=FontManager.ui(FONT_SM)
        )
        regex_cb.pack(side=LEFT, padx=(0, SP['sm']))

        logcat_mid = Frame(self._logcat_bar, bg=colors['bg_group'])
        logcat_mid.pack(fill=X, padx=SP['sm'], pady=(SP['xs'], 0))

        Label(
            logcat_mid,
            text="Level:",
            bg=colors['bg_group'],
            fg=colors['fg'],
            font=FontManager.ui(FONT_SM)
        ).pack(side=LEFT)

        self._level_var = StringVar(value='V')
        level_menu = OptionMenu(
            logcat_mid,
            self._level_var,
            'V', 'D', 'I', 'W', 'E', 'F',
            command=self._on_level_change
        )
        level_menu.configure(
            bg=colors['bg_input'],
            fg=colors['fg'],
            activebackground=colors['bg_hover'],
            activeforeground=colors['fg'],
            highlightthickness=0,
            font=FontManager.ui(FONT_SM)
        )
        level_menu.pack(side=LEFT, padx=(0, SP['sm']))

        Label(
            logcat_mid,
            text="Tag:",
            bg=colors['bg_group'],
            fg=colors['fg'],
            font=FontManager.ui(FONT_SM)
        ).pack(side=LEFT)

        self._tag_var = StringVar()
        tag_entry = Entry(
            logcat_mid,
            textvariable=self._tag_var,
            bg=colors['bg_input'],
            fg=colors['fg'],
            insertbackground=colors['fg'],
            relief='flat',
            highlightthickness=1,
            highlightbackground=colors['border'],
            highlightcolor=colors['bg_button'],
            font=FontManager.ui(FONT_SM),
            width=15
        )
        tag_entry.pack(side=LEFT, padx=(0, SP['sm']))

        Label(
            logcat_mid,
            text="Search:",
            bg=colors['bg_group'],
            fg=colors['fg'],
            font=FontManager.ui(FONT_SM)
        ).pack(side=LEFT)

        self._filter_var = StringVar()
        self._filter_entry = Entry(
            logcat_mid,
            textvariable=self._filter_var,
            bg=colors['bg_input'],
            fg=colors['fg'],
            insertbackground=colors['fg'],
            relief='flat',
            highlightthickness=1,
            highlightbackground=colors['border'],
            highlightcolor=colors['bg_button'],
            font=FontManager.ui(FONT_SM),
            width=20
        )
        self._filter_entry.pack(side=LEFT, padx=(0, SP['xs']))
        self._filter_entry.bind('<Return>', self._apply_filter)

        _make_button(
            logcat_mid,
            text="Apply",
            command=self._apply_filter,
            colors=colors,
            kind='primary',
            padx=SP['md'],
            pady=SP['xs'],
            font_size=FONT_SM
        ).pack(side=LEFT, padx=(0, SP['xs']))

        _make_button(
            logcat_mid,
            text="Clear",
            command=self._clear_filter,
            colors=colors,
            kind='ghost',
            padx=SP['md'],
            pady=SP['xs'],
            font_size=FONT_SM
        ).pack(side=LEFT)

        logcat_bottom = Frame(self._logcat_bar, bg=colors['bg_group'])
        logcat_bottom.pack(fill=X, padx=SP['sm'], pady=(SP['xs'], SP['xs']))

        _make_button(
            logcat_bottom,
            text="Clear Logcat",
            command=self._clear_device_logcat,
            colors=colors,
            kind='secondary',
            padx=SP['md'],
            pady=SP['xs'],
            font_size=FONT_SM
        ).pack(side=LEFT)

        text_frame = Frame(
            self,
            bg=colors['output_bg'],
            highlightthickness=1,
            highlightbackground=colors['border']
        )
        text_frame.pack(fill=BOTH, expand=True, padx=SP['sm'], pady=(0, SP['sm']))

        scrollbar = Scrollbar(
            text_frame,
            bg=colors['bg_secondary'],
            troughcolor=colors['output_bg'],
            activebackground=colors['bg_button'],
            relief='flat',
            bd=0,
            highlightthickness=0
        )
        scrollbar.pack(side=RIGHT, fill=Y)

        self.text = Text(
            text_frame,
            bg=colors['output_bg'],
            fg=colors['output_fg'],
            insertbackground=colors['fg'],
            selectbackground=colors['selection_bg'],
            selectforeground=colors['selection_fg'],
            font=FontManager.mono(FONT_MD),
            wrap=WORD,
            padx=SP['lg'],
            pady=SP['md'],
            spacing1=2,
            spacing3=2,
            relief='flat',
            highlightthickness=0,
            yscrollcommand=scrollbar.set,
            state=DISABLED,
            exportselection=True
        )
        self.text.pack(fill=BOTH, expand=True)
        scrollbar.config(command=self.text.yview)

        self.text.bind('<Button-1>', self._on_text_click)
        self.text.bind('<ButtonRelease-1>', self._on_text_release)

        self.text.tag_configure('info', foreground=colors['fg_info'])
        self.text.tag_configure('success', foreground=colors['fg_success'])
        self.text.tag_configure('error', foreground=colors['fg_error'])
        self.text.tag_configure('stdout', foreground=colors['output_fg'])
        self.text.tag_configure('V', foreground=self.LOG_LEVEL_COLORS['V'])
        self.text.tag_configure('D', foreground=self.LOG_LEVEL_COLORS['D'])
        self.text.tag_configure('I', foreground=self.LOG_LEVEL_COLORS['I'])
        self.text.tag_configure('W', foreground=self.LOG_LEVEL_COLORS['W'])
        self.text.tag_configure('E', foreground=self.LOG_LEVEL_COLORS['E'])
        self.text.tag_configure('F', foreground=self.LOG_LEVEL_COLORS['F'])
        self.text.tag_configure('highlight', background='#ffff00', foreground='#000000')
        self.text.tag_configure('search_current', background='#ff8800', foreground='#000000')

        self._all_lines = []
        self._search_matches = []
        self._search_index = -1

        self._search_bar = Frame(self, bg=colors['bg_group'])
        
        Label(
            self._search_bar,
            text="Find:",
            bg=colors['bg_group'],
            fg=colors['fg'],
            font=FontManager.ui(FONT_SM)
        ).pack(side=LEFT, padx=(SP['sm'], SP['xs']))

        self._search_var = StringVar()
        self._search_entry = Entry(
            self._search_bar,
            textvariable=self._search_var,
            bg=colors['bg_input'],
            fg=colors['fg'],
            insertbackground=colors['fg'],
            relief='flat',
            highlightthickness=1,
            highlightbackground=colors['border'],
            highlightcolor=colors['bg_button'],
            font=FontManager.ui(FONT_SM),
            width=30
        )
        self._search_entry.pack(side=LEFT, padx=(0, SP['xs']))
        self._search_entry.bind('<Return>', self._search_next)
        self._search_entry.bind('<KeyRelease>', self._on_search_change)

        self._search_count_label = Label(
            self._search_bar,
            text="0/0",
            bg=colors['bg_group'],
            fg=colors['fg_dim'],
            font=FontManager.ui(FONT_SM),
            width=8
        )
        self._search_count_label.pack(side=LEFT, padx=(0, SP['xs']))

        _make_button(
            self._search_bar,
            text="▲",
            command=self._search_prev,
            colors=colors,
            kind='ghost',
            padx=SP['sm'],
            pady=SP['xs'],
            font_size=FONT_SM
        ).pack(side=LEFT, padx=(0, SP['xs']))

        _make_button(
            self._search_bar,
            text="▼",
            command=self._search_next,
            colors=colors,
            kind='ghost',
            padx=SP['sm'],
            pady=SP['xs'],
            font_size=FONT_SM
        ).pack(side=LEFT, padx=(0, SP['xs']))

        self._search_regex_var = IntVar(value=0)
        Checkbutton(
            self._search_bar,
            text="Regex",
            variable=self._search_regex_var,
            bg=colors['bg_group'],
            fg=colors['fg'],
            selectcolor=colors['bg_input'],
            activebackground=colors['bg_group'],
            activeforeground=colors['fg'],
            font=FontManager.ui(FONT_SM)
        ).pack(side=LEFT, padx=(0, SP['xs']))

        self._search_case_var = IntVar(value=0)
        Checkbutton(
            self._search_bar,
            text="Aa",
            variable=self._search_case_var,
            bg=colors['bg_group'],
            fg=colors['fg'],
            selectcolor=colors['bg_input'],
            activebackground=colors['bg_group'],
            activeforeground=colors['fg'],
            font=FontManager.ui(FONT_SM)
        ).pack(side=LEFT, padx=(0, SP['sm']))

        _make_button(
            self._search_bar,
            text="✕",
            command=self._close_search,
            colors=colors,
            kind='ghost',
            padx=SP['sm'],
            pady=SP['xs'],
            font_size=FONT_SM
        ).pack(side=RIGHT, padx=(0, SP['sm']))

        search_key = self.settings.get('_keybindings', 'search_open', 'Control-f') if self.settings else 'Control-f'
        close_key = self.settings.get('_keybindings', 'search_close', 'Escape') if self.settings else 'Escape'

        self.text.bind(f'<{search_key}>', self._show_search)
        self.text.bind(f'<{search_key.replace("Control-", "Control-").replace("c-", "C-")}>', self._show_search)
        self.text.bind(f'<{close_key}>', self._close_search)

    def _show_search(self, event=None):
        self._search_bar.pack(fill=X, padx=SP['sm'], pady=(SP['xs'], 0), before=self.text.master)
        self._search_entry.focus_set()
        self._search_entry.select_range(0, END)
        return 'break'

    def _close_search(self, event=None):
        self._search_bar.pack_forget()
        self._search_var.set("")
        self._search_matches = []
        self._search_index = -1
        self.text.config(state=NORMAL)
        self.text.tag_remove('search_current', '1.0', END)
        self.text.config(state=DISABLED)
        self.text.focus_set()
        return 'break'

    def _on_search_change(self, event=None):
        self._perform_search()

    def _perform_search(self):
        keyword = self._search_var.get()
        self._search_matches = []
        self._search_index = -1
        
        self.text.config(state=NORMAL)
        self.text.tag_remove('search_current', '1.0', END)
        
        if not keyword:
            self._search_count_label.config(text="0/0")
            self.text.config(state=DISABLED)
            return
        
        use_regex = self._search_regex_var.get()
        case_sensitive = self._search_case_var.get()
        
        content = self.text.get('1.0', END)
        
        if use_regex:
            try:
                flags = 0 if case_sensitive else re.IGNORECASE
                pattern = re.compile(keyword, flags)
                for match in pattern.finditer(content):
                    start_idx = f"1.0+{match.start()}c"
                    end_idx = f"1.0+{match.end()}c"
                    self._search_matches.append((start_idx, end_idx))
                    self.text.tag_add('search_current', start_idx, end_idx)
            except re.error:
                pass
        else:
            start = '1.0'
            while True:
                if case_sensitive:
                    pos = self.text.search(keyword, start, stopindex=END, exact=True)
                else:
                    pos = self.text.search(keyword, start, stopindex=END, nocase=True)
                if not pos:
                    break
                end = f"{pos}+{len(keyword)}c"
                self._search_matches.append((pos, end))
                self.text.tag_add('search_current', pos, end)
                start = end
        
        count = len(self._search_matches)
        if count > 0:
            self._search_index = 0
            self._search_count_label.config(text=f"1/{count}")
            self.text.see(self._search_matches[0][0])
        else:
            self._search_count_label.config(text="0/0")
        
        self.text.config(state=DISABLED)

    def _search_next(self, event=None):
        if not self._search_matches:
            self._perform_search()
            return
        
        self._search_index = (self._search_index + 1) % len(self._search_matches)
        self._navigate_to_match()

    def _search_prev(self, event=None):
        if not self._search_matches:
            self._perform_search()
            return
        
        self._search_index = (self._search_index - 1) % len(self._search_matches)
        self._navigate_to_match()

    def _navigate_to_match(self):
        if not self._search_matches or self._search_index < 0:
            return
        
        self.text.config(state=NORMAL)
        self.text.tag_remove('search_current', '1.0', END)
        
        start, end = self._search_matches[self._search_index]
        self.text.tag_add('search_current', start, end)
        self.text.see(start)
        
        count = len(self._search_matches)
        self._search_count_label.config(text=f"{self._search_index + 1}/{count}")
        
        self.text.config(state=DISABLED)

    def _export_log(self):
        default_path = self.settings.get('_logcat', 'export_path', '') if self.settings else ''
        
        if default_path:
            import time
            timestamp = time.strftime("%Y%m%d_%H%M%S")
            filepath = os.path.join(default_path, f"logcat_{timestamp}.txt")
        else:
            filepath = filedialog.asksaveasfilename(
                title="Export Log",
                initialfile="logcat.txt",
                defaultextension=".txt",
                filetypes=[("Text files", "*.txt"), ("All files", "*.*")]
            )
            
            if not filepath:
                return
        
        try:
            self.text.config(state=NORMAL)
            content = self.text.get('1.0', END)
            self.text.config(state=DISABLED)
            
            with open(filepath, 'w', encoding='utf-8') as f:
                f.write(content)
            
            messagebox.showinfo("Export", f"Log exported to:\n{filepath}")
        except Exception as e:
            messagebox.showerror("Error", f"Failed to export log:\n{e}")

    def _on_text_click(self, event):
        self.text.config(state=NORMAL)

    def _on_text_release(self, event):
        try:
            if self.text.tag_ranges('sel'):
                pass
            else:
                self.text.config(state=DISABLED)
        except:
            self.text.config(state=DISABLED)

    def show_logcat_bar(self):
        self._logcat_mode = True
        self._paused = False
        self._pause_btn.configure(text="Pause")
        self._logcat_bar.pack(fill=X, padx=SP['sm'], pady=(SP['xs'], 0), before=self.text.master)

    def hide_logcat_bar(self):
        self._logcat_mode = False
        self._paused = False
        self._filter_var.set("")
        self._tag_var.set("")
        self._level_var.set('V')
        self._filter_active = False
        self._all_lines.clear()
        self._logcat_bar.pack_forget()

    def _toggle_pause(self):
        if not self._logcat_mode:
            return
        self._paused = not self._paused
        if self._paused:
            self._pause_btn.configure(text="Resume")
            if self._on_pause:
                self._on_pause()
        else:
            self._pause_btn.configure(text="Pause")
            if self._on_resume:
                self._on_resume()

    def _toggle_auto_scroll(self):
        self._auto_scroll = bool(self._auto_scroll_var.get())

    def _toggle_regex(self):
        self._regex_mode = bool(self._regex_var.get())

    def _on_level_change(self, value):
        self._log_level = value

    def _stop_script(self):
        if self._on_stop:
            self._on_stop()
        self.hide_logcat_bar()

    def _apply_filter(self, event=None):
        keyword = self._filter_var.get().strip()
        self._tag_filter = self._tag_var.get().strip()
        if keyword or self._tag_filter:
            self._filter_active = True
            self._highlight_matches(keyword, self._tag_filter)
        else:
            self._clear_filter()

    def _clear_filter(self):
        self._filter_var.set("")
        self._tag_var.set("")
        self._filter_active = False
        self.text.config(state=NORMAL)
        self.text.tag_remove('highlight', '1.0', END)
        self.text.config(state=DISABLED)

    def _highlight_matches(self, keyword, tag_filter):
        self.text.config(state=NORMAL)
        self.text.tag_remove('highlight', '1.0', END)
        
        if not keyword and not tag_filter:
            self.text.config(state=DISABLED)
            return
        
        content = self.text.get('1.0', END)
        lines = content.split('\n')
        
        for i, line in enumerate(lines):
            if not line.strip():
                continue
            
            match = True
            if tag_filter and tag_filter.lower() not in line.lower():
                match = False
            if keyword:
                if self._regex_mode:
                    try:
                        if not re.search(keyword, line, re.IGNORECASE):
                            match = False
                    except re.error:
                        match = False
                else:
                    if keyword.lower() not in line.lower():
                        match = False
            
            if match:
                line_start = f"{i+1}.0"
                line_end = f"{i+1}.end"
                self.text.tag_add('highlight', line_start, line_end)
        
        self.text.config(state=DISABLED)
        
        first_match = self.text.tag_nextrange('highlight', '1.0')
        if first_match:
            self.text.see(first_match[0])

    def _refresh_display(self):
        self.text.config(state=NORMAL)
        self.text.delete('1.0', END)

        keyword = self._filter_var.get().strip()
        tag_filter = self._tag_var.get().strip()
        level_threshold = self.LOG_LEVELS.get(self._log_level, 0)

        for line_data in self._all_lines:
            line_text = line_data['text']
            line_level = line_data.get('level', 'V')
            line_tag = line_data.get('tag', '')

            level_num = self.LOG_LEVELS.get(line_level, 0)
            if level_num < level_threshold:
                continue

            if tag_filter and tag_filter.lower() not in line_tag.lower():
                continue

            if keyword:
                if self._regex_mode:
                    try:
                        if not re.search(keyword, line_text, re.IGNORECASE):
                            continue
                    except re.error:
                        continue
                else:
                    if keyword.lower() not in line_text.lower():
                        continue

            tag = line_data.get('tag', 'stdout')
            self.text.insert(END, line_text, tag)

            if keyword and self._search_highlight:
                self._highlight_keyword(keyword)

        if self._auto_scroll:
            self.text.see(END)
        self.text.config(state=DISABLED)

    def _highlight_keyword(self, keyword):
        if not keyword:
            return
        self.text.tag_remove('highlight', '1.0', END)
        if self._regex_mode:
            try:
                import re
                pattern = re.compile(keyword, re.IGNORECASE)
                content = self.text.get('1.0', END)
                for match in pattern.finditer(content):
                    start = f"1.0+{match.start()}c"
                    end = f"1.0+{match.end()}c"
                    self.text.tag_add('highlight', start, end)
            except re.error:
                pass
        else:
            start = '1.0'
            while True:
                pos = self.text.search(keyword, start, stopindex=END, nocase=True)
                if not pos:
                    break
                end = f"{pos}+{len(keyword)}c"
                self.text.tag_add('highlight', pos, end)
                start = end

    def _clear_device_logcat(self):
        try:
            adb_path = os.path.expanduser('~/Android/Sdk/platform-tools/adb')
            subprocess.run([adb_path, 'logcat', '-c'], check=True)
            self.clear()
            self._all_lines.clear()
        except subprocess.CalledProcessError:
            pass

    def _parse_logcat_line(self, line):
        match = re.match(r'^(\d{2}-\d{2}\s\d{2}:\d{2}:\d{2}\.\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+(.*?)\s*:\s*(.*)', line)
        if match:
            timestamp, pid, tid, level, tag, message = match.groups()
            return {
                'text': line,
                'level': level,
                'tag': tag,
                'timestamp': timestamp,
                'pid': pid,
                'tid': tid,
                'message': message
            }
        return {'text': line, 'level': 'V', 'tag': ''}

    def set_status(self, status_type: str):
        dot_colors = {
            'idle': self.colors['fg_dim'],
            'info': self.colors['fg_info'],
            'running': self.colors['fg_warning'],
            'success': self.colors['fg_success'],
            'error': self.colors['fg_error'],
        }
        self.status_dot.configure(
            fg=dot_colors.get(status_type, self.colors['fg_dim'])
        )

    def append(self, text: str, tag: str = 'stdout'):
        if self._paused and self._logcat_mode:
            return

        if self._logcat_mode:
            line_data = self._parse_logcat_line(text.rstrip('\n'))
            self._all_lines.append(line_data)

            level_threshold = self.LOG_LEVELS.get(self._log_level, 0)
            level_num = self.LOG_LEVELS.get(line_data.get('level', 'V'), 0)
            if level_num < level_threshold:
                return

            tag_filter = self._tag_var.get().strip()
            if tag_filter and tag_filter.lower() not in line_data.get('tag', '').lower():
                return

            keyword = self._filter_var.get().strip()
            if keyword:
                if self._regex_mode:
                    try:
                        import re
                        if not re.search(keyword, text, re.IGNORECASE):
                            return
                    except re.error:
                        return
                else:
                    if keyword.lower() not in text.lower():
                        return

            display_tag = line_data.get('level', tag)
        else:
            display_tag = tag

        self.text.config(state=NORMAL)
        self.text.insert(END, text, display_tag)
        if self._auto_scroll:
            self.text.see(END)
        self.text.config(state=DISABLED)
        self.text.update_idletasks()

    def copy_all(self):
        self.text.config(state=NORMAL)
        content = self.text.get('1.0', END)
        self.text.config(state=DISABLED)
        self.clipboard_clear()
        self.clipboard_append(content)

    def clear(self):
        self.text.config(state=NORMAL)
        self.text.delete('1.0', END)
        self.text.config(state=DISABLED)


class StatusBar(Frame):
    """Bottom status bar with a color-coded status dot."""

    def __init__(self, parent, colors: dict, **kwargs):
        super().__init__(parent, bg=colors['bg_secondary'], **kwargs)

        self.colors = colors

        # Hairline separator on top
        Frame(self, bg=colors['border'], height=1).pack(fill=X)

        row = Frame(self, bg=colors['bg_secondary'])
        row.pack(fill=X, pady=SP['sm'])

        self.dot = Label(
            row,
            text='●',
            bg=colors['bg_secondary'],
            fg=colors['fg_dim'],
            font=FontManager.ui(FONT_SM),
            padx=SP['md']
        )
        self.dot.pack(side=LEFT)

        self.label = Label(
            row,
            text="Ready",
            bg=colors['bg_secondary'],
            fg=colors['fg_dim'],
            anchor='w',
            pady=SP['sm'],
            font=FontManager.ui(FONT_SM)
        )
        self.label.pack(side=LEFT, fill=X, expand=True)

    def set_status(self, text: str, status_type: str = 'info'):
        color_map = {
            'info': self.colors['fg_info'],
            'success': self.colors['fg_success'],
            'error': self.colors['fg_error'],
            'running': self.colors['fg_warning'],
        }
        color = color_map.get(status_type, self.colors['fg_dim'])
        self.label.config(text=text, fg=color)
        self.dot.config(fg=color)


class PromptsPanel:
    """Persistent prompt storage with list + editor + copy."""

    def __init__(self, parent, colors: dict, settings: SettingsManager):
        self.colors = colors
        self.settings = settings
        self.prompts = []
        self.selected_index = None
        self._ignore_select = False

        self.frame = Frame(parent, bg=colors['bg'])
        self.frame.pack(fill=BOTH, expand=True)

        self._create_toolbar()
        self._create_panels()
        self._load_prompts()

    def _create_toolbar(self):
        toolbar = Frame(self.frame, bg=self.colors['bg'])
        toolbar.pack(fill=X, padx=SP['sm'], pady=SP['sm'])

        Label(
            toolbar,
            text="Name:",
            bg=self.colors['bg'],
            fg=self.colors['fg'],
            font=FontManager.ui(FONT_SM)
        ).pack(side=LEFT, padx=(0, SP['sm']))

        self.name_var = StringVar()
        self.name_entry = Entry(
            toolbar,
            textvariable=self.name_var,
            bg=self.colors['bg_input'],
            fg=self.colors['fg'],
            insertbackground=self.colors['fg'],
            relief='flat',
            highlightthickness=1,
            highlightbackground=self.colors['border'],
            highlightcolor=self.colors['bg_button'],
            font=FontManager.ui(FONT_LG)
        )
        self.name_entry.pack(side=LEFT, fill=X, expand=True)
        self.name_entry.bind('<KeyRelease>', self._schedule_save)
        self.name_entry.bind('<FocusOut>', self._immediate_save)

        _make_button(
            toolbar,
            text="Add",
            command=self._add_prompt,
            colors=self.colors,
            kind='primary',
            padx=SP['md'],
            pady=SP['xs'],
            font_size=FONT_SM
        ).pack(side=LEFT, padx=(SP['sm'], 0))

        _make_button(
            toolbar,
            text="Save",
            command=self._save_current_prompt,
            colors=self.colors,
            kind='secondary',
            padx=SP['md'],
            pady=SP['xs'],
            font_size=FONT_SM
        ).pack(side=LEFT, padx=(SP['sm'], 0))

        _make_button(
            toolbar,
            text="Delete",
            command=self._delete_prompt,
            colors=self.colors,
            kind='danger',
            padx=SP['md'],
            pady=SP['xs'],
            font_size=FONT_SM
        ).pack(side=LEFT, padx=(SP['sm'], 0))

        _make_button(
            toolbar,
            text="Copy",
            command=self._copy_prompt,
            colors=self.colors,
            kind='ghost',
            padx=SP['md'],
            pady=SP['xs'],
            font_size=FONT_SM
        ).pack(side=LEFT, padx=(SP['sm'], 0))

    def _create_panels(self):
        paned = PanedWindow(self.frame, orient='horizontal', bg=self.colors['border'])
        paned.pack(fill=BOTH, expand=True, padx=SP['sm'], pady=(0, SP['sm']))

        list_frame = Frame(paned, bg=self.colors['bg'])
        paned.add(list_frame, minsize=160)

        list_header = Label(
            list_frame,
            text="Prompts",
            bg=self.colors['bg'],
            fg=self.colors['fg_header'],
            font=FontManager.ui(FONT_LG, bold=True),
            anchor='w'
        )
        list_header.pack(fill=X, pady=(0, SP['xs']))

        list_container = Frame(list_frame, bg=self.colors['bg'])
        list_container.pack(fill=BOTH, expand=True)

        scrollbar = Scrollbar(list_container, bg=self.colors['bg_secondary'])
        scrollbar.pack(side=RIGHT, fill=Y)

        self.listbox = Listbox(
            list_container,
            bg=self.colors['bg_input'],
            fg=self.colors['fg'],
            selectbackground=self.colors['selection_bg'],
            selectforeground=self.colors['selection_fg'],
            relief='flat',
            highlightthickness=1,
            highlightbackground=self.colors['border'],
            highlightcolor=self.colors['bg_button'],
            font=FontManager.ui(FONT_LG),
            yscrollcommand=scrollbar.set,
            exportselection=False
        )
        self.listbox.pack(side=LEFT, fill=BOTH, expand=True)
        scrollbar.config(command=self.listbox.yview)

        self.listbox.bind('<<ListboxSelect>>', self._on_select)

        editor_frame = Frame(paned, bg=self.colors['bg'])
        paned.add(editor_frame, minsize=240)

        editor_header = Label(
            editor_frame,
            text="Content",
            bg=self.colors['bg'],
            fg=self.colors['fg_header'],
            font=FontManager.ui(FONT_LG, bold=True),
            anchor='w'
        )
        editor_header.pack(fill=X, pady=(0, SP['xs']))

        text_container = Frame(
            editor_frame,
            bg=self.colors['bg_input'],
            highlightthickness=1,
            highlightbackground=self.colors['border']
        )
        text_container.pack(fill=BOTH, expand=True)

        text_scrollbar = Scrollbar(text_container, bg=self.colors['bg_secondary'])
        text_scrollbar.pack(side=RIGHT, fill=Y)

        self.text = Text(
            text_container,
            bg=self.colors['bg_input'],
            fg=self.colors['fg'],
            insertbackground=self.colors['fg'],
            selectbackground=self.colors['selection_bg'],
            selectforeground=self.colors['selection_fg'],
            font=FontManager.ui(FONT_MD),
            wrap=WORD,
            relief='flat',
            highlightthickness=0,
            yscrollcommand=text_scrollbar.set,
            padx=SP['md'],
            pady=SP['sm'],
            undo=True,
            maxundo=-1
        )
        self.text.pack(side=LEFT, fill=BOTH, expand=True)
        text_scrollbar.config(command=self.text.yview)
        self.text.bind('<KeyRelease>', self._schedule_save)
        self.text.bind('<FocusOut>', self._immediate_save)

        self._save_debounce_id = None

    def _schedule_save(self, event=None):
        if self._save_debounce_id is not None:
            self.frame.after_cancel(self._save_debounce_id)
        self._save_debounce_id = self.frame.after(400, self._debounced_save)

    def _immediate_save(self, event=None):
        if self._save_debounce_id is not None:
            self.frame.after_cancel(self._save_debounce_id)
            self._save_debounce_id = None
        self._save_current_prompt()

    def _debounced_save(self):
        self._save_debounce_id = None
        self._save_current_prompt()

    def _load_prompts(self):
        try:
            self.prompts = self.settings.settings.get('_prompts', [])
        except Exception:
            self.prompts = []

        if not isinstance(self.prompts, list):
            self.prompts = []

        if not self.prompts:
            self.prompts.append({'name': 'New Prompt', 'content': ''})
            self._persist()

        self._update_list()
        self.listbox.selection_set(0)
        self._on_select()

    def _persist(self):
        try:
            self.settings.settings['_prompts'] = self.prompts
            self.settings.save()
        except Exception as e:
            messagebox.showerror("Error", f"Failed to save prompts:\n{e}")

    def _update_list(self):
        self.listbox.delete(0, END)
        for prompt in self.prompts:
            self.listbox.insert(END, prompt.get('name', 'Unnamed'))

    def _on_select(self, event=None):
        if self._ignore_select:
            return

        selection = self.listbox.curselection()
        if not selection:
            return

        new_index = selection[0]
        if self.selected_index is not None and self.selected_index != new_index:
            self._save_current_prompt()

        self.selected_index = new_index
        prompt = self.prompts[self.selected_index]
        self.name_var.set(prompt.get('name', ''))
        self.text.delete('1.0', END)
        self.text.insert('1.0', prompt.get('content', ''))
        self.text.edit_reset()

    def _generate_name(self) -> str:
        base = "New Prompt"
        names = {p.get('name', '') for p in self.prompts}
        if base not in names:
            return base
        index = 2
        while f"{base} {index}" in names:
            index += 1
        return f"{base} {index}"

    def _add_prompt(self):
        self._save_current_prompt()

        name = self._generate_name()
        self.prompts.append({'name': name, 'content': ''})
        self._persist()
        self._update_list()

        index = len(self.prompts) - 1
        self.listbox.selection_clear(0, END)
        self.listbox.selection_set(index)
        self.listbox.see(index)
        self._on_select()
        self.name_entry.focus_set()
        self.name_entry.select_range(0, END)

    def _save_current_prompt(self):
        if self.selected_index is None or self.selected_index >= len(self.prompts):
            return

        name = self.name_var.get().strip()
        if not name:
            name = 'Unnamed'

        content = self.text.get('1.0', END).rstrip('\n')
        self.prompts[self.selected_index] = {'name': name, 'content': content}
        self._persist()

        self._ignore_select = True
        self._update_list()
        self.listbox.selection_set(self.selected_index)
        self.listbox.see(self.selected_index)
        self._ignore_select = False

    def _delete_prompt(self):
        if self.selected_index is None:
            return

        name = self.prompts[self.selected_index].get('name', 'this prompt')
        if not messagebox.askyesno("Confirm", f"Delete \"{name}\"?"):
            return

        del self.prompts[self.selected_index]

        if not self.prompts:
            self.prompts.append({'name': 'New Prompt', 'content': ''})

        self._persist()
        self._update_list()

        new_index = min(self.selected_index, len(self.prompts) - 1)
        self.listbox.selection_set(new_index)
        self.listbox.see(new_index)
        self.selected_index = None
        self._on_select()

    def _copy_prompt(self):
        content = self.text.get('1.0', END).rstrip('\n')
        self.frame.clipboard_clear()
        self.frame.clipboard_append(content)


class Schedule:
    REPEAT_OPTIONS = ['once', 'daily', 'weekdays', 'weekly']

    def __init__(self, data: dict):
        self.id = data.get('id', str(uuid.uuid4())[:8])
        self.name = data.get('name', 'New Schedule')
        self.script_id = data.get('script_id', '')
        self.datetime_str = data.get('datetime', datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S'))
        self.repeat = data.get('repeat', 'once')
        if self.repeat not in self.REPEAT_OPTIONS:
            self.repeat = 'once'
        self.enabled = data.get('enabled', True)
        self.last_run = data.get('last_run', '')

    def to_dict(self) -> dict:
        return {
            'id': self.id,
            'name': self.name,
            'script_id': self.script_id,
            'datetime': self.datetime_str,
            'repeat': self.repeat,
            'enabled': self.enabled,
            'last_run': self.last_run,
        }


class Scheduler:
    def __init__(self, schedules: list, callback):
        self.schedules = schedules
        self.callback = callback
        self.running = False
        self._thread = None

    def start(self):
        if self.running:
            return
        self.running = True
        self._thread = threading.Thread(target=self._loop, daemon=True)
        self._thread.start()

    def stop(self):
        self.running = False

    def _loop(self):
        while self.running:
            try:
                self._check_schedules()
            except Exception as e:
                print(f"Scheduler error: {e}")
            time.sleep(1)

    def _check_schedules(self):
        now = datetime.datetime.now()
        for schedule in self.schedules:
            if not schedule.enabled:
                continue
            if self._should_run(schedule, now):
                schedule.last_run = now.strftime('%Y-%m-%d %H:%M:%S')
                self.callback(schedule)

    def _should_run(self, schedule: Schedule, now: datetime.datetime) -> bool:
        try:
            base = datetime.datetime.strptime(schedule.datetime_str, '%Y-%m-%d %H:%M:%S')
        except Exception:
            return False

        if schedule.last_run:
            try:
                last = datetime.datetime.strptime(schedule.last_run, '%Y-%m-%d %H:%M:%S')
                if (last.year, last.month, last.day, last.hour, last.minute) == (now.year, now.month, now.day, now.hour, now.minute):
                    return False
            except Exception:
                pass

        if schedule.repeat == 'once':
            return now >= base and (now - base).total_seconds() < 60
        elif schedule.repeat == 'daily':
            return (now.hour, now.minute) == (base.hour, base.minute)
        elif schedule.repeat == 'weekdays':
            return now.weekday() < 5 and (now.hour, now.minute) == (base.hour, base.minute)
        elif schedule.repeat == 'weekly':
            return now.weekday() == base.weekday() and (now.hour, now.minute) == (base.hour, base.minute)
        return False


class SchedulesPanel:
    """Schedule management with list + form."""

    def __init__(self, parent, colors: dict, settings: SettingsManager, scripts: list, on_change):
        self.colors = colors
        self.settings = settings
        self.scripts = scripts
        self.on_change = on_change
        self.schedules = []
        self.selected_index = None
        self._ignore_select = False

        self.frame = Frame(parent, bg=colors['bg'])
        self.frame.pack(fill=BOTH, expand=True)

        self._create_toolbar()
        self._create_panels()
        self._load_schedules()

    def _create_toolbar(self):
        toolbar = Frame(self.frame, bg=self.colors['bg'])
        toolbar.pack(fill=X, padx=SP['sm'], pady=SP['sm'])

        _make_button(
            toolbar,
            text="Add",
            command=self._add_schedule,
            colors=self.colors,
            kind='primary',
            padx=SP['md'],
            pady=SP['xs'],
            font_size=FONT_SM
        ).pack(side=LEFT, padx=(0, SP['sm']))

        _make_button(
            toolbar,
            text="Save",
            command=self._save_current_schedule,
            colors=self.colors,
            kind='secondary',
            padx=SP['md'],
            pady=SP['xs'],
            font_size=FONT_SM
        ).pack(side=LEFT, padx=(0, SP['sm']))

        _make_button(
            toolbar,
            text="Delete",
            command=self._delete_schedule,
            colors=self.colors,
            kind='danger',
            padx=SP['md'],
            pady=SP['xs'],
            font_size=FONT_SM
        ).pack(side=LEFT, padx=(0, SP['sm']))

        _make_button(
            toolbar,
            text="Toggle Enable",
            command=self._toggle_enable,
            colors=self.colors,
            kind='ghost',
            padx=SP['md'],
            pady=SP['xs'],
            font_size=FONT_SM
        ).pack(side=LEFT, padx=(0, SP['sm']))

    def _create_panels(self):
        paned = PanedWindow(self.frame, orient='horizontal', bg=self.colors['border'])
        paned.pack(fill=BOTH, expand=True, padx=SP['sm'], pady=(0, SP['sm']))

        list_frame = Frame(paned, bg=self.colors['bg'])
        paned.add(list_frame, minsize=160)

        Label(
            list_frame,
            text="Schedules",
            bg=self.colors['bg'],
            fg=self.colors['fg_header'],
            font=FontManager.ui(FONT_LG, bold=True),
            anchor='w'
        ).pack(fill=X, pady=(0, SP['xs']))

        list_container = Frame(list_frame, bg=self.colors['bg'])
        list_container.pack(fill=BOTH, expand=True)

        scrollbar = Scrollbar(list_container, bg=self.colors['bg_secondary'])
        scrollbar.pack(side=RIGHT, fill=Y)

        self.listbox = Listbox(
            list_container,
            bg=self.colors['bg_input'],
            fg=self.colors['fg'],
            selectbackground=self.colors['selection_bg'],
            selectforeground=self.colors['selection_fg'],
            relief='flat',
            highlightthickness=1,
            highlightbackground=self.colors['border'],
            highlightcolor=self.colors['bg_button'],
            font=FontManager.ui(FONT_MD),
            yscrollcommand=scrollbar.set,
            exportselection=False
        )
        self.listbox.pack(side=LEFT, fill=BOTH, expand=True)
        scrollbar.config(command=self.listbox.yview)
        self.listbox.bind('<<ListboxSelect>>', self._on_select)

        form_frame = Frame(paned, bg=self.colors['bg'])
        paned.add(form_frame, minsize=300)

        Label(
            form_frame,
            text="Schedule Settings",
            bg=self.colors['bg'],
            fg=self.colors['fg_header'],
            font=FontManager.ui(FONT_LG, bold=True),
            anchor='w'
        ).pack(fill=X, pady=(0, SP['xs']))

        self.countdown_label = Label(
            form_frame,
            text="Next run: --",
            bg=self.colors['bg'],
            fg=self.colors['fg_info'],
            font=FontManager.ui(FONT_MD, bold=True),
            anchor='w'
        )
        self.countdown_label.pack(fill=X, pady=(0, SP['sm']))

        self.form = Frame(form_frame, bg=self.colors['bg'])
        self.form.pack(fill=BOTH, expand=True, padx=SP['sm'])

        self._add_form_row("Name:", 'name_var', self.form)
        self._add_script_row(self.form)
        self._add_form_row("DateTime (YYYY-MM-DD HH:MM:SS):", 'datetime_var', self.form, width=30)
        self._add_repeat_row(self.form)
        self._add_enabled_row(self.form)

        Label(
            form_frame,
            text="Execution Log",
            bg=self.colors['bg'],
            fg=self.colors['fg_header'],
            font=FontManager.ui(FONT_LG, bold=True),
            anchor='w'
        ).pack(fill=X, pady=(SP['sm'], SP['xs']))

        log_container = Frame(
            form_frame,
            bg=self.colors['bg_input'],
            highlightthickness=1,
            highlightbackground=self.colors['border']
        )
        log_container.pack(fill=BOTH, expand=True)

        log_scrollbar = Scrollbar(log_container, bg=self.colors['bg_secondary'])
        log_scrollbar.pack(side=RIGHT, fill=Y)

        self.log_text = Text(
            log_container,
            bg=self.colors['bg_input'],
            fg=self.colors['fg_dim'],
            insertbackground=self.colors['fg'],
            selectbackground=self.colors['selection_bg'],
            selectforeground=self.colors['selection_fg'],
            font=FontManager.ui(FONT_SM),
            wrap=WORD,
            relief='flat',
            highlightthickness=0,
            yscrollcommand=log_scrollbar.set,
            padx=SP['md'],
            pady=SP['sm'],
            height=6
        )
        self.log_text.pack(side=LEFT, fill=BOTH, expand=True)
        log_scrollbar.config(command=self.log_text.yview)

    def _add_form_row(self, label: str, var_name: str, parent, width=20):
        row = Frame(parent, bg=self.colors['bg'])
        row.pack(fill=X, pady=SP['xs'])

        Label(
            row,
            text=label,
            bg=self.colors['bg'],
            fg=self.colors['fg'],
            font=FontManager.ui(FONT_SM),
            width=22,
            anchor='w'
        ).pack(side=LEFT)

        var = StringVar()
        setattr(self, var_name, var)
        entry = Entry(
            row,
            textvariable=var,
            bg=self.colors['bg_input'],
            fg=self.colors['fg'],
            insertbackground=self.colors['fg'],
            relief='flat',
            highlightthickness=1,
            highlightbackground=self.colors['border'],
            highlightcolor=self.colors['bg_button'],
            font=FontManager.ui(FONT_MD),
            width=width
        )
        entry.pack(side=LEFT, fill=X, expand=True)

    def _add_script_row(self, parent):
        row = Frame(parent, bg=self.colors['bg'])
        row.pack(fill=X, pady=SP['xs'])

        Label(
            row,
            text="Script:",
            bg=self.colors['bg'],
            fg=self.colors['fg'],
            font=FontManager.ui(FONT_SM),
            width=22,
            anchor='w'
        ).pack(side=LEFT)

        self.script_var = StringVar()
        options = [s.name for s in self.scripts]
        self.script_option = OptionMenu(row, self.script_var, *options) if options else None
        if self.script_option:
            self.script_option.configure(
                bg=self.colors['bg_input'],
                fg=self.colors['fg'],
                activebackground=self.colors['bg_hover'],
                activeforeground=self.colors['fg'],
                highlightthickness=0,
                font=FontManager.ui(FONT_MD)
            )
            self.script_option.pack(side=LEFT, fill=X, expand=True)

    def _add_repeat_row(self, parent):
        row = Frame(parent, bg=self.colors['bg'])
        row.pack(fill=X, pady=SP['xs'])

        Label(
            row,
            text="Repeat:",
            bg=self.colors['bg'],
            fg=self.colors['fg'],
            font=FontManager.ui(FONT_SM),
            width=22,
            anchor='w'
        ).pack(side=LEFT)

        self.repeat_var = StringVar(value='once')
        self.repeat_option = OptionMenu(row, self.repeat_var, *Schedule.REPEAT_OPTIONS)
        self.repeat_option.configure(
            bg=self.colors['bg_input'],
            fg=self.colors['fg'],
            activebackground=self.colors['bg_hover'],
            activeforeground=self.colors['fg'],
            highlightthickness=0,
            font=FontManager.ui(FONT_MD)
        )
        self.repeat_option.pack(side=LEFT, fill=X, expand=True)

    def _add_enabled_row(self, parent):
        row = Frame(parent, bg=self.colors['bg'])
        row.pack(fill=X, pady=SP['xs'])

        self.enabled_var = BooleanVar(value=True)
        cb = Checkbutton(
            row,
            text="Enabled",
            variable=self.enabled_var,
            bg=self.colors['bg'],
            fg=self.colors['fg'],
            selectcolor=self.colors['bg_input'],
            activebackground=self.colors['bg'],
            activeforeground=self.colors['fg'],
            font=FontManager.ui(FONT_SM)
        )
        cb.pack(side=LEFT)

    def _load_schedules(self):
        try:
            raw = self.settings.settings.get('_schedules', [])
            self.schedules = [Schedule(s) for s in raw]
        except Exception:
            self.schedules = []

        self._update_list()
        self._load_log()
        self._update_countdown()
        if self.schedules:
            self.listbox.selection_set(0)
            self._on_select()

    def _compute_next_run(self, schedule: Schedule):
        try:
            base = datetime.datetime.strptime(schedule.datetime_str, '%Y-%m-%d %H:%M:%S')
        except Exception:
            return None

        now = datetime.datetime.now()

        if schedule.repeat == 'once':
            return base if base > now else None
        elif schedule.repeat == 'daily':
            next_run = now.replace(hour=base.hour, minute=base.minute, second=base.second, microsecond=0)
            if next_run <= now:
                next_run += datetime.timedelta(days=1)
            return next_run
        elif schedule.repeat == 'weekdays':
            next_run = now.replace(hour=base.hour, minute=base.minute, second=base.second, microsecond=0)
            if next_run <= now:
                next_run += datetime.timedelta(days=1)
            while next_run.weekday() >= 5:
                next_run += datetime.timedelta(days=1)
            return next_run
        elif schedule.repeat == 'weekly':
            days_ahead = base.weekday() - now.weekday()
            if days_ahead <= 0:
                days_ahead += 7
            next_run = now + datetime.timedelta(days=days_ahead)
            next_run = next_run.replace(hour=base.hour, minute=base.minute, second=base.second, microsecond=0)
            return next_run
        return None

    def _format_delta(self, delta: datetime.timedelta) -> str:
        total = int(delta.total_seconds())
        if total < 0:
            total = 0
        hours = total // 3600
        minutes = (total % 3600) // 60
        seconds = total % 60
        if hours > 0:
            return f"{hours}h {minutes}m {seconds}s"
        elif minutes > 0:
            return f"{minutes}m {seconds}s"
        else:
            return f"{seconds}s"

    def _update_countdown(self):
        next_runs = []
        for schedule in self.schedules:
            if not schedule.enabled:
                continue
            next_run = self._compute_next_run(schedule)
            if next_run:
                next_runs.append((next_run, schedule.name))

        if next_runs:
            next_runs.sort()
            next_time, name = next_runs[0]
            delta = next_time - datetime.datetime.now()
            text = f"Next run: {name} in {self._format_delta(delta)}"
        else:
            text = "Next run: --"

        self.countdown_label.config(text=text)
        self.frame.after(1000, self._update_countdown)

    def _load_log(self):
        try:
            logs = self.settings.settings.get('_schedule_logs', [])
        except Exception:
            logs = []
        self.log_text.delete('1.0', END)
        for entry in logs[-50:]:
            self._append_log_line(entry)

    def _append_log_line(self, entry: dict):
        timestamp = entry.get('time', '')
        name = entry.get('name', 'Unknown')
        status = entry.get('status', 'info')
        message = entry.get('message', '')
        self.log_text.insert(END, f"{timestamp}  {name}  [{status}] {message}\n")
        self.log_text.see(END)

    def add_log(self, schedule_name: str, status: str, message: str):
        entry = {
            'time': datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S'),
            'name': schedule_name,
            'status': status,
            'message': message,
        }
        logs = self.settings.settings.get('_schedule_logs', [])
        logs.append(entry)
        self.settings.settings['_schedule_logs'] = logs[-100:]
        self.settings.save()
        self._append_log_line(entry)

    def _persist(self):
        try:
            self.settings.settings['_schedules'] = [s.to_dict() for s in self.schedules]
            self.settings.save()
            if self.on_change:
                self.on_change(self.schedules)
        except Exception as e:
            messagebox.showerror("Error", f"Failed to save schedules:\n{e}")

    def _update_list(self):
        self._ignore_select = True
        self.listbox.delete(0, END)
        for schedule in self.schedules:
            status = "[ON]" if schedule.enabled else "[OFF]"
            self.listbox.insert(END, f"{status} {schedule.name}")
        self._ignore_select = False

    def _on_select(self, event=None):
        if self._ignore_select:
            return

        selection = self.listbox.curselection()
        if not selection:
            return

        self.selected_index = selection[0]
        schedule = self.schedules[self.selected_index]
        self.name_var.set(schedule.name)
        self.datetime_var.set(schedule.datetime_str)
        self.repeat_var.set(schedule.repeat)
        self.enabled_var.set(schedule.enabled)

        script_name = ""
        for script in self.scripts:
            if script.id == schedule.script_id:
                script_name = script.name
                break
        if script_name and self.script_option:
            self.script_var.set(script_name)
        elif self.script_option and self.scripts:
            self.script_var.set(self.scripts[0].name)

    def _get_script_id(self) -> str:
        if not self.script_option:
            return ""
        name = self.script_var.get()
        for script in self.scripts:
            if script.name == name:
                return script.id
        return ""

    def _add_schedule(self):
        now = datetime.datetime.now()
        default_time = (now + datetime.timedelta(minutes=1)).strftime('%Y-%m-%d %H:%M:%S')
        default_script_id = self.scripts[0].id if self.scripts else ""

        schedule = Schedule({
            'name': 'New Schedule',
            'script_id': default_script_id,
            'datetime': default_time,
            'repeat': 'once',
            'enabled': True
        })
        self.schedules.append(schedule)
        self._persist()
        self._update_list()

        index = len(self.schedules) - 1
        self.listbox.selection_clear(0, END)
        self.listbox.selection_set(index)
        self.listbox.see(index)
        self._on_select()

    def _save_current_schedule(self):
        if self.selected_index is None or self.selected_index >= len(self.schedules):
            return

        schedule = self.schedules[self.selected_index]
        schedule.name = self.name_var.get().strip() or 'Unnamed'
        schedule.script_id = self._get_script_id()
        schedule.datetime_str = self.datetime_var.get().strip()
        schedule.repeat = self.repeat_var.get()
        schedule.enabled = self.enabled_var.get()

        self._persist()
        self._update_list()

    def _delete_schedule(self):
        if self.selected_index is None:
            return

        if not messagebox.askyesno("Confirm", "Delete this schedule?"):
            return

        del self.schedules[self.selected_index]
        self._persist()
        self._update_list()

        new_index = min(self.selected_index, len(self.schedules) - 1)
        if self.schedules:
            self.listbox.selection_set(new_index)
            self.listbox.see(new_index)
            self.selected_index = None
            self._on_select()

    def _toggle_enable(self):
        if self.selected_index is None:
            return

        schedule = self.schedules[self.selected_index]
        schedule.enabled = not schedule.enabled
        self.enabled_var.set(schedule.enabled)
        self._persist()
        self._update_list()


class UIScreenshotPanel:
    """38 张 AppStore UI 截图的触发面板。

    左侧为按钮网格，点击触发对应序号的截图脚本；
    右键菜单支持重命名按钮，名称持久化到 settings.json。
    """

    def __init__(self, parent, colors, settings, runner, base_dir, output_panel):
        self.parent = parent
        self.colors = colors
        self.settings = settings
        self.runner = runner
        self.base_dir = base_dir
        self.output_panel = output_panel
        self._buttons = {}
        self._create_widgets()

    def _create_widgets(self):
        self.frame = Frame(self.parent, bg=self.colors['bg'])
        self.frame.pack(fill=BOTH, expand=True)

        # 顶部操作栏
        toolbar = Frame(self.frame, bg=self.colors['bg'])
        toolbar.pack(fill=X, padx=SP['lg'], pady=SP['md'])

        Label(
            toolbar,
            text="AppStore UI 截图",
            bg=self.colors['bg'],
            fg=self.colors['fg_header'],
            font=FontManager.ui(FONT_2XL, bold=True)
        ).pack(side=LEFT)

        self.capture_all_btn = _make_button(
            toolbar,
            text="全部捕获",
            command=self._capture_all,
            colors=self.colors,
            kind='primary',
            padx=SP['md'],
            pady=SP['xs'],
            font_size=FONT_SM
        )
        self.capture_all_btn.pack(side=LEFT, padx=(SP['lg'], 0))

        self.reset_names_btn = _make_button(
            toolbar,
            text="重置名称",
            command=self._reset_names,
            colors=self.colors,
            kind='secondary',
            padx=SP['md'],
            pady=SP['xs'],
            font_size=FONT_SM
        )
        self.reset_names_btn.pack(side=LEFT, padx=(SP['sm'], 0))

        Label(
            toolbar,
            text="右键按钮可修改名称",
            bg=self.colors['bg'],
            fg=self.colors['fg_dim'],
            font=FontManager.ui(FONT_SM)
        ).pack(side=LEFT, padx=(SP['lg'], 0))

        # 状态栏
        self.status_label = Label(
            toolbar,
            text="",
            bg=self.colors['bg'],
            fg=self.colors['fg_info'],
            font=FontManager.ui(FONT_SM, bold=True)
        )
        self.status_label.pack(side=RIGHT, padx=(SP['lg'], 0))

        # 滚动区域
        container = Frame(self.frame, bg=self.colors['bg'])
        container.pack(fill=BOTH, expand=True, padx=SP['lg'], pady=(0, SP['lg']))

        canvas = Canvas(container, bg=self.colors['bg'], highlightthickness=0)
        scrollbar = Scrollbar(container, orient="vertical", command=canvas.yview)
        self.grid_frame = Frame(canvas, bg=self.colors['bg'])

        self.grid_frame.bind(
            "<Configure>",
            lambda e: canvas.configure(scrollregion=canvas.bbox("all"))
        )
        canvas.create_window((0, 0), window=self.grid_frame, anchor="nw", tags="grid")
        canvas.configure(yscrollcommand=scrollbar.set)

        def _on_canvas_configure(event):
            canvas.itemconfig("grid", width=event.width)

        canvas.bind("<Configure>", _on_canvas_configure)
        canvas.pack(side=LEFT, fill=BOTH, expand=True)
        scrollbar.pack(side=RIGHT, fill=Y)

        # 绑定鼠标滚轮
        def _on_mousewheel(event):
            canvas.yview_scroll(int(-1 * (event.delta / 120)), "units")
        canvas.bind_all("<MouseWheel>", _on_mousewheel)
        canvas.bind_all("<Button-4>", lambda e: canvas.yview_scroll(-3, "units"))
        canvas.bind_all("<Button-5>", lambda e: canvas.yview_scroll(3, "units"))

        self._render_grid()

    def _render_grid(self):
        for widget in self.grid_frame.winfo_children():
            widget.destroy()
        self._buttons.clear()

        for idx, (index, default_name) in enumerate(UI_SCREENSHOTS):
            row = idx // 2
            col = idx % 2
            name = self.settings.get('_ui_screenshot_names', index, default_name)

            card = Frame(
                self.grid_frame,
                bg=self.colors['bg_secondary'],
                highlightthickness=1,
                highlightbackground=self.colors['border']
            )
            card.grid(row=row, column=col, sticky='nsew', padx=SP['sm'], pady=SP['sm'])

            header = Frame(card, bg=self.colors['bg_secondary'])
            header.pack(fill=X, padx=SP['md'], pady=SP['md'])

            Label(
                header,
                text=index,
                bg=self.colors['bg_secondary'],
                fg=self.colors['accent'],
                font=FontManager.ui(FONT_XS, bold=True)
            ).pack(side=LEFT)

            btn = _make_button(
                card,
                text=name,
                command=lambda i=index: self._capture_one(i),
                colors=self.colors,
                kind='secondary',
                padx=SP['md'],
                pady=SP['sm'],
                font_size=FONT_SM,
                anchor='w'
            )
            btn.pack(fill=X, padx=SP['md'], pady=(0, SP['md']))
            self._buttons[index] = btn

            # 右键菜单
            menu = Menu(self.parent, tearoff=0)
            menu.configure(
                bg=self.colors['bg_secondary'],
                fg=self.colors['fg'],
                activebackground=self.colors['bg_hover'],
                activeforeground=self.colors['fg'],
                relief='flat',
                bd=0,
                font=FontManager.ui(FONT_MD)
            )
            menu.add_command(label="重命名", command=lambda i=index: self._rename(i))
            menu.add_command(label="捕获", command=lambda i=index: self._capture_one(i))

            btn.bind("<Button-3>", lambda e, m=menu: self._show_menu(e, m))

        # 让两列等宽
        self.grid_frame.grid_columnconfigure(0, weight=1)
        self.grid_frame.grid_columnconfigure(1, weight=1)

    def _show_menu(self, event, menu):
        try:
            menu.tk_popup(event.x_root, event.y_root)
        finally:
            menu.grab_release()

    def _capture_one(self, index):
        script = ScriptConfig({
            'id': f'ui_screenshot_{index}',
            'name': f'UI Screenshot {index}',
            'command': (
                f"python3 {UI_SCREENSHOT_SCRIPT_PATH} "
                f"--only {index} --launch-only"
            ),
            'group': 'UI',
        })
        self._start_capture(f"正在拉起 {index} ...")
        self.output_panel.clear()
        self.output_panel.set_status('info')
        self.runner.execute(script, self.base_dir)

    def _capture_all(self):
        script = ScriptConfig({
            'id': 'ui_screenshot_all',
            'name': 'UI Screenshots All',
            'command': (
                f"python3 {UI_SCREENSHOT_SCRIPT_PATH} "
                f"--launch-only"
            ),
            'group': 'UI',
        })
        self._start_capture("正在拉起全部 38 个模拟数据页面 ...")
        self.output_panel.clear()
        self.output_panel.set_status('info')
        self.runner.execute(script, self.base_dir)

    def _start_capture(self, message):
        self.status_label.config(text=message, fg=self.colors['fg_info'])
        self._set_buttons_enabled(False)
        self._poll_status()

    def _set_buttons_enabled(self, enabled):
        state = NORMAL if enabled else DISABLED
        for btn in list(self._buttons.values()) + [self.capture_all_btn, self.reset_names_btn]:
            try:
                btn.config(state=state)
            except Exception:
                pass

    def _poll_status(self):
        if not self.runner.running:
            self._finish_capture()
            return
        self.frame.after(200, self._poll_status)

    def _finish_capture(self):
        self.status_label.config(text="完成", fg=self.colors['fg_success'])
        self._set_buttons_enabled(True)
        # 3 秒后清空状态
        self.frame.after(3000, lambda: self.status_label.config(text=""))

    def _reset_names(self):
        for index, default_name in UI_SCREENSHOTS:
            self.settings.set('_ui_screenshot_names', index, default_name)
        self._render_grid()

    def _rename(self, index):
        default_name = next((name for i, name in UI_SCREENSHOTS if i == index), index)
        current = self.settings.get('_ui_screenshot_names', index, default_name)

        dialog = Toplevel(self.parent)
        dialog.title("重命名")
        dialog.transient(self.parent)
        dialog.grab_set()
        dialog.configure(bg=self.colors['bg'])
        dialog.geometry("500x160")
        dialog.resizable(False, False)

        Label(
            dialog,
            text=f"编号 {index} 的显示名称：",
            bg=self.colors['bg'],
            fg=self.colors['fg'],
            font=FontManager.ui(FONT_MD)
        ).pack(anchor=W, padx=SP['xl'], pady=(SP['xl'], SP['md']))

        var = StringVar(value=current)
        entry = _create_entry(dialog, self.colors, var)
        entry.pack(fill=X, padx=SP['xl'], pady=SP['sm'])
        entry.select_range(0, END)
        entry.focus_set()

        def _on_ok(event=None):
            value = var.get().strip()
            if value:
                self.settings.set('_ui_screenshot_names', index, value)
                self._render_grid()
            dialog.destroy()

        def _on_cancel(event=None):
            dialog.destroy()

        entry.bind('<Return>', _on_ok)
        entry.bind('<Escape>', _on_cancel)

        btn_frame = Frame(dialog, bg=self.colors['bg'])
        btn_frame.pack(fill=X, padx=SP['xl'], pady=SP['xl'])

        _make_button(
            btn_frame,
            text="确定",
            command=_on_ok,
            colors=self.colors,
            kind='primary',
            padx=SP['xl'],
            pady=SP['xs'],
            font_size=FONT_MD
        ).pack(side=RIGHT, padx=(SP['sm'], 0))

        _make_button(
            btn_frame,
            text="取消",
            command=_on_cancel,
            colors=self.colors,
            kind='secondary',
            padx=SP['xl'],
            pady=SP['xs'],
            font_size=FONT_MD
        ).pack(side=RIGHT)


class LauncherApp:
    def __init__(self, config_path: str):
        self.config_path = Path(config_path)
        self.base_dir = self.config_path.parent

        with open(self.config_path, 'r', encoding='utf-8') as f:
            self.config = AppConfig(json.load(f))

        self.colors = ThemeManager.get(self.config.theme)
        self.settings = SettingsManager(self.base_dir / 'settings.json')
        self.output_queue = queue.Queue()
        self.runner = ScriptRunner(self.output_queue)

        self._setup_window()
        self._create_menu()
        self._create_layout()
        self._load_scripts()
        self._start_scheduler()
        self._start_output_consumer()

    def _setup_window(self):
        self.root = Tk(className='ADBScriptLauncher')
        self.root.title(self.config.title)
        self.root.geometry(f"{self.config.width}x{self.config.height}")
        self.root.minsize(600, 400)
        self.root.configure(bg=self.colors['bg'])

        FontManager.init(self.root)

        self.root.bind_class('Entry', '<Control-a>', self._select_all_entry)
        self.root.bind_class('Text', '<Control-a>', self._select_all_text)

        try:
            icon_path = Path(__file__).parent / 'adb_script_icon_128.png'
            if icon_path.exists():
                from tkinter import PhotoImage
                icon = PhotoImage(file=str(icon_path))
                self.root.iconphoto(True, icon)
        except Exception:
            pass

        self.root.protocol("WM_DELETE_WINDOW", self._on_close)

    @staticmethod
    def _select_all_entry(event):
        event.widget.select_range(0, END)
        event.widget.icursor(END)
        return 'break'

    @staticmethod
    def _select_all_text(event):
        event.widget.tag_add('sel', '1.0', END)
        event.widget.mark_set('insert', END)
        return 'break'

    def _create_menu(self):
        c = self.colors

        def themed_menu(parent):
            menu = Menu(parent, tearoff=0)
            menu.configure(
                bg=c['bg_secondary'],
                fg=c['fg'],
                activebackground=c['bg_hover'],
                activeforeground=c['fg'],
                disabledforeground=c['fg_dim'],
                relief='flat',
                bd=0,
                font=FontManager.ui(FONT_MD)
            )
            return menu

        menubar = themed_menu(self.root)

        file_menu = themed_menu(menubar)
        file_menu.add_command(label="Reload Config", command=self._reload_config)
        file_menu.add_command(label="Open Config", command=self._open_config)
        file_menu.add_separator()
        file_menu.add_command(label="Exit", command=self._on_close)
        menubar.add_cascade(label="File", menu=file_menu)

        menubar.add_command(label="Settings", command=self._open_settings)

        theme_menu = themed_menu(menubar)
        theme_menu.add_command(label="Dark", command=lambda: self._set_theme('dark'))
        theme_menu.add_command(label="Light", command=lambda: self._set_theme('light'))
        menubar.add_cascade(label="Theme", menu=theme_menu)

        help_menu = themed_menu(menubar)
        help_menu.add_command(label="About", command=self._show_about)
        menubar.add_cascade(label="Help", menu=help_menu)

        menubar.add_command(label="Refresh", command=self._reload_config)

        self.root.config(menu=menubar)

    def _create_layout(self):
        self.notebook = ttk.Notebook(self.root)
        self.notebook.pack(fill=BOTH, expand=True)
        
        scripts_tab = Frame(self.notebook, bg=self.colors['bg'])
        self.notebook.add(scripts_tab, text="  Scripts  ")
        
        scripts_paned = PanedWindow(scripts_tab, orient='horizontal', bg=self.colors['border'])
        scripts_paned.pack(fill=BOTH, expand=True)

        self.left_panel = Frame(scripts_paned, bg=self.colors['bg'], width=416)
        scripts_paned.add(self.left_panel, minsize=286)

        self.output_panel = OutputPanel(
            scripts_paned, self.colors, self.settings,
            on_stop=self._stop_script,
            on_pause=self._pause_script,
            on_resume=self._resume_script
        )
        scripts_paned.add(self.output_panel, minsize=320)
        
        wms_tab = Frame(self.notebook, bg=self.colors['bg'])
        self.notebook.add(wms_tab, text="  WMS Viewer  ")
        
        from wms_viewer_module import WMSViewerPanel
        self.wms_panel = WMSViewerPanel(wms_tab, self.colors, FontManager)
        self.wms_panel.frame.pack(fill=BOTH, expand=True)

        prompts_tab = Frame(self.notebook, bg=self.colors['bg'])
        self.notebook.add(prompts_tab, text="  Prompts  ")

        self.prompts_panel = PromptsPanel(prompts_tab, self.colors, self.settings)

        schedules_tab = Frame(self.notebook, bg=self.colors['bg'])
        self.notebook.add(schedules_tab, text="  Schedules  ")

        self.schedules_panel = SchedulesPanel(
            schedules_tab, self.colors, self.settings, self.config.scripts,
            on_change=self._on_schedules_change
        )

        ui_tab = Frame(self.notebook, bg=self.colors['bg'])
        self.notebook.add(ui_tab, text="  UI  ")

        self.ui_panel = UIScreenshotPanel(
            ui_tab, self.colors, self.settings, self.runner, self.base_dir,
            self.output_panel
        )

        self.status_bar = StatusBar(self.root, self.colors)
        self.status_bar.pack(side='bottom', fill=X)

    def _load_scripts(self):
        for widget in self.left_panel.winfo_children():
            widget.destroy()

        groups = {}
        for script in self.config.scripts:
            groups.setdefault(script.group, []).append(script)

        self.group_frames = {}
        for group in self.config.groups:
            frame = GroupFrame(self.left_panel, group, self.colors)
            frame.pack(fill=X, padx=SP['sm'], pady=SP['sm'])
            self.group_frames[group.id] = frame

            for script in groups.get(group.id, []):
                enabled = self.settings.get_enabled(script.id, script.enabled)
                btn = ScriptButton(frame.content, script, self.colors, self._run_script, enabled=enabled)
                frame.add_script_button(btn)

        ungrouped = [s for s in self.config.scripts if s.group not in self.group_frames]
        if ungrouped:
            default_group = GroupConfig({'id': '_default', 'label': 'Scripts'})
            frame = GroupFrame(self.left_panel, default_group, self.colors)
            frame.pack(fill=X, padx=SP['sm'], pady=SP['sm'])

            for script in ungrouped:
                enabled = self.settings.get_enabled(script.id, script.enabled)
                btn = ScriptButton(frame.content, script, self.colors, self._run_script, enabled=enabled)
                frame.add_script_button(btn)

    def _start_output_consumer(self):
        self._consume_output()

    def _consume_output(self):
        try:
            while True:
                msg_type, text = self.output_queue.get_nowait()
                self.output_panel.append(text, msg_type)

                if msg_type == 'info' and '>>>' in text:
                    self.status_bar.set_status("Running...", 'running')
                    self.output_panel.set_status('running')
                elif msg_type == 'success':
                    self.status_bar.set_status("Completed", 'success')
                    self.output_panel.set_status('success')
                elif msg_type == 'error':
                    self.status_bar.set_status("Failed", 'error')
                    self.output_panel.set_status('error')
        except queue.Empty:
            pass

        self.root.after(10, self._consume_output)

    def _run_script(self, script: ScriptConfig):
        if script.parameters:
            all_configured = True
            for param in script.parameters:
                saved = self.settings.get(script.id, param.name, param.default)
                if param.required and not saved:
                    all_configured = False
                    break

            if all_configured:
                params = {p.name: self.settings.get(script.id, p.name, p.default) for p in script.parameters}
            else:
                dialog = ParameterDialog(self.root, script, self.colors, self.settings)
                self.root.wait_window(dialog)

                if dialog.result is None:
                    return

                params = dialog.result
        else:
            params = None

        self.output_panel.clear()
        self.output_panel.set_status('info')
        self.status_bar.set_status(f"Starting {script.name}...", 'info')
        
        if 'logcat' in script.command.lower():
            self.output_panel.show_logcat_bar()
            self.output_panel._clear_device_logcat()
        else:
            self.output_panel.hide_logcat_bar()
        
        self.runner.execute(script, self.base_dir, params)

    def _stop_script(self):
        if self.runner.running:
            self.runner.stop()
            self.status_bar.set_status("Script stopped", 'warning')
            self.output_panel.set_status('idle')
            self.output_panel.append("\n--- Script stopped by user ---\n", 'info')

    def _pause_script(self):
        if self.runner.running:
            self.runner.pause()
            self.status_bar.set_status("Paused", 'warning')

    def _resume_script(self):
        if self.runner.running:
            self.runner.resume()
            self.status_bar.set_status("Running...", 'running')

    def _open_settings(self):
        dialog = SettingsDialog(self.root, self.config, self.colors, self.settings)
        self.root.wait_window(dialog)
        self._load_scripts()

    def _reload_config(self):
        try:
            with open(self.config_path, 'r', encoding='utf-8') as f:
                self.config = AppConfig(json.load(f))
            self._load_scripts()
            self.status_bar.set_status("Config reloaded", 'success')
        except Exception as e:
            messagebox.showerror("Error", f"Failed to reload config:\n{e}")

    def _open_config(self):
        import platform
        if platform.system() == 'Darwin':
            subprocess.run(['open', str(self.config_path)])
        elif platform.system() == 'Windows':
            os.startfile(str(self.config_path))
        else:
            subprocess.run(['xdg-open', str(self.config_path)])

    def _set_theme(self, theme_name: str):
        if messagebox.askyesno("Theme Change", "Theme change requires restart. Restart now?"):
            self.config = AppConfig({
                'app': {'theme': theme_name},
                'scripts': [vars(s) for s in self.config.scripts],
                'groups': [vars(g) for g in self.config.groups]
            })
            python = sys.executable
            os.execl(python, python, *sys.argv)

    def _start_scheduler(self):
        self.scheduler = Scheduler(
            self.schedules_panel.schedules,
            lambda s: self.root.after(0, self._run_scheduled_script, s)
        )
        self.scheduler.start()

    def _on_schedules_change(self, schedules):
        if hasattr(self, 'scheduler'):
            self.scheduler.schedules = schedules

    def _run_scheduled_script(self, schedule: Schedule):
        script = None
        for s in self.config.scripts:
            if s.id == schedule.script_id:
                script = s
                break

        if not script:
            self.output_panel.append(
                f"[Scheduler] '{schedule.name}' failed: script not found\n", 'error'
            )
            return

        if self.runner.running:
            self.output_panel.append(
                f"[Scheduler] '{schedule.name}' skipped: another script is running\n", 'warning'
            )
            return

        params = {}
        for param in script.parameters:
            value = self.settings.get(script.id, param.name, param.default)
            if param.required and not value:
                self.output_panel.append(
                    f"[Scheduler] '{schedule.name}' failed: missing {param.label}\n", 'error'
                )
                return
            params[param.name] = value

        self.output_panel.clear()
        self.output_panel.set_status('info')
        self.status_bar.set_status(f"Scheduled: {script.name}", 'running')

        # Persist last_run time to avoid duplicate executions
        try:
            self.settings.settings['_schedules'] = [s.to_dict() for s in self.scheduler.schedules]
            self.settings.save()
        except Exception:
            pass

        if self.schedules_panel:
            self.schedules_panel.add_log(schedule.name, 'running', f"Triggered: {script.name}")

        if 'logcat' in script.command.lower():
            self.output_panel.show_logcat_bar()
            self.output_panel._clear_device_logcat()
        else:
            self.output_panel.hide_logcat_bar()

        self.runner.execute(script, self.base_dir, params)

    def _show_about(self):
        messagebox.showinfo(
            "About",
            f"{self.config.title}\n\n"
            f"A configurable script launcher.\n\n"
            f"Config: {self.config_path}\n"
            f"Scripts: {len(self.config.scripts)}"
        )

    def _on_close(self):
        if hasattr(self, 'scheduler'):
            self.scheduler.stop()
        if self.runner.running:
            if messagebox.askyesno("Confirm", "A script is running. Stop and exit?"):
                self.runner.stop()
            else:
                return
        self.root.destroy()

    def run(self):
        self.root.mainloop()


def bring_existing_to_front():
    """激活已有的窗口实例"""
    try:
        result = subprocess.run(['wmctrl', '-l'], capture_output=True, text=True)
        if result.returncode == 0:
            for line in result.stdout.splitlines():
                if 'ADB Script Launcher' in line:
                    window_id = line.split()[0]
                    subprocess.run(['wmctrl', '-i', '-a', window_id])
                    return True
    except FileNotFoundError:
        try:
            result = subprocess.run(
                ['xdotool', 'search', '--name', 'ADB Script Launcher'],
                capture_output=True, text=True
            )
            if result.returncode == 0 and result.stdout.strip():
                window_id = result.stdout.strip().split()[0]
                subprocess.run(['xdotool', 'windowactivate', window_id])
                return True
        except FileNotFoundError:
            pass
    return False


def check_single_instance():
    """检查是否已有实例在运行，如果是则激活已有窗口"""
    lock_file = Path('/tmp/adb_launcher.lock')
    
    if lock_file.exists():
        try:
            pid = int(lock_file.read_text().strip())
            os.kill(pid, 0)
            if bring_existing_to_front():
                sys.exit(0)
            else:
                print("ADB Script Launcher is already running.")
                sys.exit(0)
        except (ProcessLookupError, ValueError):
            lock_file.unlink()
    
    lock_file.write_text(str(os.getpid()))
    return lock_file


def main():
    lock_file = check_single_instance()
    
    try:
        if len(sys.argv) > 1:
            config_path = sys.argv[1]
        else:
            config_path = Path(__file__).parent / 'config.json'

        if not Path(config_path).exists():
            print(f"Error: Config file not found: {config_path}")
            sys.exit(1)

        app = LauncherApp(config_path)
        app.run()
    finally:
        try:
            lock_file.unlink()
        except:
            pass


if __name__ == '__main__':
    main()
