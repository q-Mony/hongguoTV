# 红果播放接口 110001/空响应 失效与恢复调研

调研日期：2026-10-15。目标：定位高清取流接口返回 `110001`／空 body 的根因，判断 720p/1080p 今天能否恢复，并给出最小改动路径。

本轮用 Node v24 在 VM 里加载 `server/vendor/hongguo.cjs` 的签名内部函数，对 `api5-normal-sinfonlineb/a/c.fqnovel.com` 做了实测，并用 `curl` 与 `ffprobe` 复核；另核对了上游 `drpys` 的当前 HEAD 与 `yhoops/onedrama` 的源码。下文每条结论都标注是**本轮实测**（我自己跑出来的）还是**外部来源**（别的仓库/文档这样说，我核对了原文但没有在本项目内运行）。

## 结论摘要

**能恢复，1080P 已在本轮实测中打通。** 需要两个条件：

1. **取流路由改了。** `/novel/player/multi_video_model/v1/` 与 `/novel/player/video_model/v1/` 已停供（HTTP 200 但无数据）；眼下唯一还吐数据的入口是 **`/novel/player/multi_video_model/preload/v1`**，且 `biz_param.video_platform` 必须是 **1024**（详情接口自己给的值是 3，这个入口不认）。
2. **固定的 `device_id`/`iid` 组合已被烧掉，必须换成新生成的指纹。** 这一条是**唯一真正必要的签名侧改动**：固定指纹无论发不发 `x-medusa` 都失败；全新指纹无论发不发都成功。

`x-medusa` 是**可选**的（详见 4b 的更正）：它与 query 自洽时可以正常成功，所以不是根因。最终仍选择不发，是因为它内嵌自己的 device id、必须与 query 同步重算，一旦不一致就返回「HTTP 200 + 空 body」，是个纯粹的风险来源；不发它对成功率没有任何损失。

修复面只有三处：`Signer.kt`（不发 `x-medusa`）、`VendorConstants.kt`（设备指纹按安装生成）、`ContentRepository.kt`（路由与 payload）。

## 现象与实测记录

### 1. 路由矩阵（本轮实测，签名用固定实现全套头）

在 `api5-normal-sinfonlineb/a/c.fqnovel.com` 上逐条打，设备用 `VendorConstants` 里的固定指纹：

| 路由 | 结果 |
| --- | --- |
| `POST /novel/player/multi_video_model/v1/` | HTTP 200，`{"Code":110001,"Message":"未知异常",...}` |
| `POST /novel/player/video_model/v1/` | HTTP 200，**body 0 字节** |
| `GET` 上两条 | 与 POST 完全相同（`multi`→110001，`video_model`→空） |
| `POST /novel/player/multi_video_model/v2/` | 110001 |
| `POST /novel/player/video_model/v2/` | 空 body |
| `POST /novel/player/multi_video_model/preload/v1` | HTTP 200，**空 body**（带 `x-medusa` 时） |
| `POST /novel/player/video_model/preload/v1` | HTTP 404 `404 Page not found`（该路由不存在） |
| `POST /novel/player/multi_video_detail/v1/`（`{"series_id":...}`） | **HTTP 200，`code:0`，84 KB 真实数据** |

三个 host（`sinfonlinea`/`b`/`c`）表现一致；不带字母的 `api5-normal-sinfonline.fqnovel.com` TLS 握手失败（本轮实测，未能连通）。

原始 `curl` 复核（排除 axios 客户端伪影）：`video_model/v1/` 返回 `content-length: 0`，`server: volc-dcdn`；`multi_video_model/v1/` 返回 125 字节 110001，`server: TLB`；`multi_video_detail/v1/` 返回 84076 字节 `code:0`。空 body 是服务端真实返回，不是客户端丢包。

### 2. 签名判别实验（本轮实测，关键）

同一 `multi_video_model/v1/`，逐步破坏签名：

