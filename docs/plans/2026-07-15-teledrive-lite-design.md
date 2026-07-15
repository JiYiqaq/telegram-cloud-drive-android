# TeleDrive Lite 设计说明

日期：2026-07-15
状态：维护者需求已给出完整验收标准，按该标准直接实施

## 1. 目标与边界

TeleDrive Lite 是一个原生 Android 客户端。它只调用 Telegram 官方 Bot API，把用户自己的私人频道作为加密对象存储；应用不登录 Telegram 用户账号，不使用 `api_id`、`api_hash`、MTProto、自建服务器、广告或分析 SDK。所有文件名、目录名和文件内容只出现在本地明文状态或加密索引的明文对象中，发送到 Telegram 的文件块只使用随机 UUID 派生的名称。

跨设备目录恢复不能依赖扫描频道历史。云端唯一目录真相是频道最新置顶的 `teledrive_index_v1.bin`，Room 只保存缓存、传输任务和待处理操作。每次正式修改都在本地形成候选状态，成功上传、置顶并复核新索引后才成为稳定状态。

## 2. 方案选择

考虑了三种结构：

1. 单 `app` Gradle 模块、按包分层。这是本版本采用的方案；它能清晰隔离 UI、网络、数据库、加密、索引和传输，同时降低 0.1 alpha 的构建与依赖复杂度。
2. 多 Gradle 模块 Clean Architecture。隔离更强，但会显著增加插件配置、跨模块模型映射和构建时间；待 API 稳定后再拆分更合适。
3. 单体原型。开发较快，但会让 `Activity`/`ViewModel` 直接依赖网络和数据库，无法满足测试、安全与维护要求，因此不采用。

应用使用手写 `AppContainer` 注入依赖，不引入 DI 框架。所有阻塞 I/O 通过协程在 `Dispatchers.IO` 或 WorkManager 中运行；界面只依赖 Repository 暴露的 `Flow`/`StateFlow`。

## 3. 模块与职责

- `app`：`TeleDriveApplication`、`AppContainer`、进程级会话。
- `ui`：Material 3 Compose 页面、对话框、列表、主题及可访问性文本。
- `navigation`：类型安全程度可控的路由常量和 NavHost。
- `model`：不依赖 Telegram JSON 或 Room 注解的领域状态。
- `database`：七类实体、DAO、数据库、显式迁移注册表和事务。
- `repository`：配置、文件目录、传输和索引的业务编排。
- `telegram`：Bot API DTO、统一错误、重试策略和流式 HTTP。
- `crypto`：PBKDF2、AES-256-GCM、数据密钥包装、随机数和敏感字节清理。
- `transfer`/`worker`/`service`：上传下载管线、WorkManager、前台通知和取消。
- `index`：索引模型、序列化、二进制加密信封、迁移、同步与恢复。
- `storage`：SAF 元数据、流、临时文件和安全复制。
- `settings`：Keystore 凭据、会话主密钥、主题及非敏感偏好。
- `util`：SHA-256、名称校验、大小/速度格式化、诊断脱敏。

## 4. 密钥与加密格式

同步密码不持久化。首次初始化生成随机 KDF salt，并用 PBKDF2-HMAC-SHA256 派生 256 位主密钥；算法名、salt、迭代次数和输出长度放入索引信封的非敏感头部，也复制到加密索引正文，便于升级校验。设备会话可把派生主密钥再用 Android Keystore AES-GCM 密钥包装后保存；退出时删除包装值和 Keystore 别名。

每个文件生成独立 256 位数据密钥。每个块使用 96 位随机 nonce 和 AES-256-GCM（128 位 tag），AAD 绑定格式版本、file UUID 和块序号。数据密钥由主密钥用独立 nonce 包装；数据库和索引都不保存明文数据密钥。每次加密 API 生成新 nonce，调用方不能传入或重用 nonce。

加密索引采用版本化二进制信封：magic、信封版本、KDF 标识、迭代次数、salt 长度及 salt、nonce 长度及 nonce、密文长度及 AES-GCM 密文。未知版本在解密或写库前拒绝。AES-GCM 认证失败统一返回“同步密码错误、数据损坏或加密认证失败”，且不会覆盖本地数据库。

