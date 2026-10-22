# 分支：pan8664716-登录红果TV

本分支合入 [pan8664716/HGTV](https://github.com/pan8664716/HGTV) 的代码，重点是**账号登录与云端同步**那一套。

## 来源与派生关系

- HGTV 本身是 [N3urda/hongguoTV](https://github.com/N3urda/hongguoTV) 的 fork（GPL-3.0-only）。
- 本分支以本仓库 `main`（`eea73e6`）为基线，合并 HGTV 的 `main`（`10637b6`，v1.2.2.2），**保留完整提交历史**，便于追溯每处改动。
- 合并后移除了旧的 React Native 电视版 / iOS / Node 服务结构，只保留 Android TV 原生客户端（`android/`）。
- 许可证与出处声明见 [LICENSE](LICENSE) 与 [NOTICE.md](NOTICE.md)；原上游代码著作权归其作者（N3urda、ermilylu 等）。

## 为什么 HGTV 有「自动登录」

严格说不是自动登录账号，而是**两件不同的事**，很多人会混为一谈：

### 1. 设备注册（匿名、自动、真正解决「用不了」）

平台的内容接口**按设备放行**，认的是服务端注册过的 `device_id` + `install_id` 这一对：

| 接口 | 未注册设备 | 注册后 |
| --- | --- | --- |
| App 搜索 `POST /reading/bookapi/search/tab/v` | `HTTP 200 + 0 字节`（从不返回数据） | `code 0`，一次 6–20 条、可翻页 |
| 播放 `POST /novel/player/video_model/v1/` | `101000 服务异常` | `code 0` + 完整播放模型 |

注册接口是 `POST https://log5-applog-lf.fqnovel.com/service/2/device_register/`，**匿名**：不要账号、不要密码、不要短信，请求里连签名头都没有。应用启动时后台调一次（`MainActivity.ensureDeviceRegistered`），拿到身份后落盘并交给仓库，之后搜索与播放都走 App 接口那条快路径。

协议细节与三个踩坑点见 [docs/DEVICE_REGISTER.md](docs/DEVICE_REGISTER.md)，实现见 `android/core/.../DeviceRegister.kt`。

> 这就是 issue 里「不导入账号就用不了」的**真实原因**：不是缺账号，是这台设备没在服务端注册过。

### 2. 账号登录（需要凭据，不能凭空自动）

真正的账号态要 `sessionid` 等凭据，来源只有一条路：

- **官方 App 登录**（模拟器或真机）→ 取出 `cookieStore.xml` 与设备档案 → 导入本机。
- 之后可读：账号资料 / 收藏 / 观看历史 / 点赞 / 预约；可写：收藏、进度。

`tools/login-lab/` 是配套的本机试跑台（`dump_device.py` 从已登录的模拟器取凭据，`server.py` 起网页）。

**手机号验证码登录与扫码登录走不通**（实测结论，见 `tools/login-lab/README.md`）：`aid=8662` 的 passport 手机号流程是给已登录账号**绑手机**用的；`login/v1`、`validate_code/v1`、官方扫码接口一律 `error_code=16 该应用无权限`。

凭据有效期是「签发时间 + 固定时长」（约 60 天），**不能自动滑动续期**，只能在还没掉登录时重新取一次。

### 3. 卸载重装后恢复

`targetSdk` 刻意钉在 29（传统存储模式），会话镜像写到自建的 `/sdcard/.hgtv/`（卸载不删），重装后直接恢复登录与设备身份。

## 本分支额外修的三处构建问题

HGTV 的 `main` 本身**编译不过**，本分支已修好（提交 `c23c645`）：

1. **`efs` 插件**（APM 插桩）来自私有仓库、不公开，拿不到会直接中断配置。改为可用时才应用——Kotlin 源码并不依赖它，只是构建期的埋点。
2. **播放器依赖**：`59ff3a9` 把依赖换成了旧的 `com.google.android.exoplayer` 2.19.1，但源码 import 的是 `androidx.media3.*`，包名对不上。已改回 media3 **1.4.1**（minSdk 19，与本工程支持 Android 4.4 一致；1.8.0 要求 minSdk 21）。
3. **`multidex-keep.pro`**：minSdk 19 时启动链路必须在主 dex 里，该文件此前被 `build.gradle.kts` 引用但未入库。

## 构建

需要 JDK 17、Android SDK Platform 36 / Build Tools 36.0.0。

```sh
echo "sdk.dir=$ANDROID_HOME" > android/local.properties
./android/gradlew -p android :core:test :app:assembleDebug
```

## 说明

- 版本号与 versionCode 沿用 HGTV 的（1.2.2.2 / 53），未改动。
- 正式签名密钥不在仓库中；缺少 `android/keystore.properties` 时回落到 debug 签名，构建不会失败。
- **已移除友盟统计 SDK**（`com.umeng.umsdk`）与 `efs` APM 插桩插件：Kotlin 源码本来就没用到它们，
  只在构建期做埋点。移除后 APK 减小约 50 KB，且不再向第三方上报任何使用数据。
