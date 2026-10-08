#!/usr/bin/env python3
"""
Pixiv 爬虫 GUI — tkinter 界面
用法: python pixiv_gui.py
"""

import json
import os
import queue
import sys
import threading
import time
import tkinter as tk
from tkinter import ttk, messagebox, filedialog

# 把当前目录加入路径，确保能导入 pixiv_scraper
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pixiv_scraper as scraper

# ============================================================
# 标签联想下拉框（类似搜索引擎自动补全）
# ============================================================

class TagSuggestBox:
    """附着在 Entry 上的联想下拉框。

    - 输入停顿 ~0.3s 后查询候选（后台线程，不卡界面）
    - 鼠标点击 / 回车选择后填回输入框（填的是日文 tag_name）
    - 支持 ↓ 进入列表、↑/↓ 选择、Esc 收起
    """

    MAX_VISIBLE = 8

    def __init__(self, root, entry, fetch, debounce_ms=280, min_chars=1):
        self.root = root
        self.entry = entry
        self.fetch = fetch              # fetch(keyword) -> list[dict]
        self.debounce_ms = debounce_ms
        self.min_chars = min_chars

        self.popup = tk.Toplevel(root)
        self.popup.withdraw()
        self.popup.overrideredirect(True)
        try:
            self.popup.attributes("-topmost", True)
        except tk.TclError:
            pass
        self.listbox = tk.Listbox(self.popup, activestyle="none",
                                  height=self.MAX_VISIBLE, exportselection=False,
                                  font=("Microsoft YaHei UI", 10),
                                  borderwidth=0, highlightthickness=0)
        self.listbox.pack(fill=tk.BOTH, expand=True)

        self.items = []
        self._after_id = None
        self._focus_after = None
        self._query_seq = 0
        self._suppress_focus_requery = False
        self._results_q = queue.Queue()
        self._drain()                    # 主线程周期取出后台查询结果

        entry.bind("<KeyRelease>", self._on_key, add="+")
        entry.bind("<FocusIn>", self._on_focus_in, add="+")
        entry.bind("<ButtonPress-1>", self._on_entry_click, add="+")
        entry.bind("<Down>", self._entry_down, add="+")
        entry.bind("<Escape>", lambda e: self.hide(), add="+")
        entry.bind("<FocusOut>", self._on_focus_out, add="+")
        self.listbox.bind("<ButtonPress-1>", lambda e: self._cancel_focus_check(), add="+")
        self.listbox.bind("<ButtonRelease-1>", self._on_click, add="+")
        self.listbox.bind("<Return>", self._on_enter, add="+")
        self.listbox.bind("<Escape>", self._listbox_escape, add="+")

    # ---------- 输入与查询 ----------
    def _on_key(self, event=None):
        if event is not None and event.keysym in (
                "Up", "Down", "Return", "Escape", "Tab", "Shift_L", "Shift_R",
                "Control_L", "Control_R", "Alt_L", "Alt_R", "Caps_Lock"):
            return
        self._schedule()

    def _on_focus_in(self, event=None):
        # 选择候选后程序调用的 focus_set 不重新弹出；用户点进来/切焦点才查
        if self._suppress_focus_requery:
            self._suppress_focus_requery = False
            return
        self._schedule()

    def _on_entry_click(self, event=None):
        # 再次点击输入框：允许重新联想
        self._suppress_focus_requery = False
        self._schedule()

    def _schedule(self):
        if self._after_id:
            try:
                self.root.after_cancel(self._after_id)
            except Exception:
                pass
        self._after_id = self.root.after(self.debounce_ms, self._start_query)

    def _start_query(self):
        self._after_id = None
        text = self.entry.get().strip()
        if len(text) < self.min_chars:
            self.hide()
            return
        self._query_seq += 1
        seq = self._query_seq

        def worker():
            try:
                results = self.fetch(text) or []
            except Exception:
                results = []
            self._results_q.put((seq, text, results))

        threading.Thread(target=worker, daemon=True).start()

    def _drain(self):
        """在主线程周期取出后台线程的查询结果（tkinter 不是线程安全的）"""
        try:
            while True:
                seq, text, results = self._results_q.get_nowait()
                self._apply_results(seq, text, results)
        except queue.Empty:
            pass
        except Exception:
            pass
        try:
            self.root.after(80, self._drain)
        except Exception:
            pass

    def _apply_results(self, seq, text, results):
        if seq != self._query_seq:
            return                                   # 已有更新的输入，丢弃过期结果
        if self.entry.get().strip() != text:
            return
        if not results:
            self.hide()
            return
        self.items = results
        self.listbox.delete(0, tk.END)
        for it in results:
            name = it.get("tag_name", "")
            trans = it.get("translation", "")
            if trans and trans != name:
                self.listbox.insert(tk.END, f"{name}    （{trans}）")
            else:
                self.listbox.insert(tk.END, name)
        self.listbox.selection_clear(0, tk.END)
        self.listbox.configure(height=min(len(results), self.MAX_VISIBLE))

        self.popup.deiconify()
        self.entry.update_idletasks()
        x = self.entry.winfo_rootx()
        y = self.entry.winfo_rooty() + self.entry.winfo_height()
        w = max(self.entry.winfo_width(), 240)
        h = self.listbox.winfo_reqheight() + 4
        self.popup.geometry(f"{w}x{h}+{x}+{y}")
        self.popup.lift()

    # ---------- 显示 / 隐藏 ----------
    def hide(self):
        try:
            self.popup.withdraw()
        except Exception:
            pass

    def _on_focus_out(self, event=None):
        self._cancel_focus_check()
        self._focus_after = self.root.after(150, self._check_focus)

    def _cancel_focus_check(self):
        if self._focus_after:
            try:
                self.root.after_cancel(self._focus_after)
            except Exception:
                pass
        self._focus_after = None

    def _check_focus(self):
        self._focus_after = None
        try:
            w = self.root.focus_get()
        except Exception:
            w = None
        if w is self.listbox or w is self.entry:
            return
        # 鼠标还按在弹窗上时（等点击选择）不要收起
        try:
            px, py = self.popup.winfo_pointerxy()
            under = self.root.winfo_containing(px, py)
        except Exception:
            under = None
        if under is self.listbox:
            return
        self.hide()

    # ---------- 选择 ----------
    def _choose(self, index):
        if index < 0 or index >= len(self.items):
            return
        tag = self.items[index].get("tag_name", "")
        # 选完后下拉不再自动弹出：取消未完成的查询 + 忽略程序触发的 FocusIn
        if self._after_id:
            try:
                self.root.after_cancel(self._after_id)
            except Exception:
                pass
            self._after_id = None
        self._query_seq += 1
        self._suppress_focus_requery = True
        self.root.after(300, self._clear_suppress)
        if tag:
            self.entry.delete(0, tk.END)
            self.entry.insert(0, tag)
        self.hide()
        self.entry.focus_set()
        self.entry.icursor(tk.END)

    def _clear_suppress(self):
        self._suppress_focus_requery = False

    def _on_click(self, event=None):
        if event is None:
            return
        idx = self.listbox.nearest(event.y)
        if idx >= 0:
            self._choose(idx)

    def _on_enter(self, event=None):
        sel = self.listbox.curselection()
        if sel:
            self._choose(sel[0])
        else:
            self._choose(self.listbox.index(tk.ACTIVE))
        return "break"

    def _listbox_escape(self, event=None):
        self.hide()
        self.entry.focus_set()
        return "break"

    def _entry_down(self, event=None):
        if self.popup.winfo_viewable() and self.listbox.size() > 0:
            self.listbox.focus_set()
            self.listbox.selection_clear(0, tk.END)
            self.listbox.selection_set(0)
            self.listbox.activate(0)
        return "break"


