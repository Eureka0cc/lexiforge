# LeXiForge

## 商用化实施（2026-10-14）

完整范围与验收目标见 [开发验收计划](docs/COMMERCIALIZATION_PLAN.md)，当前证据见 [实施台账](docs/IMPLEMENTATION_STATUS.md)。支付只做 Sandbox，真实收费未开放。

默认翻译不注入预设术语映射、不跨用户共享生成结果；个人历史同条件复用保留。普通与流式翻译采用同一语境策略。受控生成并发默认 20、排队 40，上游整体超时 90 秒，可用 `AI_CONCURRENCY`、`AI_QUEUE_CAPACITY`、`AI_TIMEOUT_SECONDS` 调整。

管理员可在 `/actuator/metrics` 查询 `lexiforge.translation.queue`、`lexiforge.translation.first_delta`、`lexiforge.translation.duration` 和 `lexiforge.translation.rejected`。缓存没有首字样本；真实模型质量与速度不能由单元测试推断。

本地 AI 配置在 `dev,local` 下通过外部文件导入。Maven 资源排除 `application-local.yml`，部署须显式提供外部配置。验证命令：后端 `mvn -B clean test`，前端 `npm run test`、`npm run build`。

### AI 场景配置与数据库验收

场景支持一句话生成可编辑草稿、试译及保存复用。数据库 V6 增加配置、乐观锁版本、草稿与历史快照；旧场景保持描述模式，不自动改写。正常翻译不额外生成场景，删除场景不删除历史快照。Free/Pro 每日场景生成额度为 10/50 次；成功试译计入翻译额度，失败释放本次预占。生成和试译均使用有限 AI 执行资源。

- `POST /api/scenes/generate/v1`：`requestId`、`description`，返回草稿及配置。
- `POST /api/scenes/preview/v1`：草稿名称、描述、配置和原文/语言，返回带场景快照的翻译历史。
- 场景 CRUD 增加 `configuration`、`expectedRevision`；返回 `revision`。过期编辑返回 `SCENE_VERSION_CONFLICT`。
- 默认关闭全部模型；本地配置显式选择 chat 模型。部署启用文本 AI 时设置 `AI_ENABLED=true`、`AI_CHAT_MODEL=openai`，并提供外部连接配置。语音、图像等未使用模型保持关闭。

真实 PostgreSQL 验收：设置 `LEXIFORGE_TEST_DB_URL` 指向独立的 `lexiforge_test*` 或 `lexiforge_acceptance*` 数据库，以及对应 `LEXIFORGE_TEST_DB_USERNAME/PASSWORD`，执行 `mvn -B -Pintegration verify`。不允许测试连接普通业务库。AI 使用可控 fixture，此测试不证明真实模型质量。

浏览器界面验收：`cd frontend` 后执行 `npx playwright install chromium`、`npm run test:e2e`，覆盖桌面与移动视口。接口 fixture 与数据库集成证据分别记录。

全部开发产物统一放到工作区的 `tmp/`：Maven 输出 `../tmp/backend`，前端构建输出 `../../tmp/frontend-dist`，浏览器报告/截图输出 `../../tmp/playwright`，类型信息与缓存使用对应子目录。目录均相对于各工具的项目目录解析，不提交 Git。真实 AI 小样本报告位于 `../tmp/acceptance`，不含密钥。

### 本地账户恢复

本轮先以本地运行验收，不创建云资源。`dev` 配置默认启用文件邮件模式：在登录页申请找回密码，或在“账户与安全”申请邮箱验证后，后台将测试邮件写入工作区 `tmp/auth-mail/*.txt`。打开文件中的链接即可继续；链接 15 分钟内有效且只能使用一次。保持前端地址与 `FRONTEND_ORIGIN` 配置一致，默认 `http://localhost:5173`。

