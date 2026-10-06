# -*- mode: python ; coding: utf-8 -*-
# 说明：用相对路径定位文件，clone 到任意目录都能直接打包。
import importlib.util
import os
from PyInstaller.utils.hooks import collect_all

_here = os.path.abspath(SPECPATH)  # noqa: F821  # PyInstaller 执行 spec 时注入
_selenium_dir = list(importlib.util.find_spec("selenium").submodule_search_locations)[0]
_selenium_mgr = os.path.join(
    _selenium_dir, "webdriver", "common", "windows", "selenium-manager.exe")

datas = []
binaries = [(_selenium_mgr, 'selenium\\webdriver\\common\\windows')]
hiddenimports = ['pixiv_scraper', 'PIL', 'PIL.Image', 'PIL.ImageFile']
tmp_ret = collect_all('selenium')
datas += tmp_ret[0]; binaries += tmp_ret[1]; hiddenimports += tmp_ret[2]
tmp_ret = collect_all('certifi')
datas += tmp_ret[0]; binaries += tmp_ret[1]; hiddenimports += tmp_ret[2]


a = Analysis(
    [os.path.join(_here, 'pixiv_gui.py')],
    pathex=[],
    binaries=binaries,
    datas=datas,
    hiddenimports=hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    noarchive=False,
    optimize=0,
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    [],
    name='PixivScraper',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=False,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)
