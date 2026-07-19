import subprocess
import sys
import os
import re
import difflib
import socket
import tempfile
import tkinter as tk
from tkinter import ttk
from tkinter import messagebox
import tkinter.font as tkfont

class WindowNode:
    def __init__(self, raw_text, indent_level, depth=0):
        self.raw_text = raw_text.rstrip()
        self.indent_level = indent_level
        self.depth = depth
        self.children = []
        self.tree_id = None
        
        self.short_name = self.raw_text.strip()
        self.node_category = "default"
        
        match = re.search(r'([A-Za-z0-9]+)\{([0-9a-f]+)\s+(.+?)\}', self.raw_text)
        if match:
            node_type = match.group(1)
            hash_code = match.group(2)
            details = match.group(3)
            
            # 为了防止 Task 和 Activity 的 hash 码不同导致被判断为 diff，我们把可变的 hash 去掉
            # 对于 Task，我们不仅要去掉 hash，还要去掉诸如 "type=standard" 之类的可变状态
            if node_type == "WindowState" or node_type == "Window":
                self.short_name = f"Window{{{details}}}"
                self.node_category = "window"
            elif node_type == "ActivityRecord":
                self.short_name = f"Activity: {details.split()[-1] if details else ''}"
                self.node_category = "activity"
            elif node_type == "Task":
                # 尝试提取明确的 taskId，作为唯一标识
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

def parse_dumpsys(output_lines):
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

def get_adb_path():
    """尝试获取当前正在运行的 adb server 的绝对路径，避免由于 PATH 环境不同导致低版本 adb 杀死高版本 server"""
    try:
        if sys.platform.startswith('linux'):
            result = subprocess.run(['pgrep', '-f', 'adb.*fork-server'], capture_output=True, text=True)
            if result.stdout.strip():
                pids = result.stdout.strip().split()
                for pid in pids:
                    exe_path = os.path.realpath(f'/proc/{pid}/exe')
                    if os.path.exists(exe_path) and 'adb' in os.path.basename(exe_path):
                        return exe_path
    except Exception:
        pass
    return 'adb'

