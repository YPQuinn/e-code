# Agent 基座设计

## 目标

用 JDK 21 和 Spring Boot 开发 Agent 基座。设计思想参考 pi（`~/Workspace/source-code/pi`），不照搬。

## 已确定

### 能力模型

- Agent = model + tool。
- tool 是唯一的能力抽象。
- MCP 和 skill 是 tool 的实现，与内置 tool 并列。
- Agent 循环只依赖 tool 接口，不关心 tool 的来源。

### 协议与技术栈

- 对外协议：ACP（Agent Client Protocol）。
- 运行环境：JDK 21。
- 框架：Spring Boot。

### Skill

1. 基座在系统提示词中注入 skill 清单。每个 skill 包含 name 和 description。
2. 基座只提供 1 个 `skill` tool。模型用它加载 skill 正文。不提供 `skill-search`。
3. 清单带强触发规则：
   - 用户点名某个 skill 时，模型必须使用该 skill。
   - 任务明显匹配某个 description 时，模型必须使用该 skill。
4. 清单有预算，超预算时分级降级：
   1. 截断 description。
   2. 如果仍超预算，丢弃全部 description，只保留 name，并向用户告警。
5. 用户输入 `$name` 时，基座直接加载该 skill，不经过模型判断。
6. skill 带 `disableModelInvocation` 标记时，不进清单，只能由用户显式触发。
7. 预算数值与 codex 相同（`codex-rs/core-skills/src/render.rs`）：
   - 清单总预算：上下文窗口的 2%，保底 8000 字符。
   - 单条 description：最多 1024 字符。

### ACP

1. 协议版本先支持 v1。v2 仍是 draft，稳定后再加。协议细节隔离在适配层。
2. 文件和命令 tool 只做本地实现，不使用 v1 的 `fs/*` 和 `terminal/*` 客户端方法。
3. 协议层采用官方 `agentclientprotocol/java-sdk`（`acp-spring-boot-starter`）。基座需要 Spring Boot 4.1。

### MCP 客户端

1. 采用官方 MCP Java SDK（`io.modelcontextprotocol.sdk:mcp`）。不使用 Spring AI MCP。
2. 基座自己写 `McpTool` 适配类。
3. 命名规则：`mcp__<server>__<tool>`，非法字符换成 `_`，超过 64 字符时加哈希后缀。
4. 输出截断：超过 20 KB 时保留头尾，截掉中间。
5. 每个 ACP 会话创建并关闭自己的 MCP 客户端。
6. MCP 来源只用客户端在 `session/new` 传入的 `mcpServers`，暂不支持本地配置。
7. `@AcpAgent` 必须声明 `mcpHttp = true`，否则客户端不会传 HTTP 类型的服务器。
8. 以后增加本地配置时，沿用主流做法：本地和客户端名单合并，同名时客户端优先。改动只在 `@NewSession`。

### 线程模型

1. 采用 ACP SDK 的同步 API。JDK 21 下，SDK 为每次 `@Prompt` 调用分配一个虚拟线程。
2. 基座代码用 `ReentrantLock`，不在 `synchronized` 块内执行阻塞调用。JDK 21 至 23 中，该写法会占住载体线程。
3. 并行 tool 执行使用虚拟线程的 `ExecutorService`。不使用预览特性 `StructuredTaskScope`。
4. 必须设置 `spring.threads.virtual.enabled=true`。Spring Boot starter 默认不启用虚拟线程，此时 handler 运行在 8 个线程的平台线程池上，会限制同时处理的 prompt 数量。

### 模型层

1. 自建薄 `Model` 接口，直接使用官方 SDK。不使用 Spring AI 的 `ChatModel`。
2. `Model` 接口只声明 tool（名称、描述、参数模式）。tool 的执行在 Agent 循环。
3. 协议和服务商分开：一个协议实现可以对应多个服务商，差别只在 `baseUrl` 和认证。
4. 流式接口返回阻塞的事件流，与同步 API 一致。事件类型参考 pi：文本增量、思考增量、tool 调用增量、结束或错误。
5. 首批实现 2 个适配类：
   - Anthropic Messages，使用 `com.anthropic:anthropic-java`。
   - OpenAI 兼容接口，使用 `com.openai:openai-java`。
6. 以后需要 Bedrock 或 Google 服务商时，再加适配类。

### Agent 循环

