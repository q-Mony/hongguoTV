# Agent 备忘（项目约定）

## 分支策略（用户指定，2026-10-16 起）

- **`my-release` 是主分支**（远端 `origin/my-release`，由本地 `main` 推送而来）。
  正式发布、版本提升、CI 发布流程都以它为准。本地 `main` 与它同源。
- 远端 `origin/main`（eea73e6 旧 TV 发布线）**不再是主分支**；其内容已被
  `my-release` 完整包含（超集），保留仅作历史参考，勿再向其推送。
- 功能分支（保持各自功能，均须能播 1080P，均已合入播放接口修复）：
  - `feature/series-speed-menu` —— 分集倍速菜单、缓冲指示延迟、搜索兜底
  - `feature/mobile-downloads-touch` —— 手机下载 + 触摸播放控制
  - `fix/play-api-recovery` —— 1080P 恢复的独立提交线

## 版本规则

- 所有分支的默认版本统一为 **versionCode 10 / versionName 0.9.1**，
  不再使用 0.9.0 / versionCode 9。
- CI 发布线版本为 `0.9.1+run_number`（versionCode = 10000 + run_number）。
- `ReleaseVersionTest` 会守住默认 versionCode 必须大于上一发布版本（9）；
  发布新版本时同步上调该测试里的 `lastReleasedVersionCode`。

## 播放接口要点（详见 docs/research/2026-10-15-hongguo-play-api-recovery.md）

- 高清取流只走 `POST /novel/player/multi_video_model/preload/v1/`，
  `biz_param.video_platform` 必须为 1024。
- 设备指纹（device_id/iid）按安装生成并持久化（SharedPreferences
  `native-library-v1` 的 deviceId/installId/deviceRotatedAt），仅在两种签名
  方案全部被拒后限频轮换（24h 一次）。
- 签名方案 A（`Signer`，不发 x-medusa）为默认，方案 B（`FallbackSigner`，
  onedrama 简化签名）为兜底；A 覆盖所有路由失败后才轮到 B。
