# FnTV · 飞牛影视电视版

[![build-apk](https://github.com/lrf1995/FnTvbox/actions/workflows/build-apk.yml/badge.svg)](https://github.com/lrf1995/FnTvbox/actions/workflows/build-apk.yml)
[![release](https://img.shields.io/github/v/release/lrf1995/FnTvbox?color=2ea44f)](https://github.com/lrf1995/FnTvbox/releases)
[![license](https://img.shields.io/badge/license-GPL--3.0-green)](LICENSE)

一个给 **Android TV / 安卓电视盒子** 用的飞牛影视（fnOS / trimemedia）第三方客户端：
遥控器操作、原生硬解、断点续播，只播放你自己 NAS 上的媒体库。

> 这不是把网页塞进电视浏览器，而是 **Kotlin + Jetpack Compose + Media3** 重写的 10-foot UI，
> 直接调用飞牛影视的 HTTP API，播放走 `media/range` 直连。

**仓库**：<https://github.com/lrf1995/FnTvbox>　**下载**：[Releases](https://github.com/lrf1995/FnTvbox/releases)

---

## 与两个参考项目的关系

| 参考项目 | 用到了什么 |
| --- | --- |
| [QiaoKes/fntv-electron](https://github.com/QiaoKes/fntv-electron)（GPL-3.0） | **协议实现依据**：Authx 签名算法、`/v/api/v1/login`、`/play/info`、`/media/range/{guid}` 拉流、`/play/record` 进度回传、请求头与 Cookie 形态 |
| [FNOSP/AniBox](https://github.com/FNOSP/AniBox)（AGPL-3.0） | **产品形态参考**：直连 API 而非注入网页、首页「继续观看 + 媒体库横排」、详情页「季 / 选集」信息架构、`item/list` 的筛选参数 |

本仓库是**面向 TV 的重新实现**，不包含上述两个项目的源码，也不含 AniBox 的
Bangumi/TMDB 元数据、在线规则源、弹幕等模块——那些能力依赖第三方服务，TV 端刻意不做。

协议实现派生自 GPL-3.0 的 fntv-electron，因此本项目以 **GPL-3.0** 发布，见 [LICENSE](LICENSE)。

---

## 已实现功能

**连接**
- NAS 地址 + 用户名 + 密码登录（地址支持裸 IP、无协议头、全角冒号，自动补 `http://`）
- 登录后固化 fnOS 302 跳转后的真实端口（80 → 5666/17777 之类）
- token 保存在本机 SharedPreferences，下次启动免登录；token 失效自动退回登录页

**首页**
- 「继续观看」横排（数据来自飞牛 `play/list`，带进度条）
- 每个媒体库一行海报，标题 + 分类 + 集数
- 一键刷新

**媒体库**
- 6 列海报网格，滚动到尾部自动加载下一页（60/页）
- 文件夹（Directory）可逐层钻取
- 已看角标、进度条

**详情页**
- 背景虚化海报 + 海报/评分/年份/季集数/简介
- 多季切换（`season/list`）、选集网格（`episode/list`，每集显示播放进度与已看状态）
- 「继续播放」自动定位到上次没看完的那一集
- 「标记已看」写回飞牛

**播放器**
- Media3 / ExoPlayer，支持 H.264 / HEVC 硬解，MKV / MP4 / TS 等容器
- 起播自动续播（`play/info` 的 `ts`，>3 秒才跳）
- 一集结束自动连播下一集（可关）
- 遥控器：OK 播放/暂停、左右 ±10 秒、上下呼出控制条、返回退出
- 控制条：进度、时间、音轨、字幕（内嵌）、倍速 1.0/1.25/1.5/2.0、下一集、退出
- 播放进度每 10 秒 + 暂停/切集/退出时回传飞牛 `play/record`，手机/电脑上的飞牛能看到同一进度

**其他**
- 全局搜索（`search/list`，拼音首字母/英文/数字关键词最顺手）
- 设置：自动连播开关、清图片缓存、刷新首页、重新登录、关于
- 电视遥控器软键盘（英文/数字/符号），登录和搜索都用它
- 首屏自动聚焦，全程 D-pad 可达，无触摸依赖
- 兼容自签证书的内网 NAS（TrustManager 放行）

---

## 安装

### 方式 A：Releases 下载（推荐）
1. 打开 [Releases](https://github.com/lrf1995/FnTvbox/releases)，下载最新 `FnTV-<版本>-debug.apk`；
2. 拷到 U 盘，插电视/盒子，用自带「文件管理 / 电视管家」打开安装；
3. 首次安装需允许「未知来源应用」。

> 仓库每次 push 到 `main` 都会由 Actions 自动构建 APK（Artifacts 可下）；打 `v1.0.0` 这类 tag 会自动建 Release。

### 方式 B：U 盘（本地构建产物）
1. 把 `dist/FnTV-1.0.0-debug.apk` 拷到 U 盘；
2. U 盘插电视/盒子，用自带的「文件管理 / 电视管家」打开 APK 安装；
3. 首次安装需允许「未知来源应用」。

### 方式 C：adb（同一局域网）
```bash
adb connect 192.168.1.50:5555      # 电视需先打开「开发者选项 → USB/网络调试」
adb install -r FnTV-1.0.0-debug.apk
```

### 方式 D：当贝/沙发管家等
把 APK 放进手机或 NAS 的共享目录，用电视上的应用市场「本地安装」功能打开。

安装后桌面会出现 **FnTV**（带 Leanback banner），也出现在「应用 → 全部」里。

---

## 首次使用

1. 打开 FnTV → 进入登录页；
2. **服务器地址**：填 NAS 的 IP + 飞牛影视端口，例如
   `192.168.1.10:5666`（端口常见 `5666 / 5667 / 8000 / 8001`，只填 IP 也行，会按 80 端口尝试）；
3. **用户名 / 密码**：你的飞牛 NAS **本地账号**（不是飞牛云 / FN Connect 账号）；
4. 点「连接并进入」。成功后会直接进首页。

> 地址、账号只保存在本机，不上传任何服务器。密码不落盘，只保存登录 token。

### 登录失败时：用「网页登录」兜底

飞牛的 `POST /v/api/v1/login` 在**密码错误**和**失败过多被临时限流**两种情况下都返回
`-15 Password Incorrect`，无法区分；限流期间即使密码正确也一律报错。

所以登录页提供了第二条路：点 **「用网页登录」**，在应用内打开的 fnOS 网页里正常登录一次。
登录成功后网页会种下 `Trim-MC-token` Cookie，客户端直接拿它当会话（与 API 登录等价），
还能顺带处理「应用访问码」和二次验证。

如果两种方式都提示被拒绝，请**停止重试**，等 10 分钟后再试一次——连续失败只会延长封禁。

---

## 遥控器操作

| 场景 | 按键 |
| --- | --- |
| 全局 | 方向键移动焦点，**OK** 确认，**返回** 回上一级（首页再按返回退出） |
| 首页 | 上下切换横排，左右浏览海报，OK 进详情 |
| 详情 | 左右选季，上下选「播放 / 标记已看 / 选集」，OK 起播 |
| 播放中 | **OK** 播放/暂停；**左/右** −10s/+10s；**上/下** 呼出控制条并移动；**返回** 退出并保存进度 |
| 软键盘 | 方向键选字符，OK 输入，「确定」提交 |

---

## 已知限制（重要）

1. **音频**：Media3 不带 DTS / TrueHD / DTS-HD 软解。若电视/盒子本身不支持该音轨，会出现「有画面没声音」。
   解决：在飞牛端选「转码播放」，或换一条 AC3/AAC 音轨（详情页播放器的「音轨」里选）。
2. **云盘直链**：只走 NAS 本地 `media/range`，未实现 115/夸克等云盘直链（AniBox 里那套）。
3. **电视直播**：`live_channels` 分支未实现，直播条目会提示没有可播放媒体。
4. **弹幕 / 在线资源 / TMDB·Bangumi 元数据**：刻意不做——本项目定位是「只播自己 NAS」。
5. **中文搜索**：电视软键盘只有英文/数字；可用拼音首字母（如 `jqr` 搜「进击的巨人」不保证，取决于 NAS 的搜索实现）或直接用电视自带输入法。
6. **多季识别**：依赖 NAS 已刮削出季结构（`season/list`）；未刮削的剧可能只有一季或没有选集。
7. 未在真机验证过的设备：本项目完成于无 TV 硬件的环境，首次运行若遇崩溃/黑屏，请抓 `adb logcat` 反馈。

---

## 首次运行排错

| 现象 | 处理 |
| --- | --- |
| 登录提示「无法连接服务器」 | 确认 NAS 的**飞牛影视**应用已启动；端口试 `5666 / 5667 / 8000 / 8001`；确认电视和 NAS 在同一网段（不要填 `localhost`） |
| 登录提示「地址/端口可能填错了」 | 返回的是网页而不是接口数据，通常是端口填成了 NAS 管理页（如 5666 是系统页、影视可能是 8000/17777），换端口再试 |
| 首页海报全空白 | token 过期或图片接口变更：设置 → 重新登录 |
| 播放有画面没声音 | TV 不支持该音轨（DTS/TrueHD）：播放器里切「音轨」，或在飞牛端转码 |
| 一点播放就退出 | 多为 `play/info` 或 `media/range` 被拦：抓日志确认 |

抓日志（电视开 ADB 调试后）：
```bash
adb connect <电视IP>:5555
adb logcat -s FnTV AndroidRuntime ExoPlayerImpl
```

> 当前 `dist/` 里的包是 **debug 签名**，可直接安装；正式分发请用 Android Studio 生成 release 签名包。

---

## 构建

环境：JDK 17、Android SDK（compileSdk 35 / build-tools 35.0.0）。

```bash
# Android Studio：直接打开 fntv-tv 目录，Run 'app'
# 或命令行（Windows）
.\build.ps1            # 本机脚本，内含 JDK/SDK 路径与沙箱所需的 GRADLE_USER_HOME
# 产物
app/build/outputs/apk/debug/app-debug.apk
```

`build.ps1` 里写死了本机工具链路径，换机器时改 `JAVA_HOME` / `ANDROID_HOME` 即可；
也可以直接 `gradle assembleDebug` 或 `./gradlew assembleDebug`。

### CI 自动构建（GitHub Actions）

仓库内置 [.github/workflows/build-apk.yml](.github/workflows/build-apk.yml)：

- **push 到 `main`**：自动编译 debug APK，产物在 Actions 的 Artifacts 里（`FnTV-debug-apk`）；
- **打 tag**：自动创建 Release 并附上 APK，版本号取自 `app/build.gradle.kts` 的 `versionName`。

发版流程：

```bash
# 1) 改 app/build.gradle.kts 里的 versionCode / versionName
# 2) 更新 CHANGELOG.md
git add -A && git commit -m "release: v1.1.0"
git tag v1.1.0
git push origin main --tags
```

---

## 目录结构

```text
fntv-tv/
├── app/src/main/java/app/fntv/tv/
│   ├── TvApp.kt                 # Application：Prefs/Repo 初始化、Coil 图片加载器（带鉴权）
│   ├── MainActivity.kt          # 路由（Home/Library/Detail/Search/Settings）、返回栈、启动播放器
│   ├── data/
│   │   ├── FnApi.kt             # 飞牛 API 客户端：Authx 签名、请求/重定向/重试、各接口
│   │   ├── Models.kt            # Lib / MediaItem / Season / Episode / PlayInfo / PlayRequest
│   │   ├── Repo.kt              # 会话状态 + 首页/库/详情数据聚合与缓存
│   │   └── Prefs.kt             # 本机配置（server/token/偏好）
│   ├── ui/
│   │   ├── Theme.kt             # 深色 10-foot 主题令牌
│   │   ├── NavBar.kt            # 顶栏与 Logo
│   │   ├── Components.kt        # PosterCard / SectionRow / TvButton / Loading / Error
│   │   ├── TvKeyboard.kt        # 遥控器软键盘
│   │   └── screens/             # Login / Home / Library / Detail / Search / Settings
│   └── player/
│       ├── PlayerActivity.kt    # ExoPlayer、续播、连播、进度回传、按键分发
│       └── PlayerUi.kt          # 播放器覆盖层 UI（Compose）
├── app/src/main/res/            # 图标、banner、主题
├── build.ps1 / build-debug.ps1  # 本机构建脚本
└── dist/FnTV-1.0.0-debug.apk    # 已构建产物
```

---

## 更新与发布

本仓库的日常更新走一条固定流水线：**改代码 → 提交 → 推 `main` → CI 出 APK → 打 tag 发 Release**。

```bash
# 只推代码（CI 会构建，产物在 Actions → Artifacts）
powershell -NoProfile -File tools\push.ps1 -Message "fix: 修复 xxx"

# 发版：先在 app/build.gradle.kts 改 versionCode/versionName，再
powershell -NoProfile -File tools\push.ps1 -Message "release: v1.1.0" -Tag v1.1.0
```

也可以手工：

```bash
git push origin main
git tag v1.1.0 && git push origin v1.1.0
```

> `tools/push.ps1` 从 `$env:GH_TOKEN` 读取令牌（fine-grained PAT，需要 **Contents** 与 **Workflows** 读写），
> 令牌只出现在单次命令里，不会写进 `.git/config`。没有令牌时直接 `git push` 走本机凭据即可。

---

## 合规与免责声明

- 本项目是**第三方、非官方**客户端，与飞牛影视 / fnOS 官方无隶属、合作或背书关系。
- 「飞牛影视」「fnOS」「飞牛私有云」等名称与商标归其各自权利人所有。
- 本项目**不提供、不存储、不分发任何影视内容**；只播放你自有 NAS 上的媒体。
- 需要登录你自己的飞牛账号；登录态只保存在本机，请勿分享 `config`/`SharedPreferences` 文件。
- 协议实现参考了 GPL-3.0 的 fntv-electron，故本项目以 **GPL-3.0** 发布；分发衍生版本时请保留
  [LICENSE](LICENSE) 与本节声明。
- 按「现状」提供，不提供任何担保；使用后果由使用者自行承担。