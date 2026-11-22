# 第三方组件与来源声明

FnTV（本仓库）是面向 Android TV 的第三方飞牛影视客户端，与飞牛影视 / fnOS 官方无关联。

## 协议实现来源

飞牛影视的 HTTP 协议细节（Authx 签名、登录、播放信息、拉流、进度回传）整理自以下开源项目：

- **QiaoKes/fntv-electron** — GPL-3.0 — <https://github.com/QiaoKes/fntv-electron>
  - 用到的部分：`src/modules/fn_api/request.ts`（Authx 签名与请求重试）、`api.ts`（接口路径）、
    `src/modules/proxy/.../playvideo.go`（`media/range` 拉流与鉴权头形态）
- **FNOSP/AniBox** — AGPL-3.0 — <https://github.com/FNOSP/AniBox>
  - 用到的部分：`src-tauri/src/fnapi.rs`（网关 Cookie、端口探测、重定向）、`lib.rs`
    （`item/list` 筛选参数、`season/list` / `episode/list` 关系、`play/info` 字段解析）、
    `fnos_progress.rs`（`play/record` 报文字段）

本项目为 Android 平台的**独立重写**（Kotlin / Compose / Media3），不包含上述项目的源代码；
由于协议实现派生自 GPL-3.0 的 fntv-electron，本项目整体以 **GPL-3.0** 发布，见 [LICENSE](LICENSE)。

## 运行时依赖

| 组件 | 许可 |
| --- | --- |
| AndroidX / Jetpack Compose / Media3 | Apache-2.0 |
| OkHttp / Okio | Apache-2.0 |
| Coil | Apache-2.0 |
| Kotlin / kotlinx.coroutines | Apache-2.0 |
| Material Icons | Apache-2.0 |

## 商标与内容

- 「飞牛影视」「fnOS」「飞牛私有云」等名称与商标归其各自权利人所有。
- 本项目不提供、不存储、不分发任何影视内容；只播放用户自有 NAS 上的媒体。
- 登录凭据只保存在用户本机（SharedPreferences），不向任何第三方服务器发送。