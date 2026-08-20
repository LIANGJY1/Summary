#!/usr/bin/env python3
"""WMS Viewer Module - Android Window Container Tree Viewer.

This module provides WMS (Window Manager Service) tree visualization functionality.
It can be used standalone or integrated into launcher_tool.
"""

import subprocess
import sys
import os
import re
import difflib
import tkinter as tk
from tkinter import ttk, messagebox, filedialog
from pathlib import Path
from typing import Optional, List


class WindowNode:
    """Represents a node in the window container hierarchy."""
    
    def __init__(self, raw_text: str, indent_level: int, depth: int = 0):
        self.raw_text = raw_text.rstrip()
        self.indent_level = indent_level
        self.depth = depth
        self.children: List['WindowNode'] = []
        self.tree_id = None
        
        self.short_name = self.raw_text.strip()
        self.node_category = "default"
        
        self._parse_node_info()
    
    def _parse_node_info(self):
        match = re.search(r'([A-Za-z0-9]+)\{([0-9a-f]+)\s+(.+?)\}', self.raw_text)
        if match:
            node_type = match.group(1)
            details = match.group(3)
            
            if node_type in ("WindowState", "Window"):
                self.short_name = f"Window{{{details}}}"
                self.node_category = "window"
            elif node_type == "ActivityRecord":
                self.short_name = f"Activity: {details.split()[-1] if details else ''}"
                self.node_category = "activity"
            elif node_type == "Task":
                task_id = re.search(r'taskId=(\d+)', details)
                if task_id:
                    self.short_name = f"Task: {task_id.group(1)}"
                else:
                    self.short_name = f"Task: {details.split()[0]}"
                self.node_category = "task"
            else:
                self.short_name = f"{node_type}: {details.split()[0] if details else ''}"
                if "Display" in node_type or node_type == "RootWindowContainer":
                    self.node_category = "display"
                if "Window:" in self.short_name:
                    self.short_name = f"Window{{{details}}}"
                    self.node_category = "window"
        else:
            parts = self.short_name.split()
            if parts and parts[0].startswith('#'):
                if len(parts) > 1:
                    name_match = re.search(r'name="([^"]+)"', self.raw_text)
                    if name_match:
                        self.short_name = f"{parts[0]} {parts[1]} ({name_match.group(1)})"
                    else:
                        self.short_name = " ".join(parts[:3])
                else:
                    self.short_name = parts[0]
                
                if "Display" in self.short_name or "Root" in self.short_name:
                    self.node_category = "display"
            elif self.short_name == self.raw_text.strip() and parts:
                self.short_name = parts[0]
                if self.short_name == "ROOT":
                    self.node_category = "root"


def parse_dumpsys(output_lines: List[str]) -> WindowNode:
    """Parse dumpsys output into a tree structure."""
    root = WindowNode("ROOT", -1, depth=-1)
    stack = [root]
    
    for line in output_lines:
        if not line.strip():
            continue
            
        indent_match = re.match(r'^( *)', line)
        indent_level = len(indent_match.group(1)) if indent_match else 0
        
        while stack and stack[-1].indent_level >= indent_level:
            stack.pop()
            
        parent = stack[-1]
        node = WindowNode(line, indent_level, depth=parent.depth + 1)
            
        parent.children.append(node)
        stack.append(node)
        
    return root


def get_adb_path() -> str:
    """Get the absolute path of the running adb server."""
    try:
        if sys.platform.startswith('linux'):
            result = subprocess.run(['pgrep', '-f', 'adb.*fork-server'], 
                                  capture_output=True, text=True)
            if result.stdout.strip():
                pids = result.stdout.strip().split()
                for pid in pids:
                    exe_path = os.path.realpath(f'/proc/{pid}/exe')
                    if os.path.exists(exe_path) and 'adb' in os.path.basename(exe_path):
                        return exe_path
    except Exception:
        pass
    return 'adb'


