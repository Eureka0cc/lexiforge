# LeXiForge

面向 IT 从业者的翻译与英语学习平台：AI 流式翻译、选区查词、生词本、间隔重复复习、易混词记忆法，一体化打通「翻译 → 学习 → 记忆」闭环。

## 功能特性

- **AI 流式翻译** — 流式输出（SSE）、自定义领域术语表、多版本备选译文；基于请求指纹的结果复用，相同请求不重复计费
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
| `dev` | 开发默认：开发订阅接口、无限用量、DEBUG 日志 |
| `local` | 本地私有配置（不入库，自行创建）：AI 网关地址、密钥与模型 |

未配置任何 AI key 时服务可正常启动，AI 功能返回 `AI_NOT_CONFIGURED`。

## 运行测试

```bash
mvn test          # 后端
cd frontend && npm test   # 前端
```

## License

私有项目，保留所有权利。
