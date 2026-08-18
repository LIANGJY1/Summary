#!/usr/bin/env python3
"""ADB Script Launcher - Configurable GUI for executing shell scripts."""

import json
import os
import subprocess
import sys
import threading
import queue
import time
from pathlib import Path
from tkinter import (
    Tk, Frame, Label, Button, Text, Scrollbar, Entry, Toplevel, Canvas, LabelFrame,
    END, BOTH, LEFT, RIGHT, TOP, BOTTOM, Y, X, WORD, DISABLED, NORMAL, W, E,
    StringVar, IntVar, Menu, messagebox, filedialog
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
    def __init__(self, parent, config: AppConfig, colors: dict, settings: SettingsManager):
        super().__init__(parent)
        self.config = config
        self.colors = colors
        self.settings = settings
        self.entries = {}

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
            text="Persisted defaults for script parameters",
            bg=self.colors['bg'],
            fg=self.colors['fg_dim'],
            font=FontManager.ui(FONT_SM)
        ).pack(anchor=W, pady=(0, SP['lg']))

        for script in self.config.scripts:
            if not script.parameters:
                continue

            script_card = Frame(
                inner,
                bg=self.colors['bg_secondary'],
                highlightthickness=1,
                highlightbackground=self.colors['border']
            )
            script_card.pack(fill=X, pady=SP['sm'])

            inner_card = Frame(script_card, bg=self.colors['bg_secondary'])
            inner_card.pack(fill=X, padx=SP['xl'], pady=SP['lg'])

            Label(
                inner_card,
                text=script.name,
                bg=self.colors['bg_secondary'],
                fg=self.colors['fg_header'],
                font=FontManager.ui(FONT_2XL, bold=True)
            ).pack(anchor=W)

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

    def _browse_file(self, var: StringVar):
        filename = filedialog.askopenfilename()
        if filename:
            var.set(filename)

    def _on_save(self):
        for (script_id, param_name), var in self.entries.items():
            self.settings.set(script_id, param_name, var.get())

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

            self.process = subprocess.Popen(
                command,
                shell=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                cwd=str(cwd),
                env=env,
                bufsize=1,
                universal_newlines=True
            )

            for line in iter(self.process.stdout.readline, ''):
                if not self.running:
                    break
                self.output_queue.put(('stdout', line))

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

    def __init__(self, parent, script: ScriptConfig, colors: dict, on_click, **kwargs):
        self.script = script
        self.colors = colors
        self._hovered = False

        super().__init__(
            parent,
            bg=colors['bg_secondary'],
            highlightthickness=1,
            highlightbackground=colors['border'],
            highlightcolor=colors['bg_button'],
            **kwargs
        )

        # Left accent indicator — widens on hover for tactile feedback
        self.indicator = Frame(self, bg=colors['accent'], width=SP['xs'])
        self.indicator.pack(side=LEFT, fill=Y)

        self._btn = Button(
            self,
            text=f"  ▸  {script.name}",
            command=lambda: on_click(script),
            bg=colors['bg_secondary'],
            fg=colors['fg'],
            activebackground=colors['bg_hover'],
            activeforeground=colors['fg'],
            relief='flat',
            padx=SP['sm'],
            pady=SP['sm'],
            cursor='hand2',
            anchor='w',
            font=FontManager.ui(FONT_MD),
            highlightthickness=0,
            bd=0,
        )
        self._btn.pack(side=LEFT, fill=X, expand=True)

        # Hover state must cover the whole row (bar + label)
        for w in (self, self._btn):
            w.bind('<Enter>', self._on_hover_enter)
            w.bind('<Leave>', self._on_hover_leave)

        self.tooltip = None
        self._tooltip_after_id = None
        if script.description:
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

    def __init__(self, parent, colors: dict, **kwargs):
        super().__init__(parent, bg=colors['bg_secondary'], **kwargs)

        self.colors = colors

        # Header bar: status dot + title + actions
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

        # Terminal body with hairline frame
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
            state=DISABLED
        )
        self.text.pack(fill=BOTH, expand=True)
        scrollbar.config(command=self.text.yview)

        self.text.tag_configure('info', foreground=colors['fg_info'])
        self.text.tag_configure('success', foreground=colors['fg_success'])
        self.text.tag_configure('error', foreground=colors['fg_error'])
        self.text.tag_configure('stdout', foreground=colors['output_fg'])

    def set_status(self, status_type: str):
        """Tint the header status dot to reflect the run state."""
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
        self.text.config(state=NORMAL)
        self.text.insert(END, text, tag)
        self.text.see(END)
        self.text.config(state=DISABLED)

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
        self._start_output_consumer()

    def _setup_window(self):
        self.root = Tk(className='ADBScriptLauncher')
        self.root.title(self.config.title)
        self.root.geometry(f"{self.config.width}x{self.config.height}")
        self.root.minsize(600, 400)
        self.root.configure(bg=self.colors['bg'])

        FontManager.init(self.root)

        try:
            icon_path = Path(__file__).parent / 'adb_script_icon_128.png'
            if icon_path.exists():
                from tkinter import PhotoImage
                icon = PhotoImage(file=str(icon_path))
                self.root.iconphoto(True, icon)
        except Exception:
            pass

        self.root.protocol("WM_DELETE_WINDOW", self._on_close)

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

        self.root.config(menu=menubar)

    def _create_layout(self):
        main = PanedWindow(self.root, orient='horizontal', bg=self.colors['border'])
        main.pack(fill=BOTH, expand=True)

        self.left_panel = Frame(main, bg=self.colors['bg'], width=320)
        main.add(self.left_panel, minsize=220)

        self.output_panel = OutputPanel(main, self.colors)
        main.add(self.output_panel, minsize=320)

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
            if group.id in groups:
                frame = GroupFrame(self.left_panel, group, self.colors)
                frame.pack(fill=X, padx=SP['sm'], pady=SP['sm'])
                self.group_frames[group.id] = frame

                for script in groups[group.id]:
                    btn = ScriptButton(frame.content, script, self.colors, self._run_script)
                    frame.add_script_button(btn)

        ungrouped = [s for s in self.config.scripts if s.group not in self.group_frames]
        if ungrouped:
            default_group = GroupConfig({'id': '_default', 'label': 'Scripts'})
            frame = GroupFrame(self.left_panel, default_group, self.colors)
            frame.pack(fill=X, padx=SP['sm'], pady=SP['sm'])

            for script in ungrouped:
                btn = ScriptButton(frame.content, script, self.colors, self._run_script)
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

        self.root.after(50, self._consume_output)

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
        self.runner.execute(script, self.base_dir, params)

    def _open_settings(self):
        SettingsDialog(self.root, self.config, self.colors, self.settings)

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

    def _show_about(self):
        messagebox.showinfo(
            "About",
            f"{self.config.title}\n\n"
            f"A configurable script launcher.\n\n"
            f"Config: {self.config_path}\n"
            f"Scripts: {len(self.config.scripts)}"
        )

    def _on_close(self):
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