class TreePane:
    def __init__(self, parent, title):
        self.frame = ttk.LabelFrame(parent, text=title)
        
        self.paned = ttk.PanedWindow(self.frame, orient=tk.VERTICAL)
        self.paned.pack(fill=tk.BOTH, expand=True, padx=2, pady=2)
        
        tree_frame = ttk.Frame(self.paned)
        self.paned.add(tree_frame, weight=6)
        
        self.tree = ttk.Treeview(tree_frame)
        self.setup_tags()
        self.tree.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        
        scrollbar = ttk.Scrollbar(tree_frame, orient="vertical", command=self.tree.yview)
        scrollbar.pack(side=tk.RIGHT, fill=tk.Y)
        self.tree.configure(yscrollcommand=scrollbar.set)
        
        self.tree.heading("#0", text="Window Container Hierarchy", anchor=tk.W)
        self.tree.bind("<<TreeviewSelect>>", self.on_select)
        
        details_frame = ttk.LabelFrame(self.paned, text="Container Details")
        self.paned.add(details_frame, weight=1)
        self.details_text = tk.Text(details_frame, wrap=tk.WORD, height=6)
        self.details_text.pack(fill=tk.BOTH, expand=True, padx=5, pady=5)
        
        self.node_map = {}
        self.flat_nodes = []

    def setup_tags(self):
        font_family = "Helvetica" if "Helvetica" in tkfont.families() else "Arial"
        
        root_font      = ("Segoe UI", 15, "bold")
        display_font   = ("Segoe UI", 14, "bold")
        tda_font       = ("Segoe UI", 13, "bold")
        task_font      = ("Segoe UI", 12)
        activity_font  = ("Segoe UI", 11)
        window_font    = ("Segoe UI", 10)

        # 恢复交替灰白背景
        self.tree.tag_configure("bg_0", background="#FFFFFF")
        self.tree.tag_configure("bg_1", background="#F5F5F5")
        self.tree.tag_configure("bg_2", background="#EEEEEE")

        self.tree.tag_configure("level_0", foreground="#0D47A1", font=root_font)
        self.tree.tag_configure("level_1", foreground="#1565C0", font=display_font)
        self.tree.tag_configure("level_2", foreground="#00838F", font=tda_font)
        self.tree.tag_configure("level_3", foreground="#2E7D32", font=task_font)
        self.tree.tag_configure("level_4", foreground="#37474F", font=activity_font)
        self.tree.tag_configure("level_5", foreground="#546E7A", font=window_font)
        self.tree.tag_configure("level_6", foreground="#78909C", font=window_font)

        # 恢复高亮样式 highlight_no_hash
        highlight_font = ("Segoe UI", 10, "bold")
        self.tree.tag_configure(
            "highlight_no_hash", 
            foreground="#D50000",
            background="#FFFDE7",
            font=highlight_font
        )
        
        # Diff tags - configured LAST so they override background colors if needed
        # 统一使用红色背景高亮所有的差异
        self.tree.tag_configure("diff_add", background="#FFCDD2") # Light Red
        self.tree.tag_configure("diff_remove", background="#FFCDD2") # Light Red
        self.tree.tag_configure("diff_modify", background="#FFCDD2") # Light Red

    def clear(self):
        for item in self.tree.get_children():
            self.tree.delete(item)
        self.node_map.clear()
        self.flat_nodes.clear()
        self.details_text.delete(1.0, tk.END)

    def populate(self, root_node):
        self.clear()
        self._populate("", root_node)

    def _populate(self, parent_id, node):
        if node.indent_level != -1:
            self.flat_nodes.append(node)
            
            bg_tag = f"bg_{node.depth % 3}"
            level_tag = f"level_{min(node.depth, 6)}"
            if node.depth == -1:
                level_tag = "level_0"
                
            tags = [bg_tag, level_tag]
            if "#" not in node.short_name and node.short_name != "ROOT":
                tags = ("highlight_no_hash",)
                
            item_id = self.tree.insert(parent_id, "end", text=node.short_name, open=True, tags=tuple(tags))
            self.node_map[item_id] = node
            node.tree_id = item_id
        else:
            item_id = ""
            
        for child in node.children:
            self._populate(item_id, child)

    def on_select(self, event):
        selected = self.tree.selection()
        if selected:
            node = self.node_map.get(selected[0])
            if node:
                self.details_text.delete(1.0, tk.END)
                self.details_text.insert(tk.END, node.raw_text)

    def expand_to_level(self, target_level, item=""):
        children = self.tree.get_children(item)
        for child in children:
            node = self.node_map.get(child)
            if node:
                if node.depth < target_level: 
                    self.tree.item(child, open=True)
                else:
                    self.tree.item(child, open=False)
            self.expand_to_level(target_level, child)