## 5. 上传、下载与完整性

上传 Worker 通过 ContentResolver 流式读取 URI，以 64 KiB 缓冲更新原文件 SHA-256，并按可配置上限切成默认 18 MiB 的块。每块边读边加密到应用缓存临时文件，上传完成立刻删除临时文件；原文件从不整体载入内存。块消息成功后立即保存 message id、file id、nonce 和大小，但文件仍不是正式索引成员。全部块完成且新索引确认置顶后，文件才变为 `AVAILABLE`。

下载 Worker 按块顺序调用 `getFile` 并流式下载密文。每块先在缓存中完成 GCM 验证，再追加到重建临时文件；所有块完成后校验 SHA-256，只有校验成功才复制到用户选择的 SAF URI。失败或取消不会把不完整输出标记为成功，并清理应用临时文件。

WorkManager 使用联网约束和唯一串行上传队列，默认同一时间一个上传。前台通知显示文件名、字节进度、块进度和速度，并提供系统取消 PendingIntent。429 只按 `retry_after` 等待；非幂等上传不做客户端无条件重发。

## 6. 索引原子更新与恢复

索引同步步骤是：读取稳定 Room 快照，revision 加一，序列化并加密，上传候选索引，验证返回文档，置顶候选消息，再调用 `getChat` 确认 `pinned_message.message_id`。确认后才写入新的稳定 message id，并尝试取消置顶/删除旧索引。旧索引清理失败只记录待处理操作，不能回滚或破坏新索引；确认前失败则保留旧索引。

恢复先读取置顶消息并验证文件名，再下载信封头、派生主密钥、解密和完整反序列化。所有 UUID、父子引用、块序号、版本和 revision 通过验证后，Room 才在单个事务中替换缓存。错误密码、损坏或不支持版本均不会清空现有数据。

Bot API 无法可靠遍历完整频道历史，因此孤立块清理只处理本地/索引中已记录的 message id；无法发现完全丢失元数据的历史消息会列为已知限制。

## 7. 文件管理与删除

目录树以 root UUID 为起点。移动目录前沿父链检测目标是否为自身或后代，所有更新在 Room 事务内完成。搜索按名称匹配文件和目录；排序支持名称、大小、修改时间及正倒序。批量操作逐项记录结果，避免一个失败隐藏其他结果。

删除先把文件置为 `DELETING`，保留全部块元数据并逐条删除 Telegram 消息。全部成功后才生成不含该文件的新索引；部分失败则保留未删除 message id 并标记 `PARTIALLY_DELETED`，用户可重试。递归目录删除复用同一管线并要求明确确认。

## 8. 界面与安全体验

视觉方向是“安静的加密档案柜”：深蓝灰背景、青绿色安全强调色、圆角卡片和清晰的状态色，不使用 Telegram 或 OpenAI 标志。Compose 页面覆盖启动、首次配置、首页/文件夹、文件详情、上传、下载、队列、搜索、设置、恢复、关于和教程；浅色、深色与跟随系统均可用。

首次配置和恢复页面启用 `FLAG_SECURE`。密码字段默认隐藏，提供强度提示和不可恢复警告。错误都映射为简体中文用户消息；日志、异常、诊断导出不包含 token、完整 chat id、频道名、文件 URL、密码或密钥。

## 9. 测试与验收

纯 Kotlin 单元测试覆盖密码派生、GCM、篡改、nonce、数据密钥包装、分块边界、空文件、SHA-256、索引版本/revision/加密和目录环检测。MockWebServer 覆盖成功、HTTP 错误、`ok=false`、429、超时及 token 脱敏。Room DAO 和关键 Android 行为使用 AndroidX instrumented tests；无设备环境下不会把未运行的仪器测试宣称为通过。

完成前运行 `gradlew.bat test`、`lint`、`assembleDebug`，再从已提交仓库进行无本地配置的干净克隆构建。敏感信息扫描同时覆盖跟踪文件和 Git 历史。真实 Bot、频道权限、通知、SAF、进程被杀恢复和多设备冲突仍需维护者在专用测试账号/设备上人工验证。