FONT_XS = 8
FONT_SM = 9
FONT_MD = 10
FONT_LG = 11

SP = {
    'xs': 4,
    'sm': 8,
    'md': 12,
    'lg': 16,
    'xl': 24,
}


class WMSTreePane:
    """A pane displaying a window container tree with details panel."""
    
    def __init__(self, parent: tk.Widget, title: str, colors: dict, font_manager):
        self.colors = colors
        self.font_manager = font_manager
        
        self.frame = tk.Frame(parent, bg=colors['bg_secondary'])
        
        header = tk.Frame(self.frame, bg=colors['bg_secondary'])
        header.pack(fill=tk.X, padx=SP['sm'], pady=(SP['sm'], 0))
        
        tk.Label(
            header,
            text=title,
            bg=colors['bg_secondary'],
            fg=colors['fg_header'],
            font=font_manager.ui(FONT_LG, bold=True)
        ).pack(side=tk.LEFT, padx=SP['sm'])
        
        tree_frame = tk.Frame(self.frame, bg=colors['bg_secondary'])
        tree_frame.pack(fill=tk.BOTH, expand=True, padx=SP['sm'], pady=SP['sm'])
        
        self.tree = ttk.Treeview(tree_frame)
        self._setup_tree_styles()
        self.tree.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        
        scrollbar = ttk.Scrollbar(tree_frame, orient="vertical", command=self.tree.yview)
        scrollbar.pack(side=tk.RIGHT, fill=tk.Y)
        self.tree.configure(yscrollcommand=scrollbar.set)
        
        self.tree.heading("#0", text="Window Container Hierarchy", anchor=tk.W)
        self.tree.bind("<<TreeviewSelect>>", self._on_select)
        
        details_frame = tk.Frame(self.frame, bg=colors['bg_secondary'])
        details_frame.pack(fill=tk.X, padx=SP['sm'], pady=(0, SP['sm']))
        
        tk.Label(
            details_frame,
            text="Container Details",
            bg=colors['bg_secondary'],
            fg=colors['fg_header'],
            font=font_manager.ui(FONT_SM, bold=True)
        ).pack(anchor=tk.W, padx=SP['xs'])
        
        self.details_text = tk.Text(
            details_frame,
            wrap=tk.WORD,
            height=6,
            bg=colors['bg_input'],
            fg=colors['fg'],
            font=font_manager.mono(FONT_SM),
            relief='flat',
            highlightthickness=1,
            highlightbackground=colors['border'],
            highlightcolor=colors['bg_button'],
            selectbackground=colors['selection_bg'],
            selectforeground=colors['selection_fg']
        )
        self.details_text.pack(fill=tk.X, padx=SP['xs'], pady=(0, SP['xs']))
        
        self.node_map = {}
        self.flat_nodes = []
    
    def _setup_tree_styles(self):
        style = ttk.Style()
        style.configure("WMS.Treeview", 
                       font=self.font_manager.ui(FONT_MD),
                       rowheight=32,
                       background=self.colors['bg_secondary'],
                       foreground=self.colors['fg'],
                       fieldbackground=self.colors['bg_secondary'])
        style.configure("WMS.Treeview.Heading",
                       font=self.font_manager.ui(FONT_SM, bold=True),
                       background=self.colors['bg_group'],
                       foreground=self.colors['fg_header'])
        self.tree.configure(style="WMS.Treeview")
        
        self.tree.tag_configure("level_0", foreground=self.colors['fg_header'], 
                               font=self.font_manager.ui(FONT_LG, bold=True))
        self.tree.tag_configure("level_1", foreground=self.colors['fg_info'], 
                               font=self.font_manager.ui(FONT_MD, bold=True))
        self.tree.tag_configure("level_2", foreground=self.colors['fg'], 
                               font=self.font_manager.ui(FONT_MD, bold=True))
        self.tree.tag_configure("level_3", foreground=self.colors['fg'], 
                               font=self.font_manager.ui(FONT_MD))
        self.tree.tag_configure("level_4", foreground=self.colors['fg_dim'], 
                               font=self.font_manager.ui(FONT_SM))
        self.tree.tag_configure("level_5", foreground=self.colors['fg_dim'], 
                               font=self.font_manager.ui(FONT_SM))
        
        self.tree.tag_configure("highlight", 
                               foreground=self.colors['fg_error'],
                               background=self.colors['bg_hover'])
        
        self.tree.tag_configure("diff_add", background=self.colors['fg_success'])
        self.tree.tag_configure("diff_remove", background=self.colors['fg_error'])
        self.tree.tag_configure("diff_modify", background=self.colors['fg_warning'])
    
    def clear(self):
        for item in self.tree.get_children():
            self.tree.delete(item)
        self.node_map.clear()
        self.flat_nodes.clear()
        self.details_text.config(state=tk.NORMAL)
        self.details_text.delete(1.0, tk.END)
        self.details_text.config(state=tk.DISABLED)
    
    def populate(self, root_node: WindowNode):
        self.clear()
        self._populate_recursive("", root_node)
    
    def _populate_recursive(self, parent_id: str, node: WindowNode):
        if node.indent_level != -1:
            self.flat_nodes.append(node)
            
            level_tag = f"level_{min(node.depth, 5)}"
            tags = [level_tag]
            
            if "#" not in node.short_name and node.short_name != "ROOT":
                tags = ["highlight"]
                
            item_id = self.tree.insert(parent_id, "end", text=node.short_name, 
                                      open=True, tags=tuple(tags))
            self.node_map[item_id] = node
            node.tree_id = item_id
        else:
            item_id = ""
            
        for child in node.children:
            self._populate_recursive(item_id, child)
    
    def _on_select(self, event):
        selected = self.tree.selection()
        if selected:
            node = self.node_map.get(selected[0])
            if node:
                self.details_text.config(state=tk.NORMAL)
                self.details_text.delete(1.0, tk.END)
                self.details_text.insert(tk.END, node.raw_text)
                self.details_text.config(state=tk.DISABLED)
    
    def expand_to_level(self, target_level: int, item: str = ""):
        children = self.tree.get_children(item)
        for child in children:
            node = self.node_map.get(child)
            if node:
                if node.depth < target_level:
                    self.tree.item(child, open=True)
                else:
                    self.tree.item(child, open=False)
            self.expand_to_level(target_level, child)
    
    def add_diff_tag(self, item_id: str, tag: str):
        tags = list(self.tree.item(item_id, "tags"))
        if tag not in tags:
            tags.append(tag)
            self.tree.item(item_id, tags=tags)
            
        node = self.node_map.get(item_id)
        if node:
            for child in node.children:
                if child.tree_id:
                    self.add_diff_tag(child.tree_id, tag)