| 变体 | 结果 |
| --- | --- |
| 完整合法签名 | 110001 |
| 合法签名 + 垃圾 `video_id` | 110001 |
| `x-gorgon` 清零 | 110001 |
| `x-medusa` 改写 | 110001 |
| `x-helios` 改写 | 110001 |
| `x-ladon` 改写 | 110001 |
| `x-argus` 改写 | 110001 |
| `x-ss-stub` 清零 | 110001 |
| **把所有签名头全部删掉** | **110001（与合法签名一字不差）** |
| `x-khronos` 回拨 3600 秒 | 110001 |

**这条很关键**：在 `multi_video_model/v1/` 上，`110001` 与签名完全无关——连一个签名头都不发也是同一响应。所以 110001 在这个路由上是**路由级/网关级**的错误，不是「签名被风控拒绝」。

**反证**：同一天、同一签名实现、同一 host，`multi_video_detail/v1/` 带**完整**签名（含 `x-medusa`）返回 `code:0` 真实数据。这说明签名算法本身是**有效**的，`x-medusa` 也不是普遍致命，只是在取流路由上致命。

### 3. 应用版本/包名不是原因（本轮实测）

在 `multi_video_model/v1/` 上换 6 组应用身份，全部 110001：

| UA / version_code | 结果 |
| --- | --- |
| `com.phoenix.read/71332`（固定实现） | 110001 |
| `com.phoenix.read/72232` | 110001 |
| `com.kylin.read/73532`（上游 Python 源在用） | 110001 |
| `com.kylin.read/74032` | 110001 |
| `com.phoenix.read/73532` | 110001 |
| `com.dragon.read/73532` | 110001 |

### 4. preload 路由 × 签名 × 设备 三项交叉（本轮实测，决定修复方案）

同一个 `POST /novel/player/multi_video_model/preload/v1`，同一 payload：

| 设备指纹 | 签名 | 结果 |
| --- | --- | --- |
| 固定 `1905892595382586`/`1905892595378490` | 全套（含 `x-medusa`） | 空 body |
| 固定 | **去掉 `x-medusa`** | 空 body（设备已被烧） |
| 全新随机 id/iid | 全套（含 `x-medusa`） | 空 body |
| 全新随机 id/iid | **去掉 `x-medusa`** | ✅ `code=0`，返回真实 `video_list` |

### 4b. 更正：「`x-medusa` 致命」这个结论是错的（后续实测推翻）

上表第 3 行「全新设备 + 带 `x-medusa` 仍失败」**不是**因为 medusa 本身有害，而是因为那次实验里 medusa 与 query **不一致**：`buildMedusa()` 读的是**模块级 `DEVICE` 常量**，而 query 用的是新随机设备，于是报文里携带旧设备号。

父级 agent 随后在 Kotlin 侧补做了**一致性**矩阵（每个用例用同一个 device map 同时构造 query 与 medusa，各跑 3 轮）：

| 设备指纹 | `x-medusa` | 结果 |
| --- | --- | --- |
| 固定（已烧） | 带 | FAIL FAIL FAIL |
| 固定（已烧） | 不带 | FAIL FAIL FAIL |
| 全新随机 | 带（**与 query 一致**） | **OK OK OK** |
| 全新随机 | 不带 | **OK OK OK** |

**结论：真正必要的只有「设备指纹必须新」。** `x-medusa` 不是致命的，带着一个**与 query 自洽**的 medusa 完全可以成功。

那为什么最终仍然选择不发 `x-medusa`？因为它是**纯粹的风险来源而非收益**：
- 它内嵌自己的 device id，必须与 query 同步重算，属于额外的、容易写错的一致性负担；
- 一旦不一致，服务端返回「HTTP 200 + 空 body」（实测 4/4），与真实故障无法区分，排查成本极高；
- 一致性矩阵显示不发它对成功率**没有任何损失**。

即：`x-medusa` 从「必须去掉的根因」降级为「为降低脆弱性而主动不发的可选头」。方案 A 的动作不变，但理由要按这条更正来写。

### 5. 成功响应的形状（本轮实测）

去掉 `x-medusa` + 全新设备后，成功响应形如 `data[<vid>].video_model`（字符串或对象），其中 `video_list` 是**数组共 5 档**：