邮件队列在 PostgreSQL 中加密存储；`AUTH_MAIL_SECRET` 至少 32 个字符，开发默认密钥仅用于本机测试，修改密钥会使既有待发邮件不可解密。`AUTH_MAIL_MODE=disabled` 关闭账户邮件；文件模式只允许 `dev`/`test`。本轮未连接真实 SMTP。

重置密码会立即撤销所有已有访问和刷新会话；退出登录会撤销当前会话及它轮换出的刷新凭据。浏览器认证接口检查 Origin，刷新 Cookie 使用 HttpOnly、SameSite=Lax；本地 HTTP 保持 `AUTH_SECURE_COOKIE=false`，HTTPS 环境才设为 true。新密码至少 8 个字符且 UTF-8 编码不超过 72 字节。

复习 V8 迁移持久化本轮题目与标准答案快照。同题同答案重试返回已保存结果，修改答案的重复提交返回冲突，不重复判分或推进计划。迁移会结束此前未记录题目成员的活动会话，升级后需重新开始一轮；已有学习记录保留。

### 本地 Sandbox 订单

按用户当前范围，仅本地模拟，暂不连接官方支付宝/微信 Sandbox。`dev` 默认启用 `BILLING_MODE=local-sandbox`；“套餐与用量”支持创建月付（30 天）/年付（365 天）订单、模拟支付、手动续费、整单模拟退款、查询和取消。价格和期限由服务端保存快照，前端不直接激活权益。原开发直开订阅接口默认关闭。

V9 将模拟渠道交易与本地订单、通知、权益分别持久化。HMAC-SHA256 验证本地通知，不属于支付宝 RSA/微信官方验签；`BILLING_SECRET` 至少 32 个字符，开发默认值仅限本机测试。支付/退款先提交渠道状态，再通知结算；查询和每 30 秒对账可补偿丢失通知。重复或乱序通知不重复授予权益。

同一账户结算使用数据库行锁。续费接续有效期限；退款后按支付时间顺序重建未退款订单的期限，保留原有有效订阅基线与学习数据。默认配置为 `disabled`，模拟模式只允许 dev/test，支付与退款测试路由也只在这些 Profile 注册。

接口均以 `/api/billing` 开头：`GET /config/v1`、`GET|POST /orders/v1`、`GET /orders/{id}/v1`（查询渠道并结算）、`POST /orders/{id}/cancel/v1`。测试路由为 `POST /orders/{id}/sandbox-pay/v1`、`/sandbox-refund/v1`；`notify=false` 用于丢失通知演练。公开的 `POST /sandbox-notify/v1` 必须提供签名。订单操作校验当前用户所有权。

真实重启验收先执行 `mvn -B -Pintegration verify`，再运行 `./scripts/verify-billing-restart.ps1 -DatabaseUrl jdbc:postgresql://localhost:5432/lexiforge_acceptance_20261014`，按需传 `DatabaseUser/DatabasePassword`。脚本只允许明确命名的本机验收库，隐藏启动三次独立后端进程，验证支付和退款丢失通知后恢复，最后清理测试账户与自身进程。结果、服务日志写入工作区 `tmp/billing-restart/`。

### AI 额度恢复与数据库恢复

V10 的 `ai_usage_reservation` 持久化翻译、场景生成和查词预占。预占与计数在同一事务提交；结果和预占确认在同一事务提交。失败释放幂等，过期恢复退回原计量日期；已经释放的预占不能再保存迟到结果。已确认的结果不会被异常补偿重复退额。复习判分和记忆法仍在各自结果事务中计量，事务失败/进程断开由数据库回滚。

同一用户同一内容的有效预占由数据库唯一索引互斥，租期内阻止跨进程重复发起；过期后允许重试，旧请求的迟到写入被拒绝。正常完成后复用个人历史。预占期限为 `max(120, AI_TIMEOUT_SECONDS + 60)` 秒，默认 150 秒，默认每 30 秒恢复过期项。请求状态清理在更晚的 `max(180, AI_TIMEOUT_SECONDS + 120)` 秒后执行，默认每 60 秒检查。同步翻译、查词、场景、判分及记忆法均使用有限执行资源，不在排队期间预扣额度。

