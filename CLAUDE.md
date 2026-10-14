# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概览

LeXiForge 后端：面向 IT 从业者的 AI 翻译与英语学习平台（Spring Boot 4 / Java 21 / Spring AI 2.0.1 / PostgreSQL + Flyway）。前端是 `backend/frontend/` 下的 Vue 3 + Vite 项目（注意：前端位于 backend 目录内，不在仓库根）。

## 常用命令

```powershell
# 在 backend 目录下执行
mvn test                                        # 全部测试
mvn test -Dtest=TranslationReuseTest            # 单个测试类
mvn test -Dtest=WordDictionaryTest#方法名       # 单个测试方法
mvn spring-boot:run "-Dspring-boot.run.profiles=dev"        # 不带 AI 启动
mvn spring-boot:run "-Dspring-boot.run.profiles=dev,local"  # 带 AI 启动（需 application-local.yml）

# 前端（backend/frontend 目录下）
npm run dev       # http://localhost:5173，Vite 将 /api 代理到 localhost:8080
npm run build
```

现有测试主要是 JUnit 单元/本地 HTTP 协议测试（无 `@SpringBootTest`、不连数据库），`mvn test` 不需要本地 PostgreSQL。`SchemaContractTest` 以文本方式断言 Flyway 迁移 SQL 的内容契约。

## Profile 约定

- `dev` — 开发订阅接口（`DevelopmentSubscriptionController` 仅在此 profile 注册路由）、无限额度、DEBUG 日志。
- `local` — Git 忽略的 `application-local.yml`，唯一存放 AI 网关地址/密钥/模型的地方。已提交的配置中不保留任何 AI provider profile。
- AI 关闭时服务正常启动，AI 接口返回 `AI_NOT_CONFIGURED`（`DemoAiGateway` 在 `app.ai.enabled=false` 时作为兜底实现，所有方法抛异常、不生成演示数据）。

## 架构

分层结构：`controller/` → `service/`（接口）+ `service/impl/`（实现）→ `repository/`（JPA）。DTO 与实体均为 record / 类，位于 `model/`。

### AI 翻译链路（核心，跨多文件理解）

1. `AiTranslationController`（SSE 流式入口 `POST /api/translations/stream/v1`，兼容入口 `POST /api/translations/v1`）。
2. `TranslationServiceImpl.translate()` 的复用顺序：**请求指纹内存缓存 → PostgreSQL 同指纹成功记录 → 真实 AI 调用**（M1 不注入术语映射、不启用跨用户词典；V5 保留兼容）。命中个人结果复用不调 AI、不扣额度；`forceRefresh: true` 跳过前两者。
3. 指纹由 `TranslationCacheService.fingerprint()` 计算，涵盖用户、原文、语言、领域及更新时间、术语表、模型配置和提示词版本。模型/策略调整时提高 `TRANSLATION_CACHE_REVISION` 使旧结果失效。
4. `AiGateway` 是所有 AI 能力的接口（translate/lookup/grade/mnemonic），实现二选一：
   - `SpringAiGateway`（`app.ai.enabled=true`）— 通过 Spring AI 的 OpenAI 兼容客户端调用；`provider-structured-output` 决定用 provider 原生 json_schema 还是 BeanOutputConverter 注入实体结构（DeepSeek 兼容接口不支持 json_schema，用 false）。流式翻译用 `JsonStringFieldDecoder` 边流式解析 `translatedText` 字段边推送 delta。
   - `DemoAiGateway`（默认）— 不可用，抛 `AI_NOT_CONFIGURED`。
5. `TranslationRequestStrategy` 按输入长度选择 token 预算与术语上限（FAST/BALANCED/DOCUMENT），为推理模型预留 512 hidden reasoning headroom。
6. 额度由 `EntitlementService` 原子扣减；模型调用在事务外，结果用短事务（`TransactionTemplate`）持久化，失败释放预占额度。

### 横切约定

- **响应包装**：所有 REST 接口返回 `ApiResponse<T>`（success/code/message/data/fieldErrors/requestId）。业务错误抛 `ApiException(status, code, message)`，由 `GlobalExceptionHandler` 统一转换；`X-Request-Id` 头会被透传或生成。
- **URL 版本**：所有 API 的 `v1` 位于 URL 最后一段（如 `/api/plans/v1`、`/api/translations/{id}/lookups/v1`）。
- **ID**：全业务使用 BIGINT 雪花 ID，由应用内 `SnowflakeIdGenerator` 生成（迁移 SQL 禁止 SERIAL/序列，`SchemaContractTest` 强制）。前端 JSON/TS 中以字符串传输。多实例部署需为每实例分配不同的 `SNOWFLAKE_DATACENTER_ID`/`SNOWFLAKE_WORKER_ID`。
- **安全**：JWT Access（30 分钟）+ 轮换 Refresh Token（30 天），无状态会话。公开路由仅 `/api/auth/**`、`/api/plans/v1`、`/actuator/health`；`/api/admin/**` 需 ADMIN 角色。当前用户经 `JwtAuthenticationFilter` → `CurrentUser` 获取。
- **数据库迁移**：`src/main/resources/db/migration/V<N>__*.sql`，只允许增量（additive）变更，不改历史脚本。`hibernate.ddl-auto=validate`，Flyway 启动时执行。
- **提示词安全**：系统提示词明确声明用户消息中的原文/上下文/术语是待处理数据而非指令（防提示注入），修改提示词时保持这一约定，并考虑对指纹/缓存版本的影响。

## 语言

代码注释、错误消息、提示词和文档均使用中文；用户也以中文交流。保持一致。