| 下标 | `gear_des_key` 里的档位 | `quality_type` | 编码 |
| --- | --- | --- | --- |
| 0 | `360p` | 40 | `h265_hvc1` |
| 1 | `480p` | 30 | `h265_hvc1` |
| 2 | `540p` | 20 | `h265_hvc1` |
| 3 | `720p` | 10 | `h265_hvc1` |
| 4 | `1080p` | 1 | `h265_hvc1` |

每档都有 `main_url`（明文 `https://...qznovelvod.com`）、`encrypt_info.spade_a`、`feature_id`、`gear_des_key`。`gear_des_key` 里的 `1:encrypt` 表示都是加密流。

**端到端复核（本轮实测）**：取第 4 档（1080p）`main_url` 发 `Range: bytes=0-1048575`，返回 **HTTP 206 / `video/mp4` / 1048576 字节**；对该分片 `ffprobe` 输出：

```
codec_name=hevc  profile=Main  width=1080  height=1920  codec_type=video
codec_name=aac   profile=LC                  codec_type=audio
format_name=mov,mp4,m4a,3gp,3g2,mj2   duration=174.720000
```

即 1080p 档确为 HEVC 1080x1920 竖屏。

### 6. 上游 `drpys` 并没有更新（本轮实测）

- `git ls-remote`：`woshishiq1/drpys` 的 `main` = `4cf38b9fa278ca2920c3465b67234f27111337d1`，提交时间 **2026-10-12 13:38:48 +0800**，消息 `up2.0.6-1012`；钉住版本之后还有 6 个提交（`48ed6b3` 9-24、`cd1f563` 9-26、`8327540` 9-28、`574cc59` 9-30、`4a16bd5` 10-07、`4cf38b9` 10-12）。
- `spider/js/红果果[短].js` **仍然存在**（此前用 `git ls-tree` 未加 `core.quotepath=false` 时被八进制转义路径骗过，误判为「已删除」，已更正）。
- 逐字节 `diff`：钉住的 `server/vendor/hongguo.cjs` 与 `22261ad:spider/js/红果果[短].js` **完全相同**；`22261ad` 与当前 `4cf38b9` 的该文件也**完全相同**。

**结论：上游从未修过这个源，单纯「升级钉住的提交」不可能修复。** 同仓库的 `spider/py/红果漫剧.py` 是另一套 Python 实现（用 `com.kylin.read/73532`、支持 branch 0/2），其取流路由同样写死 `multi_video_model/v1/`，也没有 preload 修复。

### 7. 网页路径确认没有高清（本轮实测）

- `https://hongguoduanju.com/player/7686713195246930968/7686715217652223038` 的 HTML 里只有 `/video/public/<vid>/360p.mp4`。
- 该页面加载的 JS 分片（`static/js/player.be6c1b8992.js`、`o.836724cc5f.js`、`j.5d1f4b76d6.js`）里，唯一与视频有关的路径是 `"/video/public/"`；全部分片里只出现一次 `360p`，没有任何 480/720/1080 或隐藏 JSON 接口。

即网页侧不含高清来源，HD 只能走 App 签名接口。

### 8. 外部来源记录的同一现象

`yhoops/onedrama`（Go 写的第三方项目）在 `hongguo/media.go` 的注释里明确写着：`/novel/player/video_model/v1/`（单集）与 `/novel/player/multi_video_model/v1/` 都以「HTTP 200 + 空响应体」表示停供、2026-10 起两者同时变成这样，`preload` 是眼下唯一还吐数据的入口。该文件把 `appVideoModelPath` 常量定为 `/novel/player/multi_video_model/preload/v1/`。**这是外部来源**，但它与本轮实测（去掉 `x-medusa` 后 preload 成功）一致。

## 根因判断

### 主根因（两条必要 + 一条可选）