class WMSViewerPanel:
    """Embeddable WMS Viewer panel for integration into other applications."""
    
    DEFAULT_COMMANDS = (
        "dumpsys window containers",
        "dumpsys activity containers",
        "dumpsys window windows",
        "dumpsys activity activities",
        "dumpsys window tokens",
        "dumpsys window displays",
        "dumpsys window policy",
        "dumpsys window sessions",
        "dumpsys window windows | awk '/Window #/{win=$0} /mHasSurface=true/{print win}'"
    )
    
    def __init__(self, parent: tk.Widget, colors: dict, font_manager):
        self.colors = colors
        self.font_manager = font_manager
        self.is_diff_mode = False
        
        self.frame = tk.Frame(parent, bg=colors['bg'])
        
        self._create_toolbar()
        self._create_main_panels()
        
        self.set_mode(False)
    
    def _bind_hover(self, widget, base_bg, hover_bg):
        widget.bind('<Enter>', lambda e: widget.configure(bg=hover_bg))
        widget.bind('<Leave>', lambda e: widget.configure(bg=base_bg))
    
    def _make_button(self, parent, text, command, kind='primary'):
        if kind == 'primary':
            base, hover, fg = self.colors['bg_button'], self.colors['bg_button_hover'], self.colors['fg_button']
            active = self.colors['bg_button_pressed']
        elif kind == 'secondary':
            base, hover, fg = self.colors['bg_group'], self.colors['bg_hover'], self.colors['fg']
            active = self.colors['bg_active']
        else:
            base, hover, fg = self.colors['bg_secondary'], self.colors['bg_hover'], self.colors['fg_dim']
            active = self.colors['bg_active']
        
        btn = tk.Button(
            parent,
            text=text,
            command=command,
            bg=base,
            fg=fg,
            activebackground=active,
            activeforeground=fg,
            relief='flat',
            padx=SP['md'],
            pady=SP['xs'],
            cursor='hand2',
            font=self.font_manager.ui(FONT_SM),
            highlightthickness=0
        )
        self._bind_hover(btn, base, hover)
        return btn
    
    def _create_toolbar(self):
        toolbar = tk.Frame(self.frame, bg=self.colors['bg_secondary'])
        toolbar.pack(fill=tk.X, padx=SP['sm'], pady=SP['sm'])
        
        cmd_frame = tk.Frame(toolbar, bg=self.colors['bg_secondary'])
        cmd_frame.pack(side=tk.LEFT, fill=tk.X, expand=True)
        
        tk.Label(
            cmd_frame,
            text="Command:",
            bg=self.colors['bg_secondary'],
            fg=self.colors['fg'],
            font=self.font_manager.ui(FONT_SM)
        ).pack(side=tk.LEFT, padx=(0, SP['sm']))
        
        self.cmd_var = tk.StringVar()
        self.cmd_combobox = ttk.Combobox(
            cmd_frame, 
            textvariable=self.cmd_var, 
            width=40,
            font=self.font_manager.ui(FONT_MD)
        )
        self.cmd_combobox['values'] = self.DEFAULT_COMMANDS
        self.cmd_combobox.current(0)
        self.cmd_combobox.pack(side=tk.LEFT, padx=(0, SP['md']))
        
        self.btn_load_left = self._make_button(
            cmd_frame, "Load", lambda: self.load_data(self.left_pane), 'primary'
        )
        self.btn_load_left.pack(side=tk.LEFT, padx=(0, SP['sm']))
        
        self.btn_load_right = self._make_button(
            cmd_frame, "Load Right", lambda: self.load_data(self.right_pane), 'secondary'
        )
        
        self.btn_compare = self._make_button(
            cmd_frame, "Compare", self.compare_trees, 'secondary'
        )
        
        self.btn_toggle_mode = self._make_button(
            cmd_frame, "Dual-Pane", self.toggle_mode, 'ghost'
        )
        self.btn_toggle_mode.pack(side=tk.RIGHT, padx=(SP['sm'], 0))
        
        self.btn_file = self._make_button(
            cmd_frame, "Load File", self.load_from_file, 'ghost'
        )
        self.btn_file.pack(side=tk.RIGHT, padx=(SP['sm'], 0))
        
        expand_frame = tk.Frame(toolbar, bg=self.colors['bg_secondary'])
        expand_frame.pack(side=tk.RIGHT, padx=(SP['md'], 0))
        
        self._make_button(
            expand_frame, "Expand All", lambda: self.expand_both(100), 'ghost'
        ).pack(side=tk.LEFT, padx=(0, SP['xs']))
        
        self._make_button(
            expand_frame, "Collapse", lambda: self.expand_both(0), 'ghost'
        ).pack(side=tk.LEFT, padx=(0, SP['xs']))
        
        for level in range(1, 4):
            self._make_button(
                expand_frame, f"L{level}", lambda l=level: self.expand_both(l), 'ghost'
            ).pack(side=tk.LEFT, padx=(0, SP['xs']))
    
    def _create_main_panels(self):
        self.main_paned = tk.PanedWindow(
            self.frame, 
            orient=tk.HORIZONTAL,
            bg=self.colors['border'],
            sashwidth=2
        )
        self.main_paned.pack(fill=tk.BOTH, expand=True, padx=SP['sm'], pady=(0, SP['sm']))
        
        self.left_pane = WMSTreePane(self.main_paned, "Window Tree", self.colors, self.font_manager)
        self.right_pane = WMSTreePane(self.main_paned, "Compare (Right)", self.colors, self.font_manager)
        
        self.main_paned.add(self.left_pane.frame, minsize=300)
        self.main_paned.add(self.right_pane.frame, minsize=300)
    
    def toggle_mode(self):
        self.set_mode(not self.is_diff_mode)
    
    def set_mode(self, diff_mode: bool):
        self.is_diff_mode = diff_mode
        
        if diff_mode:
            self.main_paned.add(self.right_pane.frame, minsize=300)
            self.btn_load_left.config(text="Load Left")
            self.btn_load_right.pack(side=tk.LEFT, padx=(0, SP['sm']))
            self.btn_compare.pack(side=tk.LEFT, padx=(0, SP['sm']))
            self.btn_toggle_mode.config(text="Single-Pane")
        else:
            try:
                self.main_paned.forget(self.right_pane.frame)
            except:
                pass
            self.btn_load_right.pack_forget()
            self.btn_compare.pack_forget()
            self.btn_load_left.config(text="Load")
            self.btn_toggle_mode.config(text="Dual-Pane")
    
    def load_data(self, pane: WMSTreePane):
        cmd_str = self.cmd_var.get()
        if not cmd_str:
            return
        
        adb_path = get_adb_path()
        try:
            print(f"Executing: {adb_path} shell {cmd_str}")
            
            if "|" in cmd_str or "'" in cmd_str:
                escaped_cmd_str = cmd_str.replace("'", "'\\''")
                full_cmd = f"{adb_path} shell '{escaped_cmd_str}'"
                result = subprocess.run(full_cmd, shell=True, capture_output=True, 
                                      text=True, check=True)
            else:
                cmd_args = [adb_path, 'shell'] + cmd_str.split()
                result = subprocess.run(cmd_args, capture_output=True, 
                                      text=True, check=True)
                
            lines = result.stdout.splitlines()
            root_node = parse_dumpsys(lines)
            pane.populate(root_node)
            
        except subprocess.CalledProcessError as e:
            messagebox.showerror("Error", f"Command failed:\n{e.stderr}")
        except Exception as e:
            messagebox.showerror("Error", str(e))
    
    def load_from_file(self):
        filepath = filedialog.askopenfilename(
            title="Select dumpsys output file",
            filetypes=[("Text files", "*.txt"), ("All files", "*.*")]
        )
        if filepath:
            try:
                with open(filepath, 'r', encoding='utf-8') as f:
                    lines = f.readlines()
                root_node = parse_dumpsys(lines)
                self.left_pane.populate(root_node)
            except Exception as e:
                messagebox.showerror("Error", f"Failed to load file: {e}")
    
    def expand_both(self, target_level: int):
        self.left_pane.expand_to_level(target_level)
        if self.is_diff_mode:
            self.right_pane.expand_to_level(target_level)
    
    def compare_trees(self):
        left_nodes = self.left_pane.flat_nodes
        right_nodes = self.right_pane.flat_nodes
        
        for n in left_nodes:
            tags = list(self.left_pane.tree.item(n.tree_id, "tags"))
            tags = [t for t in tags if not t.startswith("diff_")]
            self.left_pane.tree.item(n.tree_id, tags=tags)
            
        for n in right_nodes:
            tags = list(self.right_pane.tree.item(n.tree_id, "tags"))
            tags = [t for t in tags if not t.startswith("diff_")]
            self.right_pane.tree.item(n.tree_id, tags=tags)
            
        sm = difflib.SequenceMatcher(None, 
                                    [n.short_name for n in left_nodes],
                                    [n.short_name for n in right_nodes])
        has_diff = False
        first_diff_left_id = None
        first_diff_right_id = None
        
        for tag, i1, i2, j1, j2 in sm.get_opcodes():
            if tag == 'replace':
                has_diff = True
                for i in range(i1, i2):
                    self.left_pane.add_diff_tag(left_nodes[i].tree_id, "diff_modify")
                    if not first_diff_left_id:
                        first_diff_left_id = left_nodes[i].tree_id
                for j in range(j1, j2):
                    self.right_pane.add_diff_tag(right_nodes[j].tree_id, "diff_modify")
                    if not first_diff_right_id:
                        first_diff_right_id = right_nodes[j].tree_id
            elif tag == 'delete':
                has_diff = True
                for i in range(i1, i2):
                    self.left_pane.add_diff_tag(left_nodes[i].tree_id, "diff_remove")
                    if not first_diff_left_id:
                        first_diff_left_id = left_nodes[i].tree_id
            elif tag == 'insert':
                has_diff = True
                for j in range(j1, j2):
                    self.right_pane.add_diff_tag(right_nodes[j].tree_id, "diff_add")
                    if not first_diff_right_id:
                        first_diff_right_id = right_nodes[j].tree_id

        if not has_diff:
            messagebox.showinfo("Result", "No differences found!")
        else:
            if first_diff_left_id:
                self.left_pane.tree.see(first_diff_left_id)
                self.left_pane.tree.selection_set(first_diff_left_id)
                self.left_pane.tree.focus(first_diff_left_id)
            if first_diff_right_id:
                self.right_pane.tree.see(first_diff_right_id)
                self.right_pane.tree.selection_set(first_diff_right_id)
                self.right_pane.tree.focus(first_diff_right_id)