- 真实中断验收：`./scripts/verify-ai-crash.ps1 -DatabaseUrl jdbc:postgresql://localhost:5432/lexiforge_acceptance_20261014`。使用本机 Node HTTP 故障注入服务，观察上游请求和预占后强制结束后端；重启后等待真实时钟过期，不修改期限。仅用于故障恢复，不调用真实模型，也不证明翻译质量。覆盖完整清理期限，运行约 4 分钟。报告/日志写入 `tmp/ai-crash/`。
- 数据库备份恢复：`./scripts/verify-database-recovery.ps1`，按需传 `DatabasePort/DatabaseUser/DatabasePassword`。脚本创建两个全新的随机命名验收库，通过应用生成账户、场景、词库和 Sandbox 订单，补入标注为 fixture 的历史数据。停止写入后实际执行 `pg_dump`/`pg_restore`，比较所有表的行数/全列校验，启动恢复库验证业务并退款。最后仅清理本脚本创建的库与进程；备份、校验清单和报告保留在 `tmp/database-recovery/`。不连接现有业务库。

这些故障恢复检查不替代完整连续负载、真实 AI 20 并发和质量评测，未通过的门槛继续在实施台账跟踪。

### 版本回退演练与 CI

当前已验证的回退目标为 M4 提交 `50f2ea5`。先构建当前 jar 和前端生产包，安装 Playwright Chromium，然后运行：

```powershell
./scripts/verify-version-rollback.ps1 -RollbackRef 50f2ea5
```

脚本将指定提交原始源码导出到 `tmp/version-rollback/`，构建该版本前后端，不修改当前工作区。使用新建恢复验收库，先核对全部表的行数/全列校验，再依次启动当前版、旧版、当前版。保留 V10 数据库迁移；回退前必须排空 AI 请求和预占。验证旧版新增订单、重新升级继续处理该订单，以及三套生产页面读取场景、词库、历史和 Pro。浏览器直接连接真实验收后端，不使用接口 mock。其他回退目标需独立验收。

报告、构建、页面截图和备份均在 `tmp/version-rollback/`、`tmp/database-recovery/`。这是隔离演练入口，不会回退现有业务库；结束后清理本脚本创建的进程和数据库。

GitHub Actions 的 `Verify` 工作流在 main 推送、PR 和手动触发时运行，固定官方 Action 提交版本。覆盖 Java 21、PostgreSQL 16 单元/集成、PowerShell 语法、Node 22 前端单元/构建及桌面/移动浏览器测试。真实模型测试默认关闭；测试报告归档 7 天。Windows 进程管理、真实重启和版本回退由上面的本机验收脚本单独验证，不能由 Linux CI 语法检查代替。

面向 IT 从业者的翻译与英语学习平台：AI 流式翻译、选区查词、生词本、间隔重复复习、易混词记忆法，一体化打通「翻译 → 学习 → 记忆」闭环。

## 功能特性

- **AI 流式翻译** — 流式输出（SSE）、自定义场景、必要的歧义解释；基于个人请求指纹的结果复用，相同请求不重复扣额度
- **选区查词** — 在译文或原文中选中任意词组，AI 解析词义、音标、例句，一键收入词库
- **生词本 + 间隔重复复习** — SM-2 风格调度算法（EN↔ZH 双向），支持本地精确判分 + AI 语义判分级联
- **易混词组** — 归纳易混淆词汇，AI 生成多版本记忆法（口号 / 视觉钩子 / IT 类比 / 对比示例）
- **词包导入** — 预置 IT 领域词包，去重后批量导入
- **订阅与额度** — FREE / PRO 套餐，按日额度控制（翻译 / 查词 / 判分 / 记忆法）
- **管理指标** — 用户数、DAU/WAU、活跃订阅、MRR 估算

## 技术栈

**后端** — Java 21 · Spring Boot 4 · Spring Security (JWT) · Spring AI (OpenAI 兼容网关) · PostgreSQL + Flyway · JPA + JdbcClient