**(1) `multi_video_model/v1` / `video_model/v1` 这两个取流路由已被上游停供。**
证据：本轮实测两条路由分别稳定返回 `110001` 与 0 字节空 body，且 `multi_video_model/v1` 的响应**与签名完全无关**（把全部签名头删掉仍是一字不差的 110001）。若是签名/风控问题，不可能「不发签名」和「发完整签名」得到同一个响应体。同 host 的 `multi_video_detail/v1/` 用同一签名返回 `code:0`，进一步证明网关和签名都正常。外部来源 `onedrama/hongguo/media.go` 独立记录了同一结论。

这里要区分两种停供形态：`video_model/v1` 是「200 + content-length 0」，`multi_video_model/v1` 是「200 + 110001」。`onedrama` 的注释把两者都描述为空 body，本轮实测只有后者是空 body，前者是 110001——这个细节差异不影响结论（两者都不返回数据），但记录在此以免后人误引。

**(2) 固定的 `device_id`/`iid` 组合已被标记。**
证据：本轮实测——固定设备即使去掉 `x-medusa` 仍为空 body，换成随机设备后立刻成功；一致性矩阵（4b）显示固定指纹发不发 medusa 都失败、全新指纹发不发都成功，所以这一条是**唯一必要的签名侧改动**。外部来源 `zhangbaio/hongguo` 的 `docs/设备池与device_register侦察-20260629.md` 也记录：同一 IP 短时灌入大量全新随机 `device_id` 会被静默风控（网关返回空 body），而经过注册、有服务端历史的设备始终 `code=0`；并记录了「改机型字段但 UA 不同步 → 空 body」的一致性约束。

**(3) `x-medusa` 是可选头，不是根因（原结论已更正）。**
本轮最初判为「致命」，但那是**实验设计缺陷**：那次 medusa 由模块级 `DEVICE` 常量生成，与已随机化的 query 不自洽。补做一致性矩阵后（4b），「全新设备 + 自洽 medusa」三轮全部成功。最终仍选择不发，理由从「根因」降级为「降低脆弱性」：它内嵌 device id、必须与 query 同步重算，不一致时返回「HTTP 200 + 空 body」（实测 4/4），而不发它成功率无损失。

### 已排除的假设

| 假设 | 为何排除 |
| --- | --- |
| 签名算法整体过期 | 同签名同 host 的 `multi_video_detail/v1/` 返回 `code:0` 真实数据（本轮实测） |
| Kotlin 只实现 hash branch 0、服务端只认 branch 2 | 本轮实测在 batch 路由上 branch 0 与 branch 2 **都**得到相同的 110001；分支不是判别因素。且固定 JS 实现本身就同时支持 branch 0/2（`branch2F13`/`BRANCH2_SV`） |
| 应用版本号太旧 | 71332/72232/73532/74032 四档 + phoenix/kylin/dragon 三种包名全部 110001（本轮实测） |
| 需要登录态 / Cookie | `multi_video_detail/v1/` 匿名即可成功；外部来源 `zhangbaio/hongguo` 记录「search / multi_video_model 对游客开放，不校验 device_id 是否注册」 |
| 必须走 `device_register` 注册新设备 | 不需要：本轮实测纯随机设备 + 去掉 medusa 即成功。`zhangbaio/hongguo` 另记录离线 `device_register` 必须复刻 native TTEncrypt，明文注册只会拿到 `device_id=0`，属研究级工作量——**本轮结论是根本不需要它** |
| 网页端有隐藏高清接口 | 已 grep 播放页全部 JS 分片，只有 `/video/public/`（本轮实测） |
| 上游已更新钉住的源，升级即可 | 钉住副本与上游 `22261ad`、`4cf38b9` 三方逐字节相同（本轮实测） |
| `_rticket` 缺失/偏移有问题 | 外部来源（父级 agent）实测固定/随机设备、去 `_rticket` 均 110001；本轮实测 `x-khronos` 回拨亦无差别 |

## 候选方案

### 方案 A（最小改动，推荐）：保留现有 Signer，只去掉 `x-medusa` + 随机设备指纹 + 换路由