# ============================================================
# 界面布局
# ============================================================

class PixivGUI:
    def __init__(self, root):
        self.root = root
        self.root.title(f"Pixiv Scraper {scraper.VERSION} (beta)")
        self.root.geometry("750x700")
        self.root.minsize(650, 600)

        # 样式
        style = ttk.Style()
        style.theme_use("vista")

        # 用户选项（小众性癖允许列表等，跨启动保存）
        self._opts = self._load_options()
        self.allowed_niche = [k for k in (self._opts.get("allowed_niche") or [])
                              if isinstance(k, str)]

        # 主框架
        main_frame = ttk.Frame(root, padding=10)
        main_frame.pack(fill=tk.BOTH, expand=True)

        # ---- 配置区 ----
        config_frame = ttk.LabelFrame(main_frame, text="爬取配置", padding=10)
        config_frame.pack(fill=tk.X, pady=(0, 10))

        # Row 0: 搜索标签 + 排序
        row0 = ttk.Frame(config_frame)
        row0.pack(fill=tk.X, pady=2)
        ttk.Label(row0, text="搜索标签:", width=10).pack(side=tk.LEFT)
        self.tag_var = tk.StringVar(value=scraper.CONFIG.get("tag", ""))
        self.tag_entry = ttk.Entry(row0, textvariable=self.tag_var, width=30)
        self.tag_entry.pack(side=tk.LEFT, padx=5)
        # 标签联想下拉框（中文/日文/罗马音均可，如 "天童凯伊" → 天童ケイ）
        self.tag_suggest = TagSuggestBox(self.root, self.tag_entry, fetch=scraper.suggest_tags)
        ttk.Label(row0, text="排序:", width=6).pack(side=tk.LEFT, padx=(15, 0))
        self.order_var = tk.StringVar(value=scraper.CONFIG.get("order", "popular_d"))
        order_cb = ttk.Combobox(row0, textvariable=self.order_var, width=14, state="readonly",
                                values=["popular_d", "date_d", "popular_male_d", "popular_female_d"])
        order_cb.pack(side=tk.LEFT, padx=5)

        # Row 1: 下载数量 + 最低点赞
        row1 = ttk.Frame(config_frame)
        row1.pack(fill=tk.X, pady=2)
        ttk.Label(row1, text="下载数量:", width=10).pack(side=tk.LEFT)
        self.max_var = tk.IntVar(value=scraper.CONFIG.get("max_images", 60))
        ttk.Spinbox(row1, from_=1, to=9999, textvariable=self.max_var, width=8).pack(side=tk.LEFT, padx=5)
        ttk.Label(row1, text="最低点赞:", width=10).pack(side=tk.LEFT, padx=(15, 0))
        self.likes_var = tk.IntVar(value=scraper.CONFIG.get("min_likes", 50))
        ttk.Spinbox(row1, from_=0, to=999999, textvariable=self.likes_var, width=8).pack(side=tk.LEFT, padx=5)

        # Row 2: 下载目录
        row2 = ttk.Frame(config_frame)
        row2.pack(fill=tk.X, pady=2)
        ttk.Label(row2, text="下载目录:", width=10).pack(side=tk.LEFT)
        self.dir_var = tk.StringVar(value=scraper.CONFIG.get("download_dir", ""))
        ttk.Entry(row2, textvariable=self.dir_var, width=55).pack(side=tk.LEFT, padx=5)
        ttk.Button(row2, text="浏览...", command=self._browse_dir, width=7).pack(side=tk.LEFT)

        # Row 3: 复选框
        row3 = ttk.Frame(config_frame)
        row3.pack(fill=tk.X, pady=4)
        self.filter_ai_var = tk.BooleanVar(value=scraper.CONFIG.get("filter_ai", True))
        ttk.Checkbutton(row3, text="过滤AI", variable=self.filter_ai_var).pack(side=tk.LEFT, padx=5)
        self.include_r18_var = tk.BooleanVar(value=scraper.CONFIG.get("include_r18", True))
        ttk.Checkbutton(row3, text="包含R18", variable=self.include_r18_var).pack(side=tk.LEFT, padx=5)
        self.r18_only_var = tk.BooleanVar(value=scraper.CONFIG.get("r18_only", False))
        ttk.Checkbutton(row3, text="仅R18模式", variable=self.r18_only_var).pack(side=tk.LEFT, padx=5)
        self.show_browser_var = tk.BooleanVar(value=scraper.CONFIG.get("show_browser", True))
        ttk.Checkbutton(row3, text="显示浏览器", variable=self.show_browser_var).pack(side=tk.LEFT, padx=5)

        # Row 3.5: 小众性癖过滤（R18）
        row3b = ttk.Frame(config_frame)
        row3b.pack(fill=tk.X, pady=2)
        self.filter_niche_var = tk.BooleanVar(value=bool(self._opts.get(
            "filter_niche_r18", scraper.CONFIG.get("filter_niche_r18", True))))
        ttk.Checkbutton(row3b, text="过滤小众性癖(R18)",
                        variable=self.filter_niche_var).pack(side=tk.LEFT, padx=5)
        self.niche_btn = ttk.Button(row3b, text="", command=self._edit_niche, width=26)
        self.niche_btn.pack(side=tk.LEFT, padx=5)
        self._update_niche_btn()

        # Row 3.7: 删除偏好学习（beta：从删除行为学习不喜欢的标签）
        row3c = ttk.Frame(config_frame)
        row3c.pack(fill=tk.X, pady=2)
        self.learn_var = tk.BooleanVar(value=scraper.CONFIG.get("learn_prefer", True))
        ttk.Checkbutton(row3c, text="删除偏好学习（beta）",
                        variable=self.learn_var).pack(side=tk.LEFT, padx=5)
        ttk.Label(row3c, text="权重削减强度(%):").pack(side=tk.LEFT, padx=(12, 0))
        self.prefer_strength_var = tk.IntVar(value=scraper.CONFIG.get("prefer_strength", 50))
        ttk.Spinbox(row3c, from_=0, to=100, increment=10,
                    textvariable=self.prefer_strength_var, width=5).pack(side=tk.LEFT, padx=5)
        ttk.Label(row3c, text="（删掉部分图片后自动学习不喜欢的标签）",
                  foreground="gray").pack(side=tk.LEFT)

        # Row 5: 查重
        row5 = ttk.Frame(config_frame)
        row5.pack(fill=tk.X, pady=2)
        self.dedup_var = tk.BooleanVar(value=scraper.CONFIG.get("dedup", True))
        ttk.Checkbutton(row5, text="查重（跳过已处理的ID）", variable=self.dedup_var).pack(side=tk.LEFT, padx=5)
        ttk.Button(row5, text="清空查重记录", command=self._clear_history, width=13).pack(side=tk.LEFT, padx=15)

        # ---- 按钮区 ----
        btn_frame = ttk.Frame(main_frame)
        btn_frame.pack(fill=tk.X, pady=(0, 10))
        self.run_btn = ttk.Button(btn_frame, text="▶  开始爬取", command=self._start)
        self.run_btn.pack(side=tk.LEFT, padx=5)
        self.stop_btn = ttk.Button(btn_frame, text="■  停止", command=self._stop, state=tk.DISABLED)
        self.stop_btn.pack(side=tk.LEFT, padx=5)
        self.status_var = tk.StringVar(value="就绪")
        self.status_label = ttk.Label(btn_frame, textvariable=self.status_var, foreground="gray")
        self.status_label.pack(side=tk.RIGHT, padx=10)

        # ---- 日志输出 ----
        log_frame = ttk.LabelFrame(main_frame, text="运行日志", padding=5)
        log_frame.pack(fill=tk.BOTH, expand=True)
        self.log_text = tk.Text(log_frame, wrap=tk.WORD, font=("Consolas", 9),
                                bg="#1e1e1e", fg="#d4d4d4", insertbackground="white")
        log_scroll = ttk.Scrollbar(log_frame, command=self.log_text.yview)
        self.log_text.configure(yscrollcommand=log_scroll.set)
        self.log_text.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        log_scroll.pack(side=tk.RIGHT, fill=tk.Y)

        # 右键菜单
        self._setup_context_menu()

        self._thread = None

    def _setup_context_menu(self):
        menu = tk.Menu(self.log_text, tearoff=0)
        menu.add_command(label="清空日志", command=self._clear_log)
        menu.add_command(label="复制全部", command=self._copy_all)
        self.log_text.bind("<Button-3>", lambda e: menu.post(e.x_root, e.y_root))

    def _clear_log(self):
        self.log_text.delete("1.0", tk.END)

    def _copy_all(self):
        self.root.clipboard_clear()
        self.root.clipboard_append(self.log_text.get("1.0", tk.END).strip())

    # ---------- 用户选项（跨启动保存） ----------

    @property
    def _options_path(self):
        return os.path.join(getattr(scraper, "_base_dir", os.getcwd()), "pixdo_options.json")

    def _load_options(self):
        try:
            with open(self._options_path, encoding="utf-8") as f:
                data = json.load(f)
            return data if isinstance(data, dict) else {}
        except Exception:
            return {}

    def _save_options(self):
        try:
            with open(self._options_path, "w", encoding="utf-8") as f:
                json.dump({
                    "filter_niche_r18": bool(self.filter_niche_var.get()),
                    "allowed_niche": list(self.allowed_niche),
                }, f, ensure_ascii=False, indent=2)
        except Exception:
            pass

    def _update_niche_btn(self):
        n = len(self.allowed_niche)
        self.niche_btn.configure(
            text=f"允许的性癖：{n} 类…" if n else "允许的性癖：全部过滤…")

    def _edit_niche(self):
        """选择允许下载的小众性癖类别（勾选 = 允许）"""
        dlg = tk.Toplevel(self.root)
        dlg.title("允许下载的小众性癖（仅影响 R18）")
        dlg.resizable(False, False)
        try:
            dlg.transient(self.root)
            dlg.grab_set()
        except tk.TclError:
            pass
        ttk.Label(
            dlg,
            text="勾选 = 允许下载该类作品；未勾选的类别会被过滤。\n"
                 "全部不勾选 = 过滤所有小众性癖（仅对 R18 作品生效）",
            justify=tk.LEFT,
        ).pack(padx=16, pady=(14, 8), anchor="w")

        vars_ = {}
        box = ttk.Frame(dlg)
        box.pack(fill=tk.BOTH, expand=True, padx=16)
        for key, label, tags in scraper.NICHE_FETISHES:
            row = ttk.Frame(box)
            row.pack(fill=tk.X, anchor="w")
            v = tk.BooleanVar(value=key in self.allowed_niche)
            vars_[key] = v
            ttk.Checkbutton(row, text=label, variable=v, width=22).pack(side=tk.LEFT)
            ttk.Label(row, text="（" + "、".join(tags[:4]) + "）",
                      foreground="gray").pack(side=tk.LEFT, padx=6)

        btns = ttk.Frame(dlg)
        btns.pack(pady=12)

        def set_all(value):
            for v in vars_.values():
                v.set(value)

        def close():
            try:
                dlg.grab_release()
            except tk.TclError:
                pass
            dlg.destroy()

        def ok():
            self.allowed_niche = [k for k, v in vars_.items() if v.get()]
            self._update_niche_btn()
            self._save_options()
            close()

        ttk.Button(btns, text="全不选", command=lambda: set_all(False)).pack(side=tk.LEFT, padx=4)
        ttk.Button(btns, text="全选", command=lambda: set_all(True)).pack(side=tk.LEFT, padx=4)
        ttk.Button(btns, text="确定", command=ok).pack(side=tk.LEFT, padx=12)
        ttk.Button(btns, text="取消", command=close).pack(side=tk.LEFT, padx=4)
        dlg.protocol("WM_DELETE_WINDOW", close)

    def _browse_dir(self):
        d = filedialog.askdirectory(title="选择下载目录")
        if d:
            self.dir_var.set(d)

    def _clear_history(self):
        """清空查重记录（删除数据库文件）"""
        if self._thread and self._thread.is_alive():
            messagebox.showinfo("提示", "爬虫正在运行中，无法清空查重记录")
            return
        if not messagebox.askyesno("确认", "清空查重记录后，所有作品都会被当作新作品重新处理。\n确定要清空吗？"):
            return
        ok, msg = scraper.clear_history()
        self._log_to_gui(("[✓] " if ok else "[!] ") + msg)

    def _log_to_gui(self, msg):
        """线程安全的 GUI 日志追加"""
        def _append():
            self.log_text.insert(tk.END, msg + "\n")
            self.log_text.see(tk.END)
        self.root.after(0, _append)

    def _ask_low_yield(self, info):
        """爬虫线程调用：弹窗询问是否放宽点赞条件。返回 "lower" / "continue"

        弹窗在主线程显示，爬虫线程等待结果；超时（2 分钟）或用户点了
        「停止」则按「继续查找」处理，避免任务长时间卡住。
        """
        sug = info.get("suggested", 0)
        tip = f"放宽到 ≥{sug} 赞" if sug > 0 else "取消点赞过滤（设为 0）"
        msg = (f"筛选效率偏低：\n\n"
               f"已检查 {info['scanned']} 个作品的详情，"
               f"仅 {info['found']} / {info['target']} 个满足「≥{info['min_likes']} 赞」。\n\n"
               f"建议：{tip} —— 按已扫描的 {info['sample']} 个作品估算，"
               f"这一档约有 {info['est']} 个作品符合。\n\n"
               f"要继续按原条件查找，还是{tip}？")

        box = {"choice": "continue"}
        done = threading.Event()
        holder = {}

        def show():
            dlg = tk.Toplevel(self.root)
            holder["dlg"] = dlg
            dlg.title("筛选效率提醒")
            dlg.resizable(False, False)
            try:
                dlg.transient(self.root)
                dlg.grab_set()
            except tk.TclError:
                pass
            ttk.Label(dlg, text=msg, justify=tk.LEFT, wraplength=430).pack(
                padx=18, pady=(16, 10))

            def choose(choice):
                box["choice"] = choice
                try:
                    dlg.grab_release()
                except tk.TclError:
                    pass
                dlg.destroy()
                done.set()

            btns = ttk.Frame(dlg)
            btns.pack(pady=(0, 14))
            ttk.Button(btns, text=f"{tip}（推荐）",
                       command=lambda: choose("lower")).pack(side=tk.LEFT, padx=6)
            ttk.Button(btns, text="继续查找",
                       command=lambda: choose("continue")).pack(side=tk.LEFT, padx=6)
            dlg.protocol("WM_DELETE_WINDOW", lambda: choose("continue"))

            # 尽量居中显示在主窗口上方
            dlg.update_idletasks()
            w, h = dlg.winfo_reqwidth(), dlg.winfo_reqheight()
            x = self.root.winfo_rootx() + (self.root.winfo_width() - w) // 2
            y = self.root.winfo_rooty() + (self.root.winfo_height() - h) // 3
            dlg.geometry(f"+{max(0, x)}+{max(0, y)}")
            dlg.lift()
            dlg.focus_force()

        self.root.after(0, show)
        deadline = time.time() + 120
        while not done.wait(0.2):
            if scraper._should_stop() or time.time() > deadline:
                box["choice"] = "continue"
                self.root.after(0, lambda: holder.get("dlg") and holder["dlg"].destroy())
                self._log_to_gui("[*] 未收到选择（超时/停止），按「继续查找」处理")
                break
        return box["choice"]

    def _set_status(self, text, color="gray"):
        def _set():
            self.status_var.set(text)
            self.status_label.configure(foreground=color)
        self.root.after(0, _set)

    def _build_config(self):
        """从 GUI 控件构建配置 dict"""
        return {
            "tag": self.tag_var.get().strip(),
            "order": self.order_var.get(),
            "max_images": self.max_var.get(),
            "download_dir": self.dir_var.get().strip(),
            "min_likes": self.likes_var.get(),
            "filter_ai": self.filter_ai_var.get(),
            "include_r18": self.include_r18_var.get(),
            "r18_only": self.r18_only_var.get(),
            "show_browser": self.show_browser_var.get(),
            "dedup": self.dedup_var.get(),
            "filter_niche_r18": self.filter_niche_var.get(),
            "allowed_niche": list(self.allowed_niche),
            "learn_prefer": self.learn_var.get(),
            "prefer_strength": self.prefer_strength_var.get(),
        }

    def _start(self):
        config = self._build_config()
        self._save_options()

        # 基本校验
        if not config["tag"]:
            messagebox.showwarning("提示", "请输入搜索标签")
            return
        if not config["download_dir"]:
            messagebox.showwarning("提示", "请选择下载目录")
            return

        # 禁止重复运行
        if self._thread and self._thread.is_alive():
            messagebox.showinfo("提示", "爬虫正在运行中")
            return

        self._clear_log()
        self._log_to_gui("[*] 正在启动…")

        self.run_btn.configure(state=tk.DISABLED)
        self.stop_btn.configure(state=tk.NORMAL)
        self._set_status("运行中…", "green")

        def runner():
            result = scraper.run_scraper(
                config_override=config,
                log_callback=self._log_to_gui,
                ask_callback=self._ask_low_yield,
            )
            if result.get("ok"):
                self._set_status(f"完成 — 下载 {result.get('downloaded', 0)} 个作品", "blue")
            else:
                self._set_status(f"终止 — {result.get('reason', '未知错误')}", "red")

            # 恢复按钮
            def _done():
                self.run_btn.configure(state=tk.NORMAL)
                self.stop_btn.configure(state=tk.DISABLED)
            self.root.after(0, _done)

        self._thread = threading.Thread(target=runner, daemon=True)
        self._thread.start()

    def _stop(self):
        scraper.stop_scraper()
        self._set_status("正在停止…", "orange")
        self.stop_btn.configure(state=tk.DISABLED)
        self._log_to_gui("[!] 已请求停止 (等待当前步骤完成)")

    def on_close(self):
        """窗口关闭时确保停止爬虫"""
        scraper.stop_scraper()
        self.root.destroy()


# ============================================================
# 入口
# ============================================================

def main():
    root = tk.Tk()
    app = PixivGUI(root)
    root.protocol("WM_DELETE_WINDOW", app.on_close)
    root.mainloop()


if __name__ == "__main__":
    main()