**前端** — Vue 3 · TypeScript · Vite · Ant Design Vue · Pinia

## 目录结构

```
├── src/main/java/com/justinsight/lexiforge
│   ├── controller/      # REST API（含 ai/ 子包）
│   ├── service/         # 业务接口 + impl/ 实现
│   │   └── ai/           # AI 相关服务（网关、缓存、查词、请求幂等）
│   ├── repository/       # JPA 数据访问
│   ├── model/            # 实体与 DTO
│   ├── security/         # JWT 签发 / 过滤器 / 当前用户
│   ├── config/           # 安全、JWT、缓存等配置
│   └── common/           # 通用异常、响应体、雪花 ID
├── src/main/resources
│   ├── db/migration/     # Flyway 迁移脚本
│   └── application*.yml  # 多 profile 配置
└── frontend/             # Vue 3 前端
```

## 快速开始

### 本地启动入口（Windows / PowerShell 7）

先准备 Java 21、Maven、Node/npm 和可连接的 PostgreSQL 数据库；默认连接本机 `lexiforge`，不会创建或替换已有业务数据库。可通过 `DB_URL/DB_USERNAME/DB_PASSWORD` 环境变量指定连接。

```powershell
./scripts/start-local.ps1
./scripts/status-local.ps1
./scripts/stop-local.ps1
```

启动会构建后端，缺少前端依赖时执行 `npm ci`，隐藏启动前后端并等待健康检查。仅监听 `127.0.0.1`，使用输出的前端地址访问；默认端口被占用时自动选择空闲端口，显式指定被占用端口则停止。前端代理与认证 Origin 跟随实际端口。

同配置重复启动复用已运行实例。修改配置或代码后先停止再启动；确认已有 jar 时可用 `-SkipBuild`。`-DisableAI` 用于启动诊断，正常启动保留现有 `dev,local` 私有模型配置。启动入口默认执行真实 Free/Pro 额度，只有显式 `-UnlimitedUsage` 才跳过限制。

运行状态、日志和稳定的开发密钥放在工作区 `tmp/local/default/`；停止保留数据库与密钥，因此重启不会无故注销会话。用 `-InstanceName` 分开管理验收实例。停止时检查项目路径、PID、启动时间、进程类型和命令行，拒绝终止不属于本项目的进程。

可运行 `./scripts/verify-local-runtime.ps1` 验收入口：创建全新随机验收库，使用非默认端口验证代理、认证、重复启动/停止和原会话跨重启，结束后仅清理本脚本实例及验收库。报告在 `tmp/local-runtime-acceptance/`。

### 环境要求

- JDK 21+、Maven 3.9+
- PostgreSQL 15+（空库即可，Flyway 自动建表）
- Node.js 20+（前端）

### 1. 配置环境变量

以下为本机默认值，按需覆盖：

```bash
DB_URL=jdbc:postgresql://localhost:5432/lexiforge
DB_USERNAME=postgres
DB_PASSWORD=postgres
JWT_SECRET=replace-with-at-least-32-random-bytes
```

AI 服务连接信息不入库，写在 `src/main/resources/application-local.yml`（已被 Git 忽略），见下文 Profile 说明。

### 2. 启动后端

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev,local
```

### 3. 启动前端

```bash
cd frontend
npm install
npm run dev
```

访问 http://localhost:5173

## 配置 Profile

| Profile | 说明 |
|---|---|
| `dev` | 本地文件邮件、Sandbox 模拟支付、无限用量、DEBUG 日志；旧直接激活订阅接口默认关闭 |
| `local` | 本地私有配置（不入库，自行创建）：AI 网关地址、密钥与模型 |

未配置任何 AI key 时服务可正常启动，AI 功能返回 `AI_NOT_CONFIGURED`。

## 运行测试

```bash
mvn test          # 后端
cd frontend && npm test   # 前端
```

## License

私有项目，保留所有权利。