1. 权限决定 `allow_always` 和 `reject_always` 只在会话内有效，不跨会话持久化。
2. 上下文压缩暂不做，作为独立话题以后设计。上下文超过窗口时，循环返回错误。
3. 循环不依赖 ACP 类型。循环只使用 3 个自己的接口：`AgentEvents`、`PermissionGate`、`CancelToken`。ACP 适配层实现这 3 个接口，并负责把事件映射为 `session/update`。事件名称使用循环自己的语义，不照搬 ACP。
4. `maxTurns` 可选，默认不限制，设置时必须大于 0。超出时返回 `max_turn_requests`。

5. 消息模型自建，含思考签名。消息分 4 类：系统、用户、助手、tool 结果。助手消息的内容块包括文本、思考、tool 调用。
6. tool 并行采用读写锁模型（参考 codex）：
   - 一批调用全部启动，每个调用在一个虚拟线程里运行。
   - 并行安全的 tool 持读锁，其余持写锁。写锁与所有其他调用互斥。
   - 使用 `ReentrantReadWriteLock`。
   - 并行安全由 ACP 的 `ToolKind` 推导：读、搜索、获取为并行安全，其余不是。不增加单独的标志。
   - MCP tool 带 `readOnlyHint = true` 时，映射为读类。
   - 校验和权限请求按调用顺序依次执行。
   - 结果按原调用顺序追加到历史。
7. 权限被拒绝时，只让被拒的调用失败，生成一条错误结果交给模型。如果用户取消整个回合（`request_permission` 返回 `cancelled`），全部终止。
8. tool 失败（抛出异常、未知 tool、参数校验失败）转成错误结果交给模型，循环继续。
9. 回复因长度截断且含 tool 调用时，全部调用判失败，不执行。

### 会话与持久化

1. 存储：每会话一个追加式 JSONL 文件，路径为 `<根目录>/sessions/<cwd 编码>/<sessionId>.jsonl`。第一行是头部，含 `id`、`version`、`createdAt`、`cwd`。
2. 写入：每条消息完成时追加一行并 flush，不做 `fsync`。整机掉电时可能丢失最后几条消息，进程崩溃不丢失。
3. ACP 方法：支持 `session/load`、`session/resume`、`session/list`。`session/list` 按目录列出文件并读取头部，暂不建索引。
4. 并发：内存中用 `sessionId → Session` 的并发映射。每个会话同时只允许一个活动回合，第二个 `session/prompt` 返回错误。不同会话完全独立。
5. 系统提示词随会话保存，恢复时沿用（参考 codex 和 grok-build）。skill 清单在恢复后发生变化时，用提醒消息告知模型，不改写已保存的提示词。

6. 保存范围：消息、模型 ID、用量、tool 的标题与 `kind`、系统提示词。不保存 MCP 配置和分支树。
7. 系统提示词作为头部之后的第一条 system 消息保存，与 grok-build 一致。
8. 头部保存创建时的 skill 名称快照。恢复时与当前清单比较，有差异就追加一条提醒消息，内容是新增和移除的 skill。
9. 易变信息（日期、工作目录）放进消息，不放进系统提示词。

### 内置 tool

1. 内置 tool 共 8 个，集合与 pi 的 7 个 tool 相同，另加 `skill`：

   | tool | ACP `kind` | 并行安全 | 说明 |
   | --- | --- | --- | --- |
   | `read` | `READ` | 是 | 按行读取，支持偏移和行数限制 |
   | `grep` | `SEARCH` | 是 | 正则或字面量搜索 |
   | `find` | `SEARCH` | 是 | 按模式查找文件 |
   | `ls` | `SEARCH` | 是 | 列目录 |
   | `skill` | `READ` | 是 | 加载 skill 正文 |
   | `write` | `EDIT` | 否 | 整文件写入 |
   | `edit` | `EDIT` | 否 | 精确文本替换 |
   | `bash` | `EXECUTE` | 否 | 执行命令 |

2. 暂不做：网页搜索和获取、计划、子 Agent、LSP、向用户提问、图片输入。以后单独讨论。
3. `edit` 采用 pi 的精确替换，不用 `apply_patch`：
   - 参数是 `edits[]`，每项含 `oldText` 和 `newText`。
   - `oldText` 必须在原文件中唯一匹配。多处修改各自对照原文件匹配，互不重叠。
