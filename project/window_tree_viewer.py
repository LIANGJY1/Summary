import subprocess
import sys
import re
import tkinter as tk
from tkinter import ttk
import tkinter.font as tkfont

class WindowNode:
    def __init__(self, raw_text, indent_level, depth=0):
        self.raw_text = raw_text.strip()
        self.indent_level = indent_level
        self.depth = depth # 记录节点在树中的实际层级深度
        self.children = []
        
        # 尝试提取一些关键信息以作简短显示
        self.short_name = self.raw_text
        self.node_category = "default" # 用于后续 UI 渲染打标签
        
        # 1. 尝试提取形如 Type{hash details} 的常见对象 (Task, ActivityRecord, WindowState 等)
        # 注意 Window 在 dumpsys 中通常直接叫 Window{...}，而不是 WindowState{...}
        match = re.search(r'([A-Za-z0-9]+)\{([0-9a-f]+)\s+(.+?)\}', self.raw_text)
        if match:
            node_type = match.group(1)
            hash_code = match.group(2)
            details = match.group(3)
            if node_type == "WindowState" or node_type == "Window":
                # Window 完整显示大括号内的所有核心信息
                self.short_name = f"Window{{{hash_code} {details}}}"
                self.node_category = "window"
            elif node_type == "ActivityRecord":
                self.short_name = f"Activity: {details.split()[-1] if details else ''}"
                self.node_category = "activity"
            elif node_type == "Task":
                task_id = re.search(r'taskId=(\d+)', details)
                t_id = task_id.group(1) if task_id else "?"
                self.short_name = f"Task: {t_id}"
                self.node_category = "task"
            else:
                self.short_name = f"{node_type}: {details.split()[0] if details else ''}"
                if "Display" in node_type or node_type == "RootWindowContainer":
                    self.node_category = "display"
                
                # 防御性逻辑：如果 short_name 里包含了 "Window:" (例如前面逻辑漏掉的特殊格式)，强制纠正
                if "Window:" in self.short_name:
                    self.short_name = f"Window{{{hash_code} {details}}}"
                    self.node_category = "window"
        else:
            # 2. 处理类似 "#0 Display 0 name=\"Built-in Screen\" type=undefined ..." 这样的普通行
            # 或者 "#2 DefaultTaskDisplayArea ..."
            parts = self.raw_text.split()
            if parts and parts[0].startswith('#'):
                # 如果这行有超过 2 个单词，提取紧跟在 #编号 之后的核心描述词
                if len(parts) > 1:
                    # 例如 "#0 Display 0 name=...", 取 "#0 Display" 或尝试提取 name
                    name_match = re.search(r'name="([^"]+)"', self.raw_text)
                    if name_match:
                        self.short_name = f"{parts[0]} {parts[1]} ({name_match.group(1)})"
                    else:
                        # 截取前面的 2 到 3 个词作为核心描述
                        self.short_name = " ".join(parts[:3])
                else:
                    self.short_name = parts[0]
                
                if "Display" in self.short_name or "Root" in self.short_name:
                    self.node_category = "display"
            elif self.short_name == self.raw_text and parts:
                self.short_name = parts[0]
                if self.short_name == "ROOT":
                    self.node_category = "root"

def parse_dumpsys(output_lines):
    root = WindowNode("ROOT", -1, depth=-1)
    stack = [root]
    
    for line in output_lines:
        if not line.strip():
            continue
            
        # 计算缩进
        indent_match = re.match(r'^( *)', line)
        indent_level = len(indent_match.group(1)) if indent_match else 0
        
        # 找到正确的父节点
        while stack and stack[-1].indent_level >= indent_level:
            stack.pop()
            
        parent = stack[-1]
        node = WindowNode(line, indent_level, depth=parent.depth + 1)
            
        parent.children.append(node)
        stack.append(node)
        
    return root