**做法**
1. `Signer.kt` 的 header 里**不再发 `x-medusa`**（`multi_video_detail` 等仍需要它的路由，如需保留可做成按路由开关）。
2. `VendorConstants.kt` 把 `device_id`/`iid` 改为**每次安装生成一次的随机 19 位数字**并持久化（`onedrama` 的 `NewDeviceID()` 用 `1e18 + rand % 8e18`，可作参照口径）。
3. `ContentRepository.kt` 把取流路由换成 `/novel/player/multi_video_model/preload/v1/`，payload 保持 `video_platform: 1024`、`mixed_video_id_map: {"1004":[vid]}`；旧的 `/v1/` 路由可留作兜底。

**改动面**：`Signer.kt`、`VendorConstants.kt`、`ContentRepository.kt` 三处；`MediaCrypto.kt` 的密钥派生**不需要改**（本轮实测 1080p 档 `encrypt_info.spade_a` 仍走同一口径）。

**依据**：本轮实测矩阵中「全新设备 + 去 medusa + preload」成功返回 5 档并 ffprobe 出 1080x1920 HEVC。

**风险**：低。`x-medusa` 是唯一被证实有害的头；`x-ladon`/`x-argus`/`x-helios` 单独发送无害（外部来源父级 agent 的矩阵，本轮实测中它们与全套成功率无冲突）。设备随机化会失去「经过注册的设备」的历史，`zhangbaio/hongguo` 提示同 IP 大批量随机设备会撞速率墙——客户端单用户单设备场景影响很小，但**同一设备 id 必须持久化复用**，不要每次请求都换。

**验证方式**：单元测试固定「header 集合不含 `x-medusa`」；集成测试断言 preload 返回的 `video_list` 长度为 5 且含 `1080p`；真机播一集并确认 `ffprobe` 档位。

### 方案 B：改用 `onedrama` 的简化 signer

**做法**：照搬 `hongguo/sign.go` 的 `SignRequest`——只发 `X-Gorgon`/`X-Khronos`/`X-SS-Req-Ticket`/`X-SS-STUB`，不发 ladon/argus/helios/medusa。其 gorgon 是固定 20 字节 key 异或 + `RotateLeft8`/`Reverse8`，输出 `8404 401c` 前缀，与现有实现完全不同。

**改动面**：只动 `Signer.kt`（新增一套简化签名），`ContentRepository.kt` 仍需按方案 A 换路由。

**依据**：外部来源父级 agent 实测该简化 signer 在 preload 路由成功拿到五档；本轮核对了 `onedrama/hongguo/sign.go` 原文，确认它确实不发送 ladon/argus/helios/medusa。

**风险**：中。等于替换整套签名，`multi_video_detail`/搜索等其它依赖现有签名头的路由会一起受影响，需要回归；且该实现偏简单，长期稳定性未知。

**验证方式**：与方案 A 相同，另加搜索/详情路由的回归。

### 方案 C（可选）：修好 `x-medusa` 而不是去掉

把 `buildMedusa()` 内部对模块级 `DEVICE` 常量的引用改为使用本次请求的实际设备，使 medusa 报文与 query 自洽。**后续实测已证明「自洽的 medusa 会被接受」**（4b 一致性矩阵：全新设备 + 自洽 medusa 三轮全 OK），所以这条从「无法验证」升级为「可行」。

不建议选它的理由是**收益为零、风险为正**：不发 medusa 成功率完全相同（同样三轮全 OK），而保留它就要长期维护「medusa 与 query 设备同步」这一不变量，一旦漏改就退化成「HTTP 200 + 空 body」这种最难排查的失败形态。除非将来有证据表明某些路由**要求** medusa，否则不值得引入这个维护负担。

## 建议的下一步

