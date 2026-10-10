#!/usr/bin/env python3
"""
pixdo 桌面版 GUI — tkinter 界面
用法: python pixiv_gui.py

界面结构（顶部分页）：
  爬取     —— 常用操作：标签 / 数量 / 最低点赞 / 排序 / R18 / 开始停止 / 运行日志入口
  操作说明 —— 使用步骤与功能说明
  设置     —— 登录状态检测、下载目录、各类过滤与查重设置
  关于     —— 使用风险、版本信息、AI 生成声明
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

FONT = "Microsoft YaHei UI"

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
                                  font=(FONT, 10),
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
# 说明 / 关于文本
# ============================================================

GUIDE_ITEMS = [
    ("使用步骤", "h"),
    ("1. 开启 VPN / 代理（需能正常访问 pixiv）\n"
     "2. 首次使用先到「设置」页检查登录状态；运行时若未登录会自动打开浏览器等待登录\n"
     "3. 回到「爬取」页输入标签（支持中文联想日文 Tag），按需调整排序、数量、最低点赞与 R18 选项\n"
     "4. 点「开始爬取」；过程中可点「停止」中止，进度见「运行日志」窗口\n"
     "5. 下载完成后到下载目录查看（路径见「设置」页）", "b"),
    ("保存位置", "h"),
    ("· 图片保存在下载目录下的 <标签>/safe（普通）与 <标签>/r18 文件夹中，按点赞数命名方便排序", "b"),
    ("网络要求", "h"),
    ("· 需能访问：www.pixiv.net、accounts.pixiv.net（登录）、i.pximg.net（图片）\n"
     "· 大陆网络必须开启代理（建议全局代理，或把浏览器与本程序加入代理规则）\n"
     "· 登录页打不开 / 一直转圈：更换节点后重试", "b"),
    ("R18 说明", "h"),
    ("· 需已登录，且账号已开启「设置 → 閲覧設定 → R-18作品の表示」\n"
     "· 「包含R18」同时搜索普通与 R18 两条通道；「仅R18」只下载 R18 作品", "b"),
    ("动图（うごイラ）说明", "h"),
    ("· 动图会自动下载帧序列并合成为可播放的 GIF\n"
     "· 合成一次完成，帧数多时需要一些时间，属正常现象", "b"),
    ("查重说明", "h"),
    ("· 已下载且文件完整的作品自动跳过\n"
     "· 删除图片后会区分意图：\n"
     "   只删了一部分（挑掉几张不好看的）→ 视为有意保留，不再补下\n"
     "   整组作品全删 → 视为不喜欢，不再补下，并计入删除偏好学习\n"
     "   一次几乎删光（≥ 九成）→ 视为批量清理，下次重新下载\n"
     "· 「设置」页可改为「整组删除后重新下载」，也可清空查重记录让所有作品重新参与", "b"),
    ("小众性癖过滤（R18）", "h"),
    ("· 内置常见小众性癖的标签库，默认全部过滤（仅对 R18 作品生效）\n"
     "· 在「设置」页勾选允许的类别后，这些类别的作品才会下载", "b"),
    ("删除偏好学习（beta）", "h"),
    ("· 默认只看「整组删除」的作品（视为明确不喜欢），下次运行降低其标签的排序权重\n"
     "· 「挑片删除」（同一作品只删了几页，例如清理重复图 / 无用图）默认不计入学习，\n"
     "   可在「设置」页勾选「挑片删除也计入偏好学习」开始计入（权重会封顶）\n"
     "· 角色名、作品名、系列名等身份标签不参与统计；与该标签高度伴随的\n"
     "   基础标签（例如角色本身就是贫乳时的「贫乳」）同样不会被计入\n"
     "· 偏好按搜索标签分别学习：换标签后旧偏好不会串台\n"
     "· 强度可在「设置」页调整；数据来自本机查重记录，清空记录即重置", "b"),
    ("筛选效率提醒", "h"),
    ("· 检查了很多作品仍凑不够目标数量时，会提醒一次：\n"
     "   「继续查找」或「放宽最低点赞」（附建议参考值）\n"
     "· 较长时间未选择会按「继续查找」自动继续", "b"),
    ("常见问题", "h"),
    ("· 一直显示未登录 → 检查代理是否生效；删除 cookies.pkl 后重新运行登录\n"
     "· 搜索无结果 → 检查标签拼写（推荐日文）与代理\n"
     "· 提示 429 限流 → 程序会自动等待重试，属正常现象\n"
     "· Edge / Chrome 启动失败 → 更新浏览器后重试（驱动会尝试自动匹配）\n"
     "· 中文联想不出结果 → 用更短的词头试试（如「百合园」）", "b"),
]

ABOUT_ITEMS = [
    ("⚠ 使用风险（必读）", "risk"),
    ("· 本工具通过 pixiv 公开接口批量获取内容。虽然内置了请求间隔与限流自动重试，\n"
     "   但短时间内大量下载仍可能被判定为异常访问，可能导致：接口限流、账号功能受限，\n"
     "   甚至封号。\n"
     "· 建议：保持默认间隔、单次下载量不要过大、避免长时间连续运行；介意风险请使用小号登录。\n"
     "· 下载内容仅供个人学习与收藏，版权归原作者所有，请勿传播或用于商业用途。", "riskb"),
    ("版本信息", "h"),
    (f"· 应用名称：pixdo\n"
     f"· 版本号：{scraper.VERSION}\n"
     f"· 渠道：beta（实验性功能，可能随时调整或回退）\n"
     f"· 运行环境：Windows（免安装单文件 exe，需本机安装 Edge / Chrome 用于登录）", "b"),
    ("关于本项目", "h"),
    ("· 本项目（含代码、界面与文案）全部由 AI 生成，仅供个人学习与技术研究使用。\n"
     "· 本应用与 pixiv 官方无关；请遵守 pixiv 的使用条款与当地法律法规。\n"
     "· 使用者需自行承担因使用本工具产生的一切风险与责任（包括但不限于账号封禁、数据丢失等）。\n"
     "· 请尊重创作者版权，支持原作者。", "b"),
]


# ============================================================
# 界面
# ============================================================

class PixivGUI:

    # 排序：显示名 -> 接口值
    ORDER_LABELS = [
        ("popular_d", "综合热门"),
        ("date_d", "最新"),
        ("popular_male_d", "男性向"),
        ("popular_female_d", "女性向"),
    ]

    def __init__(self, root):
        self.root = root
        self.root.title(f"pixdo {scraper.VERSION}")
        self.root.geometry("840x660")
        self.root.minsize(720, 560)

        # 样式
        style = ttk.Style()
        style.theme_use("vista")

        # 用户选项（小众性癖允许列表等，跨启动保存）
        self._opts = self._load_options()
        self.allowed_niche = [k for k in (self._opts.get("allowed_niche") or [])
                              if isinstance(k, str)]

        self._thread = None
        self._login_state = None            # True / False / None
        self._log_lines = []                # 日志缓冲（日志窗口关闭时也不丢）
        self._log_win = None
        self._log_text = None

        # ---- 顶部分页：爬取 / 操作说明 / 设置 / 关于 ----
        nb = ttk.Notebook(root)
        nb.pack(fill=tk.BOTH, expand=True, padx=8, pady=8)
        self.notebook = nb
        self.tab_scrape = ttk.Frame(nb, padding=12)
        self.tab_guide = ttk.Frame(nb, padding=(12, 8))
        self.tab_settings = ttk.Frame(nb, padding=8)
        self.tab_about = ttk.Frame(nb, padding=(12, 8))
        nb.add(self.tab_scrape, text="  爬取  ")
        nb.add(self.tab_guide, text="  操作说明  ")
        nb.add(self.tab_settings, text="  设置  ")
        nb.add(self.tab_about, text="  关于  ")

        self._build_scrape_tab()
        self._build_info_tab(self.tab_guide, GUIDE_ITEMS)
        self._build_settings_tab()
        self._build_info_tab(self.tab_about, ABOUT_ITEMS)

        self._sync_conditions()

        # 启动后台检查登录状态（仅用本地 cookies，不启动浏览器）
        threading.Thread(target=self._check_login_worker, daemon=True).start()

    # ---------- 小工具 ----------

    @staticmethod
    def _desc(parent, text):
        """灰色功能说明文字（不含具体参数）"""
        return ttk.Label(parent, text=text, foreground="#666666",
                         justify=tk.LEFT, wraplength=740)

    def _toggle(self, frame, show):
        """条件显示：frame 始终存在于布局中，仅切换内部是否可见"""
        frame.pack_forget()
        if show:
            frame.pack(fill=tk.X)

    def _sync_conditions(self):
        """按开关状态决定哪些控件出现（只显示相关的那一层）"""
        # 仅 R18：只在「包含R18」开启时出现
        if self.include_r18_var.get():
            if not self.r18_only_cb.winfo_manager():
                self.r18_only_cb.pack(side=tk.LEFT, padx=(4, 0))
        else:
            self.r18_only_var.set(False)
            self.r18_only_cb.pack_forget()
        # 允许的性癖：只在开启小众性癖过滤时出现
        self._toggle(self.niche_content, self.filter_niche_var.get())
        # 跳过已过滤作品：只在开启查重时出现
        self._toggle(self.dedup_skip_content, self.dedup_var.get())
        # 偏好削减强度：只在开启删除偏好学习时出现
        self._toggle(self.learn_content, self.learn_var.get())

    def _make_scroll_frame(self, parent):
        """可滚动的容器（设置项较多时使用）"""
        canvas = tk.Canvas(parent, highlightthickness=0)
        sb = ttk.Scrollbar(parent, command=canvas.yview)
        inner = ttk.Frame(canvas)
        inner.bind("<Configure>",
                   lambda e: canvas.configure(scrollregion=canvas.bbox("all")))
        win = canvas.create_window((0, 0), window=inner, anchor="nw")
        canvas.bind("<Configure>", lambda e: canvas.itemconfigure(win, width=e.width))
        canvas.configure(yscrollcommand=sb.set)
        canvas.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        sb.pack(side=tk.RIGHT, fill=tk.Y)

        def _wheel(event):
            canvas.yview_scroll(int(-event.delta / 120), "units")

        canvas.bind_all("<MouseWheel>", _wheel)
        return inner

    def _build_info_tab(self, parent, items):
        """只读文本页（操作说明 / 关于）"""
        text = tk.Text(parent, wrap="word", relief="flat", padx=6, pady=4,
                       font=(FONT, 10), cursor="arrow")
        sb = ttk.Scrollbar(parent, command=text.yview)
        text.configure(yscrollcommand=sb.set)
        text.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        sb.pack(side=tk.RIGHT, fill=tk.Y)

        text.tag_configure("h", font=(FONT, 11, "bold"), spacing1=10, spacing3=2)
        text.tag_configure("b", spacing1=2, spacing3=6)
        text.tag_configure("risk", foreground="#c0392b",
                           font=(FONT, 11, "bold"), spacing1=2, spacing3=2)
        text.tag_configure("riskb", foreground="#c0392b", spacing3=6)

        for content, tag in items:
            text.insert(tk.END, content + "\n", tag)
        text.configure(state="disabled")

    # ---------- 爬取页 ----------

    def _build_scrape_tab(self):
        f = self.tab_scrape

        # 当前状态 / 进度（开始前 / 运行中 / 已完成，一看就知道跑到哪一步）
        st = ttk.LabelFrame(f, text="当前状态", padding=12)
        st.pack(fill=tk.X, pady=(0, 10))
        head = ttk.Frame(st)
        head.pack(fill=tk.X)
        self.state_var = tk.StringVar(value="未开始")
        self.state_label = ttk.Label(head, textvariable=self.state_var,
                                     font=(FONT, 12, "bold"))
        self.state_label.pack(side=tk.LEFT)
        self.count_var = tk.StringVar(value="已下载 0 / 目标 -")
        ttk.Label(head, textvariable=self.count_var, foreground="#666666").pack(side=tk.RIGHT)
        self.phase_var = tk.StringVar(value="填写标签后点击「开始爬取」")
        ttk.Label(st, textvariable=self.phase_var, foreground="#666666").pack(
            anchor="w", pady=(4, 6))
        self.progress = ttk.Progressbar(st, mode="determinate", maximum=100, value=0)
        self.progress.pack(fill=tk.X)
        self._progress_mode = None
        self._progress_done = 0
        self._progress_target = 0

        box = ttk.LabelFrame(f, text="搜索条件", padding=12)
        box.pack(fill=tk.X)

        # 标签 + 排序
        row0 = ttk.Frame(box)
        row0.pack(fill=tk.X, pady=3)
        ttk.Label(row0, text="搜索标签:", width=10).pack(side=tk.LEFT)
        self.tag_var = tk.StringVar(value=scraper.CONFIG.get("tag", ""))
        self.tag_entry = ttk.Entry(row0, textvariable=self.tag_var, width=28)
        self.tag_entry.pack(side=tk.LEFT, padx=5)
        # 标签联想下拉框（中文/日文/罗马音均可，如 "天童凯伊" → 天童ケイ）
        self.tag_suggest = TagSuggestBox(self.root, self.tag_entry, fetch=scraper.suggest_tags)

        ttk.Label(row0, text="排序:", width=6).pack(side=tk.LEFT, padx=(15, 0))
        order0 = scraper.CONFIG.get("order", "popular_d")
        self.order_var = tk.StringVar(
            value=dict(self.ORDER_LABELS).get(order0, self.ORDER_LABELS[0][1]))
        ttk.Combobox(row0, textvariable=self.order_var, width=12, state="readonly",
                     values=[label for _, label in self.ORDER_LABELS]).pack(side=tk.LEFT, padx=5)

        # 下载数量 + 最低点赞
        row1 = ttk.Frame(box)
        row1.pack(fill=tk.X, pady=3)
        ttk.Label(row1, text="下载数量:", width=10).pack(side=tk.LEFT)
        self.max_var = tk.IntVar(value=scraper.CONFIG.get("max_images", 60))
        ttk.Spinbox(row1, from_=1, to=9999, textvariable=self.max_var,
                    width=8).pack(side=tk.LEFT, padx=5)
        ttk.Label(row1, text="最低点赞:", width=10).pack(side=tk.LEFT, padx=(15, 0))
        self.likes_var = tk.IntVar(value=scraper.CONFIG.get("min_likes", 50))
        ttk.Spinbox(row1, from_=0, to=999999, textvariable=self.likes_var,
                    width=8).pack(side=tk.LEFT, padx=5)

        # R18（仅 R18 只在「包含R18」开启时出现）
        row2 = ttk.Frame(box)
        row2.pack(fill=tk.X, pady=3)
        ttk.Label(row2, text="R18:", width=10).pack(side=tk.LEFT)
        self.include_r18_var = tk.BooleanVar(value=scraper.CONFIG.get("include_r18", True))
        ttk.Checkbutton(row2, text="包含R18作品", variable=self.include_r18_var,
                        command=self._sync_conditions).pack(side=tk.LEFT, padx=(0, 8))
        self.r18_only_var = tk.BooleanVar(value=scraper.CONFIG.get("r18_only", False))
        self.r18_only_cb = ttk.Checkbutton(row2, text="仅R18模式（不下载普通作品）",
                                           variable=self.r18_only_var)

        # 运行按钮
        btn = ttk.Frame(f)
        btn.pack(fill=tk.X, pady=(14, 0))
        self.run_btn = ttk.Button(btn, text="▶  开始爬取", command=self._start)
        self.run_btn.pack(side=tk.LEFT, padx=(0, 6))
        self.stop_btn = ttk.Button(btn, text="■  停止", command=self._stop, state=tk.DISABLED)
        self.stop_btn.pack(side=tk.LEFT, padx=6)
        ttk.Button(btn, text="运行日志", command=self._open_log_window).pack(side=tk.RIGHT)

        # 小提示（填充剩余空间，让页面不显得空）
        tips = ttk.LabelFrame(f, text="小提示", padding=12)
        tips.pack(fill=tk.BOTH, expand=True, pady=(12, 0))
        for line in (
            "运行中可以切后台 / 锁屏，进度看上方「当前状态」与通知栏（Android）。",
            "凑不够数量时，可以降低「最低点赞」，或换一个更通用的标签。",
            "图片保存在下载目录的 <标签>/safe 与 <标签>/r18 文件夹中，按点赞数命名。",
            "首次使用请先到「设置」页检查登录状态（下载 R18 必须登录）。",
            "更多说明见「操作说明」页，设置项集中在「设置」页。",
        ):
            ttk.Label(tips, text="· " + line, foreground="#666666",
                      justify=tk.LEFT, wraplength=760).pack(anchor="w", pady=1)

    # ---------- 设置页 ----------

    def _build_settings_tab(self):
        inner = self._make_scroll_frame(self.tab_settings)

        # ---- pixiv 账号 ----
        acc = ttk.LabelFrame(inner, text="pixiv 账号", padding=10)
        acc.pack(fill=tk.X, pady=(0, 8))
        self._desc(acc, "下载 R18 作品需要登录；登录信息（cookies）只保存在本机，不会上传。").pack(anchor="w")
        row = ttk.Frame(acc)
        row.pack(fill=tk.X, pady=(6, 0))
        self.login_state_var = tk.StringVar(value="登录状态：检测中…")
        self.login_label = ttk.Label(row, textvariable=self.login_state_var, foreground="gray")
        self.login_label.pack(side=tk.LEFT)
        ttk.Button(row, text="检查登录状态", width=14,
                   command=self._check_login_async).pack(side=tk.LEFT, padx=10)
        self.show_browser_var = tk.BooleanVar(value=scraper.CONFIG.get("show_browser", True))
        ttk.Checkbutton(row, text="运行时显示浏览器窗口（登录 / 排查问题）",
                        variable=self.show_browser_var).pack(side=tk.LEFT, padx=10)

        # ---- 下载目录 ----
        dl = ttk.LabelFrame(inner, text="下载目录", padding=10)
        dl.pack(fill=tk.X, pady=(0, 8))
        self._desc(dl, "图片保存位置：<下载目录>/<标签>/safe（普通）与 <标签>/r18（R18）。").pack(anchor="w")
        row = ttk.Frame(dl)
        row.pack(fill=tk.X, pady=(6, 0))
        self.dir_var = tk.StringVar(value=scraper.CONFIG.get("download_dir", ""))
        ttk.Entry(row, textvariable=self.dir_var, width=58).pack(side=tk.LEFT, padx=(0, 6))
        ttk.Button(row, text="浏览…", command=self._browse_dir, width=7).pack(side=tk.LEFT)

        # ---- 内容过滤 ----
        flt = ttk.LabelFrame(inner, text="内容过滤", padding=10)
        flt.pack(fill=tk.X, pady=(0, 8))
        self._desc(flt, "过滤被标记为 AI 生成的作品；建议同时在 pixiv 网页版关闭「展示 AI 生成作品」。").pack(anchor="w")
        row = ttk.Frame(flt)
        row.pack(fill=tk.X, pady=(6, 0))
        self.filter_ai_var = tk.BooleanVar(value=scraper.CONFIG.get("filter_ai", True))
        ttk.Checkbutton(row, text="过滤 AI 生成", variable=self.filter_ai_var).pack(side=tk.LEFT)

        # ---- 小众性癖过滤（R18） ----
        niche = ttk.LabelFrame(inner, text="小众性癖过滤（R18）", padding=10)
        niche.pack(fill=tk.X, pady=(0, 8))
        self._desc(niche, "过滤 R18 作品中的小众性癖标签；只有勾选的类别会被下载。").pack(anchor="w")
        row = ttk.Frame(niche)
        row.pack(fill=tk.X, pady=(6, 0))
        self.filter_niche_var = tk.BooleanVar(value=bool(self._opts.get(
            "filter_niche_r18", scraper.CONFIG.get("filter_niche_r18", True))))
        ttk.Checkbutton(row, text="启用小众性癖过滤", variable=self.filter_niche_var,
                        command=self._sync_conditions).pack(side=tk.LEFT)
        self.niche_content = ttk.Frame(niche)
        self.niche_btn = ttk.Button(self.niche_content, text="",
                                    command=self._edit_niche, width=26)
        self.niche_btn.pack(side=tk.LEFT, padx=(0, 6))
        self._update_niche_btn()

        # ---- 查重 ----
        dedup = ttk.LabelFrame(inner, text="查重", padding=10)
        dedup.pack(fill=tk.X, pady=(0, 8))
        self._desc(dedup, "已经下载过的作品不会重复下载；整组删掉的作品视为不喜欢，"
                          "不再补下并计入偏好学习（一次几乎删光则视为清理，下次重新下载）。").pack(anchor="w")
        row = ttk.Frame(dedup)
        row.pack(fill=tk.X, pady=(6, 0))
        self.dedup_var = tk.BooleanVar(value=scraper.CONFIG.get("dedup", True))
        ttk.Checkbutton(row, text="启用查重（跳过已处理的 ID）", variable=self.dedup_var,
                        command=self._sync_conditions).pack(side=tk.LEFT)
        ttk.Button(row, text="清空查重记录", command=self._clear_history,
                   width=13).pack(side=tk.LEFT, padx=12)
        self.dedup_skip_content = ttk.Frame(dedup)
        self.dedup_skip_var = tk.BooleanVar(
            value=scraper.CONFIG.get("dedup_skip_filtered", True))
        ttk.Checkbutton(self.dedup_skip_content, text="跳过已过滤作品（低赞 / AI）",
                        variable=self.dedup_skip_var).pack(side=tk.LEFT)
        self.redownload_deleted_var = tk.BooleanVar(
            value=scraper.CONFIG.get("redownload_deleted", False))
        ttk.Checkbutton(self.dedup_skip_content, text="整组删除后重新下载（视为清理）",
                        variable=self.redownload_deleted_var).pack(side=tk.LEFT, padx=12)

        # ---- 删除偏好学习（beta） ----
        learn = ttk.LabelFrame(inner, text="删除偏好学习（beta）", padding=10)
        learn.pack(fill=tk.X, pady=(0, 8))
        self._desc(learn, "根据你的删除行为，自动统计不喜欢的标签并在排序时降低它们的权重"
                          "（只影响顺序，不会直接排除）。默认只看「整组删除」——"
                          "清理重复图 / 无用图不会影响学习；偏好按搜索标签分别学习。").pack(anchor="w")
        row = ttk.Frame(learn)
        row.pack(fill=tk.X, pady=(6, 0))
        self.learn_var = tk.BooleanVar(value=scraper.CONFIG.get("learn_prefer", True))
        ttk.Checkbutton(row, text="启用删除偏好学习", variable=self.learn_var,
                        command=self._sync_conditions).pack(side=tk.LEFT)
        self.learn_content = ttk.Frame(learn)
        self.learn_from_partial_var = tk.BooleanVar(
            value=scraper.CONFIG.get("learn_from_partial", False))
        ttk.Checkbutton(self.learn_content, text="挑片删除也计入偏好学习",
                        variable=self.learn_from_partial_var).pack(side=tk.LEFT, padx=(0, 16))
        ttk.Label(self.learn_content, text="偏好削减强度(%):").pack(side=tk.LEFT, padx=(0, 4))
        self.prefer_strength_var = tk.IntVar(value=scraper.CONFIG.get("prefer_strength", 50))
        ttk.Spinbox(self.learn_content, from_=0, to=100, increment=10,
                    textvariable=self.prefer_strength_var, width=5).pack(side=tk.LEFT)

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

    # ---------- 登录状态 ----------

    def _check_login_async(self):
        self._set_login_state(None, "登录状态：检测中…")
        threading.Thread(target=self._check_login_worker, daemon=True).start()

    def _check_login_worker(self):
        try:
            state, msg = scraper.check_login_from_cookies()
        except Exception as e:
            state, msg = None, f"检查失败（{e}）"
        try:
            self.root.after(0, lambda: self._set_login_state(state, f"登录状态：{msg}"))
        except Exception:
            pass                                   # 窗口已关闭

    def _set_login_state(self, state, text):
        self._login_state = state
        try:
            self.login_state_var.set(text)
            self.login_label.configure(
                foreground={True: "#1e7b1e", False: "#c0392b", None: "gray"}.get(state, "gray"))
        except Exception:
            pass

    # ---------- 运行日志（独立窗口） ----------

    def _open_log_window(self):
        if self._log_win is not None and self._log_win.winfo_exists():
            self._log_win.deiconify()
            self._log_win.lift()
            self._log_win.focus_force()
            return
        win = tk.Toplevel(self.root)
        win.title("运行日志")
        win.geometry("780x520")
        win.minsize(520, 320)

        body = ttk.Frame(win, padding=8)
        body.pack(fill=tk.BOTH, expand=True)
        self._log_text = tk.Text(body, wrap=tk.WORD, font=("Consolas", 9),
                                 bg="#1e1e1e", fg="#d4d4d4", insertbackground="white")
        sb = ttk.Scrollbar(body, command=self._log_text.yview)
        self._log_text.configure(yscrollcommand=sb.set)
        self._log_text.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        sb.pack(side=tk.RIGHT, fill=tk.Y)

        btns = ttk.Frame(win, padding=(8, 0, 8, 8))
        btns.pack(fill=tk.X)
        ttk.Button(btns, text="复制全部", command=self._copy_all).pack(side=tk.LEFT)
        ttk.Button(btns, text="清空日志", command=self._clear_log).pack(side=tk.LEFT, padx=6)
        ttk.Button(btns, text="关闭", command=self._close_log_window).pack(side=tk.RIGHT)

        menu = tk.Menu(self._log_text, tearoff=0)
        menu.add_command(label="清空日志", command=self._clear_log)
        menu.add_command(label="复制全部", command=self._copy_all)
        self._log_text.bind("<Button-3>", lambda e: menu.post(e.x_root, e.y_root))

        if self._log_lines:
            self._log_text.insert(tk.END, "\n".join(self._log_lines) + "\n")
            self._log_text.see(tk.END)

        self._log_win = win
        win.protocol("WM_DELETE_WINDOW", self._close_log_window)

    def _close_log_window(self):
        if self._log_win is not None:
            try:
                self._log_win.destroy()
            except Exception:
                pass
        self._log_win = None
        self._log_text = None

    def _clear_log(self):
        self._log_lines = []
        if self._log_text is not None:
            self._log_text.delete("1.0", tk.END)

    def _copy_all(self):
        try:
            self.root.clipboard_clear()
            self.root.clipboard_append("\n".join(self._log_lines))
        except Exception:
            pass

    def _log_to_gui(self, msg):
        """线程安全的 GUI 日志追加（日志窗口未打开时也先缓存）"""
        self._log_lines.append(msg)
        if len(self._log_lines) > 5000:
            del self._log_lines[:-3000]

        def _append():
            if self._log_text is not None:
                try:
                    self._log_text.insert(tk.END, msg + "\n")
                    self._log_text.see(tk.END)
                except Exception:
                    pass
        self.root.after(0, _append)

    # ---------- 低产提醒（爬虫线程回调） ----------

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

    # ---------- 运行 ----------

    # ---------- 状态 / 进度显示 ----------

    def _on_status(self, info):
        """爬虫线程通过 status_callback 上报：转到主线程刷新界面"""
        try:
            self.root.after(0, lambda: self._apply_status(info))
        except Exception:
            pass

    def _set_progress_mode(self, mode, value=None):
        if self._progress_mode != mode:
            self._progress_mode = mode
            self.progress.stop()
            self.progress.configure(mode=mode)
            if mode == "indeterminate":
                self.progress.start(60)
        if mode == "determinate" and value is not None:
            self.progress.configure(value=value)

    def _apply_status(self, info):
        """运行中：状态 = 运行中，阶段 / 进度条按上报信息刷新"""
        state = str(info.get("state") or "starting")
        phase = str(info.get("phase") or "")
        try:
            if info.get("target"):
                self._progress_target = int(info["target"])
            if info.get("downloaded") is not None:
                self._progress_done = int(info["downloaded"])
        except (TypeError, ValueError):
            pass
        self.state_var.set("运行中")
        self.state_label.configure(foreground="#1e7b1e")
        if phase:
            self.phase_var.set(phase)
        self.count_var.set(f"已下载 {self._progress_done} / 目标 "
                           f"{self._progress_target or '-'}")
        if state == "downloading" and self._progress_target > 0:
            self._set_progress_mode(
                "determinate",
                min(100.0, self._progress_done * 100.0 / self._progress_target))
        else:
            self._set_progress_mode("indeterminate")

    def _finish_status(self, ok, downloaded, skipped, reason):
        """任务结束：已完成 / 已中断"""
        def _apply():
            self._set_progress_mode("determinate")
            if ok:
                self.state_var.set("已完成")
                self.state_label.configure(foreground="#1e7b1e")
                self.progress.configure(value=100)
                tail = f"，查重跳过 {skipped} 个" if skipped else ""
                self.phase_var.set(f"下载 {downloaded} 个作品{tail}（详情见「运行日志」）")
                self.count_var.set(f"已下载 {downloaded} / 目标 "
                                   f"{self._progress_target or downloaded}")
            else:
                self.state_var.set("已中断")
                self.state_label.configure(foreground="#c0392b")
                self.progress.configure(value=0)
                self.phase_var.set(reason or "任务未完成（详情见「运行日志」）")
        self.root.after(0, _apply)

    def _order_value(self):
        label = self.order_var.get()
        for value, text in self.ORDER_LABELS:
            if text == label:
                return value
        return label or "popular_d"

    def _build_config(self):
        """从 GUI 控件构建配置 dict"""
        return {
            "tag": self.tag_var.get().strip(),
            "order": self._order_value(),
            "max_images": self.max_var.get(),
            "download_dir": self.dir_var.get().strip(),
            "min_likes": self.likes_var.get(),
            "filter_ai": self.filter_ai_var.get(),
            "include_r18": self.include_r18_var.get(),
            "r18_only": self.r18_only_var.get(),
            "show_browser": self.show_browser_var.get(),
            "dedup": self.dedup_var.get(),
            "dedup_skip_filtered": self.dedup_skip_var.get(),
            "redownload_deleted": self.redownload_deleted_var.get(),
            "filter_niche_r18": self.filter_niche_var.get(),
            "allowed_niche": list(self.allowed_niche),
            "learn_prefer": self.learn_var.get(),
            "learn_from_partial": self.learn_from_partial_var.get(),
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
            messagebox.showwarning("提示", "请先在「设置」页选择下载目录")
            self.notebook.select(self.tab_settings)
            return

        # 禁止重复运行
        if self._thread and self._thread.is_alive():
            messagebox.showinfo("提示", "爬虫正在运行中")
            return

        # 登录提醒：未确认已登录时先询问（登录状态检测在「设置」页）
        if self._login_state is not True:
            if not messagebox.askyesno(
                "登录提醒",
                "尚未检测到已登录的 pixiv 账号。\n\n"
                "可以先去「设置」页点「检查登录状态」，首次使用需完成一次登录；\n"
                "运行时若仍未登录，程序会自动打开浏览器等待登录。\n\n"
                "仍要立即开始吗？",
            ):
                self.notebook.select(self.tab_settings)
                self._check_login_async()
                return

        self._clear_log()
        self._open_log_window()
        self._log_to_gui("[*] 正在启动…")
        self._apply_status({"state": "starting", "phase": "准备中…", "downloaded": 0,
                            "target": int(self.max_var.get() or 0)})

        self.run_btn.configure(state=tk.DISABLED)
        self.stop_btn.configure(state=tk.NORMAL)

        def runner():
            result = scraper.run_scraper(
                config_override=config,
                log_callback=self._log_to_gui,
                ask_callback=self._ask_low_yield,
                status_callback=self._on_status,
            )
            self._finish_status(bool(result.get("ok")),
                                int(result.get("downloaded", 0) or 0),
                                int(result.get("skipped_dup", 0) or 0),
                                str(result.get("reason", "") or ""))

            # 恢复按钮 + 刷新登录状态
            def _done():
                self.run_btn.configure(state=tk.NORMAL)
                self.stop_btn.configure(state=tk.DISABLED)
            self.root.after(0, _done)
            threading.Thread(target=self._check_login_worker, daemon=True).start()

        self._thread = threading.Thread(target=runner, daemon=True)
        self._thread.start()

    def _stop(self):
        scraper.stop_scraper()
        self.stop_btn.configure(state=tk.DISABLED)

        def _apply():
            self.state_var.set("正在停止…")
            self.state_label.configure(foreground="orange")
            self.phase_var.set("等待当前步骤结束（详情见「运行日志」）")
        self.root.after(0, _apply)
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