4. 输出上限沿用 pi：最多 2000 行或 50 KB，先到者为准。`bash` 保留末尾部分。超出时，完整输出写入临时文件，并把路径告诉模型。MCP tool 的上限是 20 KB，两者独立。
5. 同一文件的并发写入由读写锁保证：写 tool 持写锁，与所有其他调用互斥。不实现 pi 的 `file-mutation-queue`。
6. `bash` 默认超时 120 秒。模型可以用 `timeout` 参数指定。超时或取消时，终止整个进程树。
7. 路径权限：
   - `read`、`grep`、`find`、`ls` 在 `cwd` 内无需确认。路径落在 `cwd` 之外时，先请求权限。
   - `write` 和 `edit` 对任何路径都按 `EDIT` 类请求权限。
   - 判断路径是否在 `cwd` 内之前，先用 `realpath` 解析符号链接，再比较前缀。
   - 目的：防止文件内容中的提示词注入诱导模型读取 `~/.ssh/id_rsa` 或 `.env`，再把内容发给模型服务商。

### 项目与构建

1. 构建工具：Maven，使用 `mvnw`。
2. `groupId`：`io.agentic.pplee`。
3. 传输方式：同时支持 stdio 和 Streamable HTTP。
4. 内置 tool 见「内置 tool」一节。
5. 模块划分为 6 个：`core`（含内置 tool）、`mcp`、`model-anthropic`、`model-openai`、`acp`、`app`（含自动配置）。依赖只能向下，`core` 不依赖任何内部模块，同一层的模块互不依赖。这些规则用 ArchUnit 测试强制。以后需要嵌入时，再从 `app` 拆出自动配置和 starter。
6. `artifactId` 前缀：`ecode-`。
7. HTTP 安全规则：默认只绑定 `127.0.0.1`。绑定其他地址时必须配置认证，否则启动失败。
8. HTTP 模块采用 `acp-http-servlet`。默认配置 `server.address=127.0.0.1`，用 Spring Security 保护 `/acp`。以 stdio 启动时必须设置 `spring.main.web-application-type=none`。
9. 包路径：`io.agentic.pplee.ecode`。

## 待确定

### 会话与持久化细节

- 恢复时 tool 集合由客户端重新传入的 `mcpServers` 决定。历史里引用已不存在的 tool 时，模型服务是否接受，待核对。实现前必须验证。

### 模型层细节

- Jackson 共存：Anthropic 和 OpenAI 的 SDK 使用 Jackson 2（`com.fasterxml`），ACP SDK 和 Spring Boot 4.1 使用 Jackson 3（`tools.jackson`）。两者包名不同，可以共存，但项目创建后必须用依赖树确认。
- OpenAI 兼容服务商的非标准字段，例如思考内容，是否能通过 `openai-java` 读取。
- `openai-java` 的 `baseUrl` 在构建器上的设置方式。
- 取消：`Model.stream` 目前没有取消参数。实现 `CancelToken` 时决定：由循环从另一个线程关闭流，还是把 `CancelToken` 传入 `stream`。关闭流的做法需要先验证 Anthropic 和 OpenAI SDK 在阻塞读取时被关闭的行为。

### ACP 细节

- 权限拦截的位置：建议放在 Agent 循环，不放在 tool 实现里。
- 权限请求超时：SDK 向客户端发请求的默认超时是 60 秒，需调大。配置项是 `spring.acp.agent.request-timeout`，具体数值待定。
- 其他可配置项：`spring.acp.agent.cancel-grace-period`（默认 60 秒）和 `spring.acp.agent.max-prompt-duration`（默认不限制），数值待定。

### 项目骨架

- Streamable HTTP 传输下，本地 tool 作用于服务器而不是用户机器，需确认使用场景。

## 参考

| 来源 | 内容 |
| --- | --- |
| pi `packages/coding-agent/src/core/skills.ts` | `formatSkillsForPrompt`，`<available_skills>` 块，`disableModelInvocation` |
| codex `codex-rs/core-skills/src/render.rs` | 清单预算，分级降级，触发规则文本 |
| `agent-client-protocol` `docs/protocol/v1`、`v2` | ACP 规范，v2 迁移指南在 `v2/migration.mdx` |
| pi `packages/agent/src/agent-loop.ts` | Agent 循环，事件接口，tool 顺序与并行执行 |
| codex `codex-rs/core/src/tools/parallel.rs` | 读写锁实现的 tool 并行 |
| `grok-build` `crates/codegen/xai-grok-shell/src/session/acp_session_impl/tool_calls.rs` | ACP 会话中的 tool 批量执行 |
| `java-sdk` `acp-agent-support/README.md` | `@AcpAgent` 注解模型 |
| `java-sdk` `acp-spring-boot-autoconfigure/README.md` | Spring Boot 集成，stdio 配置 |