class WindowTreeApp:
    def __init__(self, root, window_tree_root):
        self.root = root
        self.root.title("Android Window Container Tree Viewer")
        self.root.geometry("1000x800")
        
        # 顶部工具栏
        toolbar = ttk.Frame(self.root)
        toolbar.pack(side=tk.TOP, fill=tk.X, padx=5, pady=5)
        
        # 命令选择下拉框和执行按钮
        cmd_frame = ttk.Frame(toolbar)
        cmd_frame.pack(side=tk.LEFT, padx=(0, 20))
        
        ttk.Label(cmd_frame, text="执行命令: ").pack(side=tk.LEFT)
        
        self.cmd_var = tk.StringVar()
        self.cmd_combobox = ttk.Combobox(cmd_frame, textvariable=self.cmd_var, width=35)
        self.cmd_combobox['values'] = (
            "dumpsys window containers",
            "dumpsys activity containers"
        )
        self.cmd_combobox.current(0)
        self.cmd_combobox.pack(side=tk.LEFT, padx=5)
        
        ttk.Button(cmd_frame, text="刷新", command=self.refresh_data).pack(side=tk.LEFT)
        
        ttk.Separator(toolbar, orient=tk.VERTICAL).pack(side=tk.LEFT, padx=5, fill=tk.Y)
        
        ttk.Button(toolbar, text="展开全部", command=lambda: self.expand_to_level(100)).pack(side=tk.LEFT, padx=2)
        ttk.Button(toolbar, text="折叠全部", command=lambda: self.expand_to_level(0)).pack(side=tk.LEFT, padx=2)
        ttk.Separator(toolbar, orient=tk.VERTICAL).pack(side=tk.LEFT, padx=5, fill=tk.Y)
        ttk.Button(toolbar, text="展开 1 级", command=lambda: self.expand_to_level(1)).pack(side=tk.LEFT, padx=2)
        ttk.Button(toolbar, text="展开 2 级", command=lambda: self.expand_to_level(2)).pack(side=tk.LEFT, padx=2)
        ttk.Button(toolbar, text="展开 3 级", command=lambda: self.expand_to_level(3)).pack(side=tk.LEFT, padx=2)
        ttk.Button(toolbar, text="展开 4 级", command=lambda: self.expand_to_level(4)).pack(side=tk.LEFT, padx=2)
        
        # 上下分割：上面是树，下面是详细信息
        self.paned_window = ttk.PanedWindow(self.root, orient=tk.VERTICAL)
        self.paned_window.pack(fill=tk.BOTH, expand=True, padx=5, pady=5)
        
        # 树视图
        tree_frame = ttk.Frame(self.paned_window)
        
        # 将权重比例调整为上面（树）占大部分，下面（详情）占小部分，
        # 并通过 sash 调整间距。注意 ttk.PanedWindow 没有 sashwidth，
        # 但我们可以通过 weight 来调整初始布局，让下半部分往下靠
        self.paned_window.add(tree_frame, weight=6)
        
        # 定义样式来增加行高，解决文本重叠问题
        style = ttk.Style()
        
        # 统一字体配置，使用现代且易读的无衬线字体，字号适中
        font_family = "Helvetica" if "Helvetica" in tkfont.families() else "Arial"
        
        # 不同的系统/主题下，Treeview 的字体大小计算可能有 bug，
        # 我们显式地设置一个默认字体并根据该字体设置合理的行高。
        default_font = ("TkDefaultFont", 12)
        style.configure("Treeview", font=default_font, rowheight=45)
        
        # 定义不同层级的背景颜色和字体颜色，重要层级节点加粗
        self.tree = ttk.Treeview(tree_frame)
        
        # # 按照层级分配逐渐变小的字号，并且前几层加粗
        # level_0_font = ("TkDefaultFont", 16, "bold")
        # level_1_font = ("TkDefaultFont", 15, "bold")
        # level_2_font = ("TkDefaultFont", 14, "bold")
        # level_3_font = ("TkDefaultFont", 13)
        # level_4_font = ("TkDefaultFont", 12)
        # normal_font = ("TkDefaultFont", 11)
        
        # # 使用交替的极浅背景色
        # self.tree.tag_configure("depth_0", background="#FFFFFF")
        # self.tree.tag_configure("depth_1", background="#FFFFFF")
        # self.tree.tag_configure("depth_2", background="#FFFFFF")
        
        # # 将字体大小、加粗与前景色绑定到具体的深度 Level
        # # ROOT (Level -1 映射为 0) -> 最大字号，深蓝
        # self.tree.tag_configure("level_0", foreground="#0D47A1", font=level_0_font) 
        
        # # DisplayContent 等 (Level 0 映射为 1) -> 较大字号，深绿
        # self.tree.tag_configure("level_1", foreground="#1B5E20", font=level_1_font) 
        
        # # TaskDisplayArea 等 (Level 1 映射为 2) -> 中大字号，深橙
        # self.tree.tag_configure("level_2", foreground="#E65100", font=level_2_font) 
        
        # # Task 等 (Level 2 映射为 3) -> 中字号，深紫，常规
        # self.tree.tag_configure("level_3", foreground="#4A148C", font=level_3_font) 
        
        # # Activity 等 (Level 3 映射为 4) -> 正常字号，黑色，常规
        # self.tree.tag_configure("level_4", foreground="#212121", font=level_4_font) 
        
        # # Window 等 (Level 4+ 映射为 5,6) -> 较小字号，灰色，常规
        # self.tree.tag_configure("level_5", foreground="#424242", font=normal_font) 
        # self.tree.tag_configure("level_6", foreground="#616161", font=normal_font) 
        
        # # 对于没有带有 # 字符的重要节点（例如 Task, WindowToken 等纯结构节点）单独定义一个高亮标签
        # # 保持和普通字号一样小 (11号)，但加粗并改变颜色以达到显眼效果
        # highlight_font = ("TkDefaultFont", 11, "bold")
        # self.tree.tag_configure("highlight_no_hash", foreground="#D50000", font=highlight_font, background="#FFF9C4") # 深红色字体 + 浅黄色高亮背景
        
        # Font
        root_font      = ("Segoe UI", 15, "bold")
        display_font   = ("Segoe UI", 14, "bold")
        tda_font       = ("Segoe UI", 13, "bold")
        task_font      = ("Segoe UI", 12)
        activity_font  = ("Segoe UI", 11)
        window_font    = ("Segoe UI", 10)

        # Zebra Background
        self.tree.tag_configure("depth_even", background="#FFFFFF")
        self.tree.tag_configure("depth_odd", background="#FAFBFC")

        # RootDisplayArea / RootWindowContainer
        self.tree.tag_configure(
            "level_0",
            foreground="#0D47A1",
            font=root_font
        )

        # DisplayContent
        self.tree.tag_configure(
            "level_1",
            foreground="#1565C0",
            font=display_font
        )

        # DisplayArea / TaskDisplayArea
        self.tree.tag_configure(
            "level_2",
            foreground="#00838F",
            font=tda_font
        )

        # Task / TaskFragment
        self.tree.tag_configure(
            "level_3",
            foreground="#2E7D32",
            font=task_font
        )

        # ActivityRecord
        self.tree.tag_configure(
            "level_4",
            foreground="#37474F",
            font=activity_font
        )

        # WindowToken
        self.tree.tag_configure(
            "level_5",
            foreground="#546E7A",
            font=window_font
        )

        # WindowState
        self.tree.tag_configure(
            "level_6",
            foreground="#78909C",
            font=window_font
        )

        # 这里就是定义 highlight_no_hash 字体样式的地方！
        # 比如我们让它变成 13号、加粗、深红色、带浅黄色背景
        highlight_font = ("Segoe UI", 10, "bold")
        self.tree.tag_configure(
            "highlight_no_hash", 
            foreground="#D50000",      # 字体颜色（深红）
            background="#FFFDE7",      # 背景颜色（浅黄）
            font=highlight_font        # 字体样式
        )

        self.tree.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        
        scrollbar = ttk.Scrollbar(tree_frame, orient="vertical", command=self.tree.yview)
        scrollbar.pack(side=tk.RIGHT, fill=tk.Y)
        self.tree.configure(yscrollcommand=scrollbar.set)
        
        self.tree.heading("#0", text="Window Container Hierarchy", anchor=tk.W)
        self.tree.bind("<<TreeviewSelect>>", self.on_tree_select)
        
        # 详情文本框
        details_frame = ttk.LabelFrame(self.paned_window, text="Container Details")
        self.paned_window.add(details_frame, weight=1)
        
        self.details_text = tk.Text(details_frame, wrap=tk.WORD, height=6)
        self.details_text.pack(fill=tk.BOTH, expand=True, padx=5, pady=5)
        
        # 节点映射字典
        self.node_map = {}
        
        # 填充树
        self.populate_tree("", window_tree_root)
        
    def populate_tree(self, parent_id, node):
        if node.indent_level == -1: # ROOT node
            for child in node.children:
                self.populate_tree("", child)
            return
            
        # 使用基于 depth 的颜色和字体
        # 1. 深度取模来应用背景色
        bg_tag = f"depth_{node.depth % 3}"
        
        # 2. 移除 type_tag（因为截图里显然没有生效），改为直接根据深度（level）应用字体和文字颜色
        # 我们把 0~6 层映射到对应的 level_X 标签
        level_tag = f"level_{min(node.depth, 6)}"
        
        # 如果是根节点，特殊处理
        if node.depth == -1:
            level_tag = "level_0"
            
        tags = (bg_tag, level_tag)
        
        # 检查是否包含 '#' 字符，如果不包含（比如纯 "WindowToken: type=2024"），应用高亮标签覆盖原有颜色
        if "#" not in node.short_name and node.short_name != "ROOT":
            tags = ("highlight_no_hash",)
            
        item_id = self.tree.insert(parent_id, "end", text=node.short_name, open=True, tags=tags)
        self.node_map[item_id] = node
        
        for child in node.children:
            self.populate_tree(item_id, child)
            
    def expand_to_level(self, target_level, item=""):
        """递归设置树节点的展开/折叠状态"""
        children = self.tree.get_children(item)
        for child in children:
            node = self.node_map.get(child)
            if node:
                # 根节点的 depth 为 -1，顶级节点 depth 为 0
                # 如果当前节点的深度小于目标层级，则展开它（使其子节点可见）
                if node.depth < target_level: 
                    self.tree.item(child, open=True)
                else:
                    self.tree.item(child, open=False)
            
            # 递归处理子节点
            self.expand_to_level(target_level, child)
            
    def on_tree_select(self, event):
        selected_items = self.tree.selection()
        if not selected_items:
            return
            
        item_id = selected_items[0]
        node = self.node_map.get(item_id)
        
        if node:
            self.details_text.delete(1.0, tk.END)
            self.details_text.insert(tk.END, node.raw_text)

    def refresh_data(self):
        """执行选择的命令并刷新树"""
        cmd_str = self.cmd_var.get()
        if not cmd_str:
            return
            
        # 清空当前树
        for item in self.tree.get_children():
            self.tree.delete(item)
        self.node_map.clear()
        self.details_text.delete(1.0, tk.END)
        
        # 执行新命令并重绘
        try:
            print(f"Executing: adb shell {cmd_str}")
            cmd_args = ['adb', 'shell'] + cmd_str.split()
            result = subprocess.run(cmd_args, capture_output=True, text=True, check=True)
            lines = result.stdout.splitlines()
            root_node = parse_dumpsys(lines)
            self.populate_tree("", root_node)
        except Exception as e:
            self.details_text.insert(tk.END, f"Error executing command: {e}")

def get_dumpsys_output():
    try:
        print("Fetching dumpsys window containers...")
        result = subprocess.run(['adb', 'shell', 'dumpsys', 'window', 'containers'], 
                              capture_output=True, text=True, check=True)
        return result.stdout.splitlines()
    except subprocess.CalledProcessError as e:
        print(f"Error executing adb command: {e}")
        print("Please make sure an emulator or device is connected.")
        sys.exit(1)
    except FileNotFoundError:
        print("adb command not found. Please ensure it's in your PATH.")
        sys.exit(1)

def main():
    if len(sys.argv) > 1 and sys.argv[1].endswith('.txt'):
        print(f"Reading from file: {sys.argv[1]}")
        with open(sys.argv[1], 'r', encoding='utf-8') as f:
            lines = f.readlines()
    else:
        lines = get_dumpsys_output()
        
    root_node = parse_dumpsys(lines)
    
    root = tk.Tk()
    app = WindowTreeApp(root, root_node)
    root.mainloop()

if __name__ == "__main__":
    main()