1. ~~**先固化方案 A**~~ **已完成**：preload 路由、去 `x-medusa`、设备指纹随机化均已落地，并补了断言「header 集合不含 `x-medusa`」「指纹非固定值」「指纹可持久化复用」的单元测试（core 95 项全通过）。
2. ~~**确认设备指纹持久化**~~ **已完成**：新增 `DeviceFingerprint`（`restore`/`current`），由 `Library` 在启动加载时从 SharedPreferences 恢复并回写。注意这里修掉了一个实现缺陷：最初的版本是**按进程**生成指纹，等于每次冷启动都伪装成全新设备——正是本文档引用的「同 IP 灌入大量随机设备会被静默风控」那种模式。指纹必须**按安装**持久化，不能按进程生成。
3. **回归 `multi_video_detail`**：确认去掉 `x-medusa` 后详情路由（`code:0`）仍正常；若异常，把 medusa 改成按路由开关。**尚未做**——但注意详情页目前走的是网页 `_ROUTER_DATA`，不是该接口。
4. **搜索路由单独跟踪**：`/reading/bookapi/search/tab/v` 换 host/参数/POST 仍为空 body（本轮实测：HTTP 200、0 字节，与 `video_model/v1` 同一形态）。App 已有网站搜索兜底，不阻塞播放，但属于同一类「签名路由静默停供」问题，建议单开一条调研。
5. **最低成本的后续实验（如果 preload 也失效）**：在 preload 路由上枚举 `biz_param.video_platform`（已知只有 1024 收）与 `mixed_video_id_map` 的键（1004 vs 1010），并核对该路由是否出现新的 `*_model/preload/vN` 版本；`video_model/preload/v1` 已确认 404，说明 preload 只在 multi 系列下存在。
6. **真机验收**：本文所有结论来自 JVM/Node 侧对真接口的实测（`quality=1080P`、ffprobe 确认 HEVC 1080x1920），**尚未在实体电视上验证播放**。HEVC 1080P 对低端 Android 8 芯片的解码压力明显高于 360P，实体电视上需确认能硬解；必要时在设置里保留 720P 上限。

### 工作区当前状态

本文档写作后，修复已落地并验证。改动文件：`ContentRepository.kt`（preload 路由）、`Signer.kt`（不发 `x-medusa`，设备按请求解析）、`VendorConstants.kt`（指纹按安装生成 + `adopt`）、`DeviceFingerprint.kt`（新增，持久化边界）、`Library.kt`（启动时恢复并回写指纹），以及对应测试。HEAD 仍为 `2322b3b Fix comic and phone search fallbacks`。

## 来源

**本轮实测（我自己运行的命令/请求）**

