# Pixiv 爬虫（Android）

桌面版 `pixiv_scraper.py` 的安卓移植版，使用 Kotlin + Jetpack Compose (Material 3) 开发。
功能与桌面版完全对齐：标签搜索、点赞/AI 过滤、R18 三模式、查重（含删图自动补下）、日志、停止控制。

## 功能一览
- 按标签搜索 pixiv 插画（综合热门 / 最新 / 男性向 / 女性向）
- 最低点赞数过滤、AI 生成作品过滤
- R18：包含 / 仅 R18 / 不含；自动分 `<标签>-safe`、`<标签>-r18` 文件夹
- 多页作品整组下载；文件按点赞数命名，便于排序浏览
- 内置查重（SQLite）：跳过已处理作品；手动删除图片后自动补下（按页补、沿用旧文件名）
- 图片保存到系统相册：`Pictures/PixivScraper/<标签>-safe`、`<标签>-r18`
- 应用内「说明」页：功能介绍、使用步骤、VPN 要求

## 环境要求
- 手机：Android 8.0 (API 26) 及以上（Android 10+ 无需存储权限）
- 手机需能访问 pixiv（中国大陆需 VPN / 代理，见应用内说明页）

## 构建 APK（Android Studio）
1. 安装 Android Studio：https://developer.android.com/studio
2. File → Open，选择本文件夹（`android-app`）
3. 等待 Gradle Sync 完成（首次会下载依赖，国内网络已配置阿里云镜像优先）
4. 构建：菜单 **Build → Build App Bundle(s) / APK(s) → Build APK(s)**
5. 产物位置：`app/build/outputs/apk/debug/app-debug.apk`

命令行构建（需已配置 Android SDK）：
```
gradlew.bat assembleDebug
```
- 若命令行提示找不到 SDK：在本目录创建 `local.properties`，写入 `sdk.dir=<你的SDK路径>`。

## 安装到手机
- 把 `app-debug.apk` 传到手机（数据线 / 文件传输工具均可）
- 手机上允许「安装未知应用」后点击安装

## 使用步骤
1. 打开 VPN / 代理（能访问 pixiv.net、accounts.pixiv.net、i.pximg.net）
2. 点应用内「登录」，在 WebView 中完成 pixiv 登录（自动检测，成功自动返回）
3. 确认账号已开启「设置 → 閲覧設定 → R-18作品の表示」（下载 R18 必需）
4. 回首页填标签与选项，点「开始爬取」
5. 图片保存在相册 `Pictures/PixivScraper/<标签>-safe`、`<标签>-r18`

## 查重说明
- 已下载且文件完整的作品自动跳过；不消耗接口次数
- 手动删除相册图片后，下次运行自动检测并补下
- 「清空查重记录」可强制全部重新检查
- `dedupSkipFiltered`（跳过已过滤作品）默认开启，可通过界面开关关闭

## 目录结构
```
android-app/
├── app/src/main/java/com/pixivscraper/
│   ├── MainActivity.kt          # 入口 + 页面导航
│   ├── MainViewModel.kt         # 状态/配置持久化/任务控制
│   ├── Models.kt                # 数据模型
│   ├── PixivApi.kt              # pixiv Web API（搜索/详情/下载）
│   ├── HistoryDb.kt             # 查重表（SQLite）
│   ├── ImageStore.kt            # 相册保存 + 文件索引
│   ├── ScraperEngine.kt         # 爬取主流程
│   └── ui/                      # Compose 界面（主页/登录页/说明页/主题）
└── app/src/main/res/            # 图标与主题资源
```

## 常见问题
- **Gradle 同步报错找不到 SDK**：用 Android Studio 打开会自动配置；命令行构建需设置 `ANDROID_HOME` 或 `local.properties`。
- **依赖下载失败**：检查网络/代理；`settings.gradle.kts` 已优先使用阿里云镜像。
- **登录页打不开**：检查手机代理是否生效，必要时换节点。
- **搜不到 R18**：确认已登录 + 账号已开启 R-18 显示 + 代理可用。

> 仅供个人学习使用。