class WindowTreeApp:
    def __init__(self, root):
        self.root = root
        self.root.title("Android Window Container Tree Viewer")
        self.root.geometry("1400x800")
        
        # 尝试设置应用运行时图标 (在侧边栏和任务栏中显示)
        # 注意：由于系统安全限制无法在目标目录写入 PNG，我们回退尝试读取原始位置的 jpg
        # 如果系统安装了 Pillow，它也可以加载 jpg。
        try:
            icon_path = "/home/liang/Project/MyProject/Summary/project/WMS Viewer/wms_viewer_icon.jpg"
            try:
                # 优先尝试使用 PIL 读取 JPG，这样一定能成功显示
                from PIL import ImageTk, Image
                img = ImageTk.PhotoImage(Image.open(icon_path))
                self.root.iconphoto(True, img)
            except ImportError:
                # 如果没有安装 Pillow 库，退回到 tkinter 默认行为
                img = tk.PhotoImage(file=icon_path)
                self.root.iconphoto(True, img)
        except Exception as e:
            pass # 忽略错误，避免因格式不支持导致程序崩溃
        
        style = ttk.Style()
        default_font = ("TkDefaultFont", 12)
        style.configure("Treeview", font=default_font, rowheight=45)
        
        # Toolbar
        toolbar = ttk.Frame(self.root)
        toolbar.pack(side=tk.TOP, fill=tk.X, padx=5, pady=8)
        
        # 统一设置 Toolbar 中按钮和标签的字体大小
        style.configure("TButton", font=("Segoe UI", 11))
        style.configure("TLabel", font=("Segoe UI", 12))
        
        cmd_frame = ttk.Frame(toolbar)
        cmd_frame.pack(side=tk.LEFT, padx=(0, 20))
        ttk.Label(cmd_frame, text="执行命令: ").pack(side=tk.LEFT)
        self.cmd_var = tk.StringVar()
        # 将下拉框宽度增大，并修改字体以放大显示
        self.cmd_combobox = ttk.Combobox(cmd_frame, textvariable=self.cmd_var, width=45, font=("Segoe UI", 12))
        self.cmd_combobox['values'] = (
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
        self.cmd_combobox.current(0)
        self.cmd_combobox.pack(side=tk.LEFT, padx=5)
        
        self.btn_load_left = ttk.Button(cmd_frame, text="加载到左侧", command=lambda: self.load_data(self.left_pane))
        self.btn_load_left.pack(side=tk.LEFT, padx=2)
        
        self.btn_load_right = ttk.Button(cmd_frame, text="加载到右侧", command=lambda: self.load_data(self.right_pane))
        self.btn_load_right.pack(side=tk.LEFT, padx=2)
        
        self.btn_compare = ttk.Button(cmd_frame, text="对比差异", command=self.compare_trees)
        self.btn_compare.pack(side=tk.LEFT, padx=10)
        
        ttk.Separator(toolbar, orient=tk.VERTICAL).pack(side=tk.LEFT, padx=5, fill=tk.Y)
        ttk.Button(toolbar, text="展开全部", command=lambda: self.expand_both(100)).pack(side=tk.LEFT, padx=2)
        ttk.Button(toolbar, text="折叠全部", command=lambda: self.expand_both(0)).pack(side=tk.LEFT, padx=2)
        ttk.Separator(toolbar, orient=tk.VERTICAL).pack(side=tk.LEFT, padx=5, fill=tk.Y)
        ttk.Button(toolbar, text="展开 1 级", command=lambda: self.expand_both(1)).pack(side=tk.LEFT, padx=2)
        ttk.Button(toolbar, text="展开 2 级", command=lambda: self.expand_both(2)).pack(side=tk.LEFT, padx=2)
        ttk.Button(toolbar, text="展开 3 级", command=lambda: self.expand_both(3)).pack(side=tk.LEFT, padx=2)
        ttk.Button(toolbar, text="展开 4 级", command=lambda: self.expand_both(4)).pack(side=tk.LEFT, padx=2)
        
        self.btn_toggle_mode = ttk.Button(toolbar, text="切换至双屏比较模式", command=self.toggle_mode)
        self.btn_toggle_mode.pack(side=tk.RIGHT, padx=10)
        
        # Main Splitter
        self.main_paned = ttk.PanedWindow(self.root, orient=tk.HORIZONTAL)
        self.main_paned.pack(fill=tk.BOTH, expand=True, padx=5, pady=5)
        
        self.left_pane = TreePane(self.main_paned, "左侧 (旧版本/基准)")
        self.right_pane = TreePane(self.main_paned, "右侧 (新版本/当前)")
        
        self.main_paned.add(self.left_pane.frame, weight=1)
        self.main_paned.add(self.right_pane.frame, weight=1)
        
        self.is_diff_mode = True
        self.set_mode(False) # 默认启动为单屏正常模式

    def toggle_mode(self):
        self.set_mode(not self.is_diff_mode)
        
    def set_mode(self, diff_mode):
        self.is_diff_mode = diff_mode
        if diff_mode:
            self.main_paned.add(self.right_pane.frame, weight=1)
            self.btn_load_left.config(text="加载到左侧")
            self.btn_load_right.pack(side=tk.LEFT, padx=2)
            self.btn_compare.pack(side=tk.LEFT, padx=10)
            self.btn_toggle_mode.config(text="切换至正常全屏模式")
            self.left_pane.frame.config(text="左侧 (旧版本/基准)")
        else:
            self.main_paned.forget(self.right_pane.frame)
            self.btn_load_right.pack_forget()
            self.btn_compare.pack_forget()
            self.btn_load_left.config(text="刷新数据")
            self.btn_toggle_mode.config(text="切换至双屏比较模式")
            self.left_pane.frame.config(text="Window Container Hierarchy")

    def load_data(self, pane):
        cmd_str = self.cmd_var.get()
        if not cmd_str:
            return
        
        adb_path = get_adb_path()
        try:
            print(f"Executing for {pane.frame.cget('text')}: {adb_path} shell {cmd_str}")
            # 处理像 awk 这样带有管道和单引号的复杂命令
            if "|" in cmd_str or "'" in cmd_str:
                # 使用单引号包裹，并转义内部单引号，防止本地 shell 提前解析 $0 等变量
                escaped_cmd_str = cmd_str.replace("'", "'\\''")
                full_cmd = f"{adb_path} shell '{escaped_cmd_str}'"
                result = subprocess.run(full_cmd, shell=True, capture_output=True, text=True, check=True)
            else:
                cmd_args = [adb_path, 'shell'] + cmd_str.split()
                result = subprocess.run(cmd_args, capture_output=True, text=True, check=True)
                
            lines = result.stdout.splitlines()
            root_node = parse_dumpsys(lines)
            pane.populate(root_node)
        except subprocess.CalledProcessError as e:
            pane.details_text.delete(1.0, tk.END)
            error_msg = f"Error executing command: {e}\n\nSTDERR:\n{e.stderr}\n\nSTDOUT:\n{e.stdout}"
            pane.details_text.insert(tk.END, error_msg)
            print(error_msg)
        except Exception as e:
            pane.details_text.delete(1.0, tk.END)
            pane.details_text.insert(tk.END, f"Error: {e}")
            
    def expand_both(self, target_level):
        self.left_pane.expand_to_level(target_level)
        self.right_pane.expand_to_level(target_level)
        
    def compare_trees(self):
        left_nodes = self.left_pane.flat_nodes
        right_nodes = self.right_pane.flat_nodes
        
        # Reset diff tags
        for n in left_nodes:
            tags = list(self.left_pane.tree.item(n.tree_id, "tags"))
            tags = [t for t in tags if not t.startswith("diff_")]
            self.left_pane.tree.item(n.tree_id, tags=tags)
            
        for n in right_nodes:
            tags = list(self.right_pane.tree.item(n.tree_id, "tags"))
            tags = [t for t in tags if not t.startswith("diff_")]
            self.right_pane.tree.item(n.tree_id, tags=tags)
            
        sm = difflib.SequenceMatcher(None, [n.short_name for n in left_nodes], [n.short_name for n in right_nodes])
        has_diff = False
        first_diff_left_id = None
        first_diff_right_id = None
        
        for tag, i1, i2, j1, j2 in sm.get_opcodes():
            if tag == 'replace':
                has_diff = True
                for i in range(i1, i2):
                    # 只有当它是当前 diff 块的最顶层节点时才去高亮，
                    # 避免对子节点重复调用导致死循环或性能问题
                    self.add_tag(self.left_pane, left_nodes[i].tree_id, "diff_modify")
                    if not first_diff_left_id: first_diff_left_id = left_nodes[i].tree_id
                for j in range(j1, j2):
                    self.add_tag(self.right_pane, right_nodes[j].tree_id, "diff_modify")
                    if not first_diff_right_id: first_diff_right_id = right_nodes[j].tree_id
            elif tag == 'delete':
                has_diff = True
                for i in range(i1, i2):
                    self.add_tag(self.left_pane, left_nodes[i].tree_id, "diff_remove")
                    if not first_diff_left_id: first_diff_left_id = left_nodes[i].tree_id
            elif tag == 'insert':
                has_diff = True
                for j in range(j1, j2):
                    self.add_tag(self.right_pane, right_nodes[j].tree_id, "diff_add")
                    if not first_diff_right_id: first_diff_right_id = right_nodes[j].tree_id

        if not has_diff:
            messagebox.showinfo("对比结果", "两边内容完全一致，没有差异！")
        else:
            # 自动跳转到第一个差异点
            if first_diff_left_id:
                self.left_pane.tree.see(first_diff_left_id)
                self.left_pane.tree.selection_set(first_diff_left_id)
                self.left_pane.tree.focus(first_diff_left_id)
            if first_diff_right_id:
                self.right_pane.tree.see(first_diff_right_id)
                self.right_pane.tree.selection_set(first_diff_right_id)
                self.right_pane.tree.focus(first_diff_right_id)

    def add_tag(self, pane, item_id, tag):
        tags = list(pane.tree.item(item_id, "tags"))
        if tag not in tags:
            tags.append(tag)
            pane.tree.item(item_id, tags=tags)
            
        # 递归高亮所有的子节点
        node = pane.node_map.get(item_id)
        if node:
            for child in node.children:
                if child.tree_id:
                    self.add_tag(pane, child.tree_id, tag)

def check_single_instance_and_bring_to_front():
    """
    检查是否已有实例在运行。
    如果是，则通过 Socket 通知旧实例拉起窗口，并退出当前进程。
    如果不，则启动一个后台线程监听拉起请求。
    """
    lock_port = 54321  # 选取一个本地未使用的固定端口作为单例锁

    try:
        # 尝试绑定端口
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.bind(('127.0.0.1', lock_port))
        s.listen(1)
        
        # 绑定成功，说明是第一个实例
        # 启动一个后台线程来监听其他实例发来的请求
        import threading
        def listen_for_wakeup(server_socket):
            while True:
                try:
                    conn, addr = server_socket.accept()
                    data = conn.recv(1024)
                    if data == b"WAKEUP":
                        # 收到拉起请求，将主窗口置顶
                        if 'app' in globals() and app.root:
                            app.root.after(0, bring_window_to_front)
                    conn.close()
                except:
                    break
                    
        t = threading.Thread(target=listen_for_wakeup, args=(s,), daemon=True)
        t.start()
        
        # 保存 socket 防止被垃圾回收
        global _single_instance_socket
        _single_instance_socket = s
        return True
        
    except socket.error:
        # 绑定失败，说明已经有实例在运行了
        try:
            # 连接已有实例，发送 WAKEUP 信号
            client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            client.connect(('127.0.0.1', lock_port))
            client.sendall(b"WAKEUP")
            client.close()
        except:
            pass
        return False

def bring_window_to_front():
    """将已存在的 Tkinter 窗口强制置顶显示"""
    if 'app' in globals() and app.root:
        # 恢复窗口（如果被最小化）
        app.root.deiconify()
        # 强制置顶并获取焦点
        app.root.attributes('-topmost', True)
        app.root.attributes('-topmost', False)
        app.root.focus_force()

def main():
    if not check_single_instance_and_bring_to_front():
        print("WMS Viewer is already running. Bringing it to front...")
        sys.exit(0)
        
    root = tk.Tk()
    global app
    app = WindowTreeApp(root)
    
    # Auto-load left if file provided, else auto-load left from adb
    if len(sys.argv) > 1 and sys.argv[1].endswith('.txt'):
        print(f"Reading from file: {sys.argv[1]}")
        with open(sys.argv[1], 'r', encoding='utf-8') as f:
            lines = f.readlines()
        root_node = parse_dumpsys(lines)
        app.left_pane.populate(root_node)
    else:
        app.load_data(app.left_pane)
    root.mainloop()

if __name__ == "__main__":
    main()