class WMSViewerWindow:
    """Standalone WMS Viewer window (for backward compatibility)."""
    
    def __init__(self, parent: Optional[tk.Tk] = None, colors: Optional[dict] = None):
        if parent:
            self.root = tk.Toplevel(parent)
        else:
            self.root = tk.Tk()
        
        self.root.title("WMS Viewer")
        self.root.geometry("1200x700")
        
        if colors is None:
            colors = {
                'bg': '#16161e',
                'bg_secondary': '#1a1b26',
                'bg_group': '#24283b',
                'bg_hover': '#292e42',
                'bg_active': '#3b4261',
                'bg_input': '#101014',
                'bg_button': '#7aa2f7',
                'bg_button_hover': '#89b4fb',
                'bg_button_pressed': '#5d7ed9',
                'fg': '#c0caf5',
                'fg_header': '#e0af68',
                'fg_dim': '#565f89',
                'fg_button': '#16161e',
                'fg_error': '#f7768e',
                'fg_success': '#9ece6a',
                'fg_warning': '#e0af68',
                'fg_info': '#7aa2f7',
                'border': '#2f3549',
                'selection_bg': '#33467c',
                'selection_fg': '#ffffff',
            }
        
        class SimpleFontManager:
            @staticmethod
            def ui(size=10, bold=False):
                return ("Segoe UI", size, "bold" if bold else "normal")
            @staticmethod
            def mono(size=10):
                return ("Consolas", size)
        
        self.panel = WMSViewerPanel(self.root, colors, SimpleFontManager)
        self.panel.frame.pack(fill=tk.BOTH, expand=True)
        
        self.panel.load_data(self.panel.left_pane)
    
    def run(self):
        self.root.mainloop()


def main():
    app = WMSViewerWindow()
    app.run()


if __name__ == "__main__":
    main()