- 固定实现的签名内部函数加载方式：`server/vendor/hongguo.cjs` 经 `vm.runInContext` 暴露 `DEVICE`/`xGorgon`/`buildMedusa`/`branchOf` 等，见 [hongguo.cjs](work/hongguoTV/server/vendor/hongguo.cjs)。
- 钉住副本的来源与许可证说明：[NOTICE.md](work/hongguoTV/server/vendor/NOTICE.md)。
- 上游 `drpys` 钉住版本：[22261ad](https://github.com/woshishiq1/drpys/commit/22261adfa31435e3b3ef5a730b8a2a75bcaf1715)、当前 `main` [4cf38b9](https://github.com/woshishiq1/drpys/commit/4cf38b9fa278ca2920c3465b67234f27111337d1)（`git ls-remote` + 浅克隆 `git log`，2026-10-12）；对应源文件 `spider/js/红果果[短].js`。
- 同仓库另一实现（Python，`com.kylin.read/73532`）：`spider/py/红果漫剧.py`，取流路由 `HOST + '/novel/player/multi_video_model/v1/'`。
- 网页播放页与其 JS 分片：[player 页](https://hongguoduanju.com/player/7686713195246930968/7686715217652223038)、`https://lf-fe.fqnovelstatic.com/obj/novel-fanqie-fe/growth/incentive-h5-monorepo/apps/hongguo/static/js/player.be6c1b8992.js`。
- ffprobe 输出的对象是 `main_url` 指向的 1080p 档（`qznovelvod.com`）前 1 MiB 分片。

**外部来源（我核对了原文，但未在本项目内运行其代码）**

- [yhoops/onedrama](https://github.com/yhoops/onedrama) commit [`6905554a2c5da06c345776ff594d34735a29fad5`](https://github.com/yhoops/onedrama/commit/6905554a2c5da06c345776ff594d34735a29fad5)（2026-10-15 取到，经 `codeload.github.com` 打包下载后本地解压阅读）：
  - [`hongguo/media.go`](https://github.com/yhoops/onedrama/blob/6905554a2c5da06c345776ff594d34735a29fad5/hongguo/media.go)：`appVideoModelPath = "/novel/player/multi_video_model/preload/v1/"`、`appVideoPlatform = 1024`、注释记录两条 `/v1/` 路由 2026-10 起空 body。
  - [`hongguo/sign.go`](https://github.com/yhoops/onedrama/blob/6905554a2c5da06c345776ff594d34735a29fad5/hongguo/sign.go)：`SignRequest` 只发 `X-Gorgon`/`X-Khronos`/`X-SS-Req-Ticket`/`X-SS-STUB`；`NewDeviceID()` 随机 19 位。
  - [`hongguo/client.go`](https://github.com/yhoops/onedrama/blob/6905554a2c5da06c345776ff594d34735a29fad5/hongguo/client.go)：query 里的设备字段与 `_rticket` 组装方式。
  - [`hongguo/crypto.go`](https://github.com/yhoops/onedrama/blob/6905554a2c5da06c345776ff594d34735a29fad5/hongguo/crypto.go)：`spade_a` 内容密钥派生口径。
  - 上游 issue（父级 agent 提供，本轮 GitHub 网页不可达未能自行打开）：[N3urda/hongguoTV#17](https://github.com/N3urda/hongguoTV/issues/17)。
- [zhangbaio/hongguo](https://github.com/zhangbaio/hongguo)（本地浅克隆 HEAD `5f8a58d10f48954bc9b6a58c9cd6418bf499feb9`，2026-07-15）：`docs/设备池与device_register侦察-20260629.md` 记录「游客可用、无需登录」「随机新设备会撞速率墙」「机型/UA 必须自洽」「`klink_egdi` 不可省」「离线 device_register 需复刻 native TTEncrypt，明文注册 device_id 恒为 0」。
- [huangxd-/danmu_api](https://github.com/huangxd-/danmu_api) HEAD `f47b786876f2ab51ef69518e5e0b1c0574664589`（2026-10-11）：`danmu_api/sources/hongguo.js` 使用 `POST /novel/player/multi_video_detail/v1/` 取分集，与本轮实测可用的详情路由一致。
- [zhenyong97/hongguo-downloader](https://github.com/zhenyong97/hongguo-downloader) HEAD `85e024dba5e4187fbbc20ddcb173ba367a9840d7`（2026-09-24）：`红果视频刷访问量/capture/probe-paths.js` 列举 `multi_video_model/preload/v1` 等路径与 payload，仍是旧口径。
- 父级 agent 提供的实测矩阵（本机同环境）：固定实现去 `x-medusa` 成功、`onedrama` 简化 gorgon 成功、单独发 ladon/argus/helios 无害、带 `x-medusa` 三轮稳定失败；以及 `device_id`/`iid` 交叉复测确认是「这一对组合」被标记而非单个值。

**本项目内已存在的相关代码**

- 签名与 header 组装：[Signer.kt](work/hongguoTV/kotlin-tv/core/src/main/kotlin/com/hongguotv/core/Signer.kt)、[AesV3.kt](work/hongguoTV/kotlin-tv/core/src/main/kotlin/com/hongguotv/core/AesV3.kt)、[VendorConstants.kt](work/hongguoTV/kotlin-tv/core/src/main/kotlin/com/hongguotv/core/VendorConstants.kt)。
- 路由与 payload、清晰度选档：[ContentRepository.kt](work/hongguoTV/kotlin-tv/core/src/main/kotlin/com/hongguotv/core/ContentRepository.kt)。
- 内容密钥与解密：[MediaCrypto.kt](work/hongguoTV/kotlin-tv/core/src/main/kotlin/com/hongguotv/core/MediaCrypto.kt)。
- 固定签名测试向量：[signing.json](work/hongguoTV/kotlin-tv/core/src/test/resources/signing.json)。
- 此前一轮接口调研：[2026-09-21-hongguo-open-source.md](work/hongguoTV/docs/research/2026-09-21-hongguo-open-source.md)。
