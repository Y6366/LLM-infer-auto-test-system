# 基于powerjob的任务调度实现调

PowerJob = 调度中心 + 执行器 SDK + Web 控制台

## **核心能力：**

- **任务调度**
  
  - 支持 CRON 表达式、固定频率、固定延迟、API 触发等多种调度策略
  
  - 支持一次性任务、周期任务、工作流（DAG）任务。

- **分布式执行**
  
  - 整个集群水平扩展，worker 节点自动分片任务，适合大批量任务、多机器执行。

- **多种任务类型**
  
  - Java Processor（普通 Java 对象 / Spring Bean）
  
  - Shell / Python 脚本（你之后在测试机拉起推理服务就会用到这个）

- **可视化管理**
  
  - Web Console：新建/修改任务、设置调度策略
  
  - 实时查看任务实例状态、执行日志、统计信息

## **基本架构**

- **PowerJob Server（调度中心）**
  
  - 负责：任务管理、调度策略计算、分发任务、记录执行结果。
  
  - 依赖：
    
    - 一个关系型数据库（MySQL / PostgreSQL 等），存任务定义、执行记录。
    
    - 可选 Redis / ZooKeeper 用于集群协调（视版本与模式而定）。

- **PowerJob Worker（执行器）**
  
  - 以 Java 应用的方式集成到你的 Spring Boot / 普通 Java 程序里。
  
  - 与 Server 保持长连接，接收调度指令，执行 Processor（Java / Shell / Python）。
  
  - worker 数量可以水平扩容，任务自动路由到可用 worker。

- **Web Console（管理界面）**
  
  - 通常和 Server 一起部署。
  
  - 你可以在页面上：
    
    - 配置任务名称、描述、处理器类型
    
    - 写入 JobParams（JSON 字符串）
    
    - 设定 CRON / 固定频率 / API 调用等调度方式
    
    - 查看执行历史、重试、终止任务等

## **当前项目典型应用场景：**

- **环境拉起与关闭**
  
  - Shell / Python 任务：在指定测试机上执行 `docker-compose up` 或 systemd 启动脚本。
  
  - JobParams 包含：模型仓库地址、端口、配置文件路径、GPU/Ascend 卡选择等。

- **功能测试批量执行**
  
  - Java Processor：从 DB 取测试用例列表、调用 LLM 服务接口、收集结果、写入测试结果表。
  
  - JobParams：模型 ID、用例集 ID、温度、max_tokens 等。

- **性能测试**
  
  - Shell / Python：调用 JMeter / jmeter-java-dsl / Locust / 自研压测工具。
  
  - JobParams：QPS 目标、持续时长、并发数、数据集路径。

- **日志与结果处理**
  
  - 下游任务：执行完后打包日志（服务日志、测试工具日志、系统监控日志），上传到日志服务器 / MinIO / NAS。
  
  - 或触发一个 Java Processor 将汇总指标写入「测试任务结果表」，供平台页面展示。

## **核心概念：**

### **核心概念-基本实体**

- **App（应用）**
  
  - 对应一个业务系统/工程。
  
  - server 端通过 appName + 密码 注册；worker 端通过配置 appName 接入。

- **Server（调度服务器）**
  
  - powerjob-server 进程，组成一个集群。
  
  - 负责任务管理、调度、工作流执行、日志聚合。

- **Worker（执行器）**
  
  - 嵌入业务应用的 powerjob-worker。
  
  - 从 server 接收 Task，在本地执行 Processor。

- **Job（任务）**
  
  - 你在控制台里“新建任务”得到的东西。
  
  - 描述：名字 / 调度策略 / 执行模式 / 处理器类型及入参等。

- **JobInstance（任务实例，简称 Instance）**
  
  - Job 每被调度一次，就生成一个实例记录。
  
  - 类比「类 → 对象」：Job 是模板，JobInstance 是一次执行。

- **Task（作业）**
  
  - JobInstance 在 Worker 上的执行单元。
  
  - 关系：
    
    - STANDALONE：1 个 JobInstance → 1 个 Task
    
    - BROADCAST：1 个 JobInstance → N 个 Task（每台 Worker 1 个）
    
    - MAP / MAP_REDUCE：1 个 JobInstance → 开发者在 Map 阶段生成的多个 Task

- **Workflow（工作流）**
  
  - DAG（一组 Job + 边），描述任务之间依赖与编排，比如：A → B、A → C → D。

- **WorkflowInstance（工作流实例）**
  
  - 工作流被调度执行后的运行时记录（包含每个节点 Job 的实例情况）。

### **核心概念-基本上下文：**

在 Processor 里你最常用的是 `TaskContext`：

- `jobParams`：**任务级参数**（控制台 / OpenAPI 上配置的 Job 参数），所有实例共享。

- `instanceParams`：**实例级参数**（比如 `runJob(jobId, instanceParams)` 传进去的），一次触发一个值。

- `taskId` / `instanceId`：标识当前 Task / Instance。

- `omsLogger`：写日志给 console 在线查看。

**核心概念-定时策略**

- `API`
  
  - 由 OpenAPI / powerjob-client 的 `runJob` 触发，server **不会自动调度**。

- `CRON`
  
  - 用标准 CRON 表达式（秒、分、时、日、月、周），到点生成实例。

- `FIXED_RATE`（固定频率）
  
  - 每隔 X 毫秒触发一次（类似 `scheduleAtFixedRate`）。

- `FIXED_DELAY`（固定延迟）
  
  - 上一轮执行结束后，延迟 X 毫秒再调度下一轮（类似 `scheduleWithFixedDelay`）。

- `WORKFLOW`
  
  - 该 Job **只由工作流节点触发**，不会被 server 单独调度。

### **核心概念-执行模式**

**executeType** 决定 JobInstance 如何拆成 Task 在集群里跑：

- `STANDALONE`（单机）
  
  - 一次只选一台符合条件的 Worker；1 Instance → 1 Task。

- `BROADCAST`（广播）
  
  - 对集群中所有（或者指定子集）Worker 各派发一个 Task。
  
  - 用于“全机清理缓存 / 日志”等。

- `MAP`
  
  - 你在 Processor 的 Map 阶段动态产生很多 Task，适合大批量数据拆分。

- `MAP_REDUCE`
  
  - 在 MAP 的基础上多一个 Reduce 汇总阶段。
  
  - 适合“全量数据分片计算 + 汇总”的场景（例如做离线统计、批处理）。

## **Job & Instance重要参数**

**基本配置**

- **jobName** / 任务名称

- **jobDescription** / 任务描述

- **jobParams**
  
  - 任务参数，Processor 里通过 `TaskContext.jobParams` 读取。
  
  - 对于工作流中的节点，如果配置了节点参数，会覆盖 Job 的 jobParams。

**定时信息**

- **timeExpressionType**：API / CRON / 固定频率 / 固定延迟 / WORKFLOW（上面讲过）。

- **timeExpression**：结合类型解释：
  
  - CRON：CRON 表达式；
  
  - 固定频率 / 固定延迟：整数毫秒；
  
  - API / WORKFLOW：通常为空。

**执行配置**

- **executeType**：STANDALONE / BROADCAST / MAP / MAP_REDUCE。

- **processorType**：Java / Java(容器) / Shell / Python / HTTP / SQL ...

- **processorInfo**：按类型写类名 / 容器 ID / 脚本 / URL 等。

**运行配置**

- **maxInstanceNum**（最大实例数）
  
  - 同一 Job 同时允许存在的运行中实例数量上限。
  
  - 防止某个任务调度过快、堆积太多执行。

- **threadConcurrency** / **单机线程并发数**
  
  - 主要对 Map/MapReduce 有效，控制单个 Worker 内用几个线程并行跑 Task。

- **instanceTimeLimit** / 运行时间限制
  
  - 单次 JobInstance 允许运行的最大时间，超时视为失败，单位 ms，0 表示不限制（不推荐）。

**重试配置**

- **instanceRetryNum**
  
  - 实例级重试次数：整个 Instance 失败后重试，会更换 TaskTracker（相当于 Master 换机），成本大。

- **taskRetryNum**
  
  - Task 级重试次数：单个 Task 失败才重试，会更换 ProcessorTracker（具体 Worker），成本小，推荐主要用这个。

- **注意组合效果**：两者都配会乘法放大执行次数（比如都是 1，单机任务理论上可能执行 4 次）。

**机器配置（机器筛选）**

用于只在“健康机器”上跑任务：

- **minCpuCores**：最低 CPU 核数，低于该值的 Worker 不会被选中。

- **minMemoryGB**：最低可用内存。

- **minDiskGB**：最低可用磁盘空间。

- 0 表示不限制。

**集群配置（Worker 选择）**

- **designatedWorkers / 执行机器地址**
  
  - 强制指定某几台 Worker 执行，格式如：  
    `192.168.1.1:27777,192.168.1.2:27777`
  
  - 常用于 Debug。

- **maxWorkerCount / 最大执行机器数量**
  
  - 限制一次最多调动多少台 Worker，和 BROADCAST/Map 模式一起控制 fan-out 规模。

**告警配置**

- 关联 **告警用户** / 告警渠道（邮件、Webhook、钉钉、自定义等）。

- 当任务失败 / 重试耗尽 / 运行超时等触发时，发送通知。

### **jobParams和instanceParams实际应用说明**

#### **设计原则：**

- **jobParams 放“少变的默认配置 / 物理路径 / 固定脚本”**
  
  - 比如：脚本路径、日志根目录、默认端口、默认超时等
  
  - 一般由运维 / 平台负责人设置，**同一个 Job 的所有实例共享**

- **instanceParams 放“本次测试的具体参数”**
  
  - 比如：测试平台任务 ID、模型、数据集、QPS、并发、时长、触发人
  
  - 每次 `runJob` 根据前端表单/配置生成，**每个实例都不同**

- 两者要**有公共 ID** 能和测试平台 DB 对上号：
  
  - 例如 `platformTaskId` / `testPlanId`

## 关键场景及参数说明：

- **RunLLMTest**：主测试执行（拉容器 → 起服务 → 健康检查 → 压测）

- **CollectLogs**：日志打包 & 上传（下游任务）

- **AggregateMetrics**：指标聚合 & 入库

- **NotifyResult**：结果通知 / 告警

### 主测试执行 Job

负责：启动 Docker 容器、拉起 LLM 服务、健康检查、执行测试（功能/性能）、写原始日志。

### jobParams（模板级）

**至少包含：**

- **基本信息**
  
  - `jobType`: `"RUN_LLM_TEST"`
  
  - `envDefault`: 默认环境，如 `"staging"` / `"dev"` / `"prod"`

- **路径 / 目录（宿主机 + 容器）**
  
  - `hostLogBaseDir`: 宿主机日志根目录（如：`/data/llm-tests/logs`）
  
  - `containerLogBaseDir`: 容器内日志目录（如：`/app/logs`）
  
  - `hostScriptDir`: 宿主机测试脚本根目录（如：`/data/llm-tests/scripts`）
  
  - `containerWorkDir`: 容器内工作目录（如：`/app`）
  
  - `containerEntryScript`: 容器内入口脚本路径（如：`/app/run_llm_test.sh`）

- **Docker 通用配置**
  
  - `docker.runtime`: `"runc"` / `"nvidia"` / 其他 runtime
  
  - `docker.networkMode`: `"host"` / `"bridge"` / 自定义 network 名
  
  - `docker.baseImage`: 默认基础镜像（如：`"registry/llm-test-base:latest"`）
  
  - `docker.pullPolicy`: `"IfNotPresent"` / `"Always"`
  
  - `docker.defaultCpuLimit`: 默认 CPU 配额（如 `"4"`）
  
  - `docker.defaultMemoryLimit`: 默认内存（如 `"8g"`）
  
  - `docker.defaultGpuCount`: 默认 GPU 个数（无 GPU 时为 `0`）

- **健康检查默认配置**
  
  - `healthCheck.type`: `"HTTP"` / `"TCP"` / `"CMD"`
  
  - `healthCheck.path`: HTTP 模式下默认健康检查路径（如 `"/healthz"`）
  
  - `healthCheck.portKey`: 从 `instanceParams` 中取端口字段名称（例如 `"servicePort"`）
  
  - `healthCheck.intervalMillis`: 检查间隔（如 `2000`）
  
  - `healthCheck.timeoutMillis`: 单次检查超时（如 `1000`）
  
  - `healthCheck.maxRetry`: 最大重试次数（如 `30`）
  
  - `healthCheck.successThreshold`: 连续成功多少次判定服务 ready
  
  - `healthCheck.failureThreshold`: 连续失败多少次判定服务失败

- **执行期间监控 / 跟踪默认配置**
  
  - `monitor.trackMode`: `"CONTAINER"` / `"PROCESS"` / `"BOTH"`
  
  - `monitor.statsSampleIntervalSeconds`: Docker stats 采样间隔（如 `5` 秒）
  
  - `monitor.collectMetrics`: `true/false`，是否收集监控指标上报
  
  - `monitor.killOnTimeout`: `true/false`，超时是否自动 kill 容器
  
  - `monitor.stopTimeoutSeconds`: 优雅停止等待时间（如 `30`）
  
  - `monitor.cleanupPolicy`: `"REMOVE_ON_SUCCESS"` / `"ALWAYS"` / `"NEVER"`

- **负载 & 指标默认配置（可选）**
  
  - `defaultLoad.qps`: 默认 QPS
  
  - `defaultLoad.concurrency`: 默认并发数
  
  - `defaultLoad.warmupSeconds`: 默认预热时长
  
  - `defaultLoad.runSeconds`: 默认压测时长
  
  - `metricsPushEndpoint`: 指标上报地址（如 `http://metrics-gw:9091/llm-tests`）
  
  - `resourceGuard.maxQps`: 全局最大 QPS 上限
  
  - `resourceGuard.maxConcurrency`: 全局最大并发上限

### instanceParams（实例级）

**至少包含：**

- **任务 / 上下文标识**
  
  - `platformTaskId`: 测试平台任务 ID（如 `"T20251123-0001"`）
  
  - `testPlanId`: 测试计划/场景 ID（可选）
  
  - `triggerUser`: 触发人 ID / 工号（如 `"u001"`）
  
  - `env`: 本次执行环境（可覆盖 `envDefault`）

- **模型 & 数据集**
  
  - `modelKey`: 模型标识（如 `"qwen2_7b"`）
  
  - `modelVersion`: 模型版本 / 镜像 tag（如 `"v1.0.3"`）
  
  - `scenario`: 场景名称（如 `"chat_completion"` / `"embedding"`）
  
  - `datasetId` 或 `datasetPath`: 数据集标识或路径（如 `"gsm8k_v1"`）

- **容器镜像 & 资源配置**
  
  - `image`: 本次执行使用的镜像（如 `"registry/llm/llm-qwen2:1.0.3"`）
  
  - `containerName`: 容器名称（如 `"llm-test-T20251123-0001"`）
  
  - `resources.cpu`: 本次执行 CPU 限制（如 `"8"`）
  
  - `resources.memory`: 本次执行内存限制（如 `"16g"`）
  
  - `resources.gpus`: 本次执行 GPU 数量（如 `1`）
  
  - `networkModeOverride`: 覆盖默认 networkMode（可选）

- **端口 & Volume 映射**
  
  - `servicePort`: 容器内服务监听端口（如 `18080`）
  
  - `hostPort`: 宿主机映射端口（bridge 模式下可选）
  
  - `volumes`: Volume 列表
    
    - `hostPath`: 宿主机路径
    
    - `containerPath`: 容器内路径
    
    - `mode`: `"ro"` / `"rw"`
  
  - `envVars`: 容器内环境变量（如 `MODEL_KEY`, `MODEL_VERSION`, `DATASET_ID` 等）

- **负载配置（本次执行）**
  
  - `qps`: 目标 QPS
  
  - `concurrency`: 并发数
  
  - `warmupSeconds`: 预热时间
  
  - `runSeconds`: 压测时间

- **日志目录 & 标签**
  
  - `logDirSuffix`: 用于拼日志目录的后缀（如 `"task-T20251123-0001"`）
  
  - `extraTags`: 业务标签（如 `{"bizLine":"llm_test","cluster":"ascend910b-01"}`）

- **健康检查覆盖（可选）**
  
  - `healthCheckOverrides.path`: 特定模型自定义 health path（如 `"/ready"`）
  
  - `healthCheckOverrides.maxRetry`: 覆盖默认重试次数
  
  - 其他 `intervalMillis` / `timeoutMillis` 等按需覆盖

- **监控 / 超时覆盖（可选）**
  
  - `monitorOverrides.maxRunSeconds`: 本次容器最大运行时长（例如 `900` 秒）
  
  - `monitorOverrides.killSignal`: 优雅结束信号（如 `"SIGTERM"`）
  
  - `monitorOverrides.forceKillAfterSeconds`: 发完信号后，再等多久强制 kill

## CollectLogs（日志打包 & 上传 Job）

负责：根据任务 ID / instanceId 找到日志目录 → 打包 → 上传到对象存储 / NFS → 回写归档信息。

### CollectLogs 的 jobParams（模板级）

**至少包含：**

- `jobType`: `"COLLECT_LOGS"`

- `logBaseDir`: 宿主机日志根目录（如 `/data/llm-tests/logs`）

- `uploadBase`: 目标存储根路径（如 `minio://llm-logs` 或挂载的 `/mnt/llm-logs`）

- `archiveTempDir`: 打包中间目录（如 `/tmp/llm-archives`）

**可选：**

- `uploaderType`: `"minio"` / `"nfs"` / `"scp"` / `"s3"`

- `retentionDays`: 默认保留天数（用于后续清理策略）

- `archiveNamePattern`: 打包文件命名模式（如 `"logs-{env}-{date}-{platformTaskId}.tar.gz"`）

### CollectLogs 的 instanceParams（实例级）

**至少包含：**

- `platformTaskId`: 对应的测试平台任务 ID（与上游 RunLLMTest 一致）

- `upstreamInstanceId`: 上游 RunLLMTest 的 PowerJob `instanceId`

- `env`: 环境（`dev` / `staging` / `prod`）

- `date`: 执行日期（如 `"20251123"`）

- `logDirSuffix`: 与上游一致（如 `"task-T20251123-0001"`）
  
  > 用来拼出最终日志目录：  
  > `{logBaseDir}/{env}/{date}/{logDirSuffix}/...`

- `uploadTarget`: 具体上传目标（如 `minio://llm-logs/staging/20251123`）

**可选：**

- `extraFiles`: 附加需要打进去的路径（比如额外的 profiler / flamegraph）

- `skipPatterns`: 打包时排除的文件模式（如 `["*.tmp","*.prof"]`）

- `notifyArchiveToPlatform`: 是否在成功后回调测试平台（`true/false`）

- `platformCallbackUrl`: 回调地址（如 `https://test-platform/api/logs/archive/callback`）

## AggregateMetrics（指标聚合 & 入库 Job）

负责：读取原始结果 & 性能指标（DB / 文件 / 对象存储），计算准确率、TP95/TP99、QPS 等，写回平台库。

### AggregateMetrics 的 jobParams（模板级）

**至少包含：**

- `jobType`: `"AGGREGATE_METRICS"`

- `metricsSource`: 指标来源类型（`"db"` / `"file"` / `"object_storage"`）

- `resultDbKey`: 数据源配置 key（在你这边映射到具体 DB 连接）

- `outputTable`: 聚合指标输出表名（如 `"llm_test_metrics_summary"`）

- `defaultTimeWindowSeconds`: 默认聚合时间窗口（如 `900` 秒）

**可选：**

- `fileBaseDir`: 当 `metricsSource = "file"` 时代码读取的根目录

- `objectStorageBase`: 当 `metricsSource = "object_storage"` 时的根路径

- `metricDefinitions`: 指标定义列表（比如 `"accuracy"`, `"latency_p99"` 等）

- `aggStrategy`: 聚合策略（平均 / 分位数 / 最大值 / 自定义）

### AggregateMetrics 的 instanceParams（实例级）

**至少包含：**

- `platformTaskId`: 测试任务 ID

- `upstreamInstanceId`: 上游 RunLLMTest 的 `instanceId`

- `env`: 环境

- `modelKey`: 模型标识

- `datasetId`: 数据集标识

- `timeRange`: 本次聚合的时间范围（如 `{ "start": "...", "end": "..." }`）或仅 `end` + 窗口

**可选：**

- `metricsFilePath`: 指标原始文件路径（如使用 file/object_storage 模式）

- `filterTags`: 过滤条件（如仅聚合特定 tag 的数据）

- `outputTableOverride`: 覆盖默认输出表

- `extraTags`: 额外写入 summary 表的标签（如 `{"bizLine":"llm_test"}`）

## NotifyResult（结果通知 / 告警 Job）

负责：根据任务最终状态 & 指标，向相关人发送通知（飞书/钉钉/企业微信/邮件）。

### NotifyResult 的 jobParams（模板级）

**至少包含：**

- `jobType`: `"NOTIFY_RESULT"`

- `defaultChannels`: 默认通知通道列表（如 `["feishu","email"]`）

- `channelConfig`：
  
  - `feishu.webhookUrl`: 默认飞书机器人地址
  
  - `email.smtpKey`: 邮件服务配置 key
  
  - `im.webhookUrl`: 其他 IM 通道 webhook

- `defaultTemplateId`: 默认通知模板 ID

**可选：**

- `severityRules`: 根据任务结果/指标决定严重级别的规则（如：失败/部分失败/指标超阈值）

- `fallbackChannels`: 主通道失败后的兜底通道

- `notifyOnSuccess`: 是否成功也发送通知（`true/false`）

### NotifyResult 的 instanceParams（实例级）

**至少包含：**

- `platformTaskId`: 测试任务 ID

- `relatedInstances`: 相关 PowerJob 实例列表（如 RunLLMTest / CollectLogs / AggregateMetrics 的 instanceId）

- `env`: 环境

- `finalStatus`: 最终状态（如 `"SUCCEED"` / `"FAILED"` / `"PARTIAL_FAILED"`）

- `summaryUrl`: 平台任务详情 / 报表页面 URL

- `metricsSummary`: 关键信息（可以是结构化 JSON 或文本），如：
  
  - 主要指标（accuracy、TP99、QPS）
  
  - 用例数 / 失败数 / 异常情况简述

**可选：**

- `receiversOverride`: 本次通知的收件人列表（用户 ID / 邮箱 / IM user）

- `channelsOverride`: 覆盖默认通道（如只发飞书不发邮件）

- `templateIdOverride`: 使用特定模板发送

- `alertLevel`: 手动指定告警级别（如 `"INFO"` / `"WARN"` / `"CRITICAL"`）

## 日志相关处理方案

**核心问题：**

- **测试环境里原始日志怎么存 & 怎么命名**

- **测试结束后的日志打包 & 上传流程怎么设计**

- **执行期间测试平台怎么“实时看日志”**

### 一、测试环境原始日志：目录 & 命名规范

先选几个**全局唯一且稳定的标识**，后面全部复用：

- `taskId`：你测试平台自己的任务 ID

- `instanceId`：PowerJob 的实例 ID（可选，但推荐带上）

- `modelKey`：模型标识（如 `qwen2_7b`）

- `env`：环境（`dev` / `staging` / `prod`）

**目录结构**

```text
/data/llm-tests/logs/
  └── {env}/
      └── {yyyyMMdd}/
          └── task-{taskId}-{instanceId}/
              ├── svc-{modelKey}.log            # 推理服务日志
              ├── client-req.log                # 压测/客户端请求日志
              ├── client-metrics.log            # TPS/latency 等指标
              ├── env-setup.log                 # 环境准备脚本日志
              └── system-monitor.log            # top / nvidia-smi 等采样
```

**好处：**

- 单个任务所有日志都在一个目录，**打包/上传只看这个目录就行**；

- 通过 `env + date + taskId + instanceId` 能唯一定位一次测试；

- 日志服务器上的路径也可以照抄这套结构。

**文件命名**

遵循：**时间 + 角色 + 场景 + 关键标识**，方便查和按前缀过滤。

**示例模式：**

- 服务启动 &运行日志：  
  `svc-{modelKey}-{port}.log`

- 测试请求日志：  
  `client-req-{scenario}.log`

- 性能指标：  
  `metrics-{scenario}.log`

- 环境准备/清理：  
  `env-{stage}.log`（`env-setup.log`, `env-teardown.log`）

## 日志打包 & 上传流程

这里以“**做成下游 PowerJob 任务 Job_B_CollectLogs**”为例。

**整体流程**

1. **上游 Job_A（RunLLMTest）** 执行测试，输出原始日志到：
   
   - `/data/llm-tests/logs/{env}/{date}/task-{taskId}-{instanceId}/`

2. Job_A 完成后：
   
   - 要么由工作流自动调度 Job_B
   
   - 要么在 Job_A 的 Processor 里 `runJob(Job_B, instanceParams)` 手动触发

3. 下游 Job_B（CollectLogs） 只做三件事：
   
   - 根据 `taskId / instanceId / env / date` 定位日志目录；
   
   - `tar.gz` 打包；
   
   - 上传到日志存储（MinIO/S3/NAS），然后把上传地址写回测试平台（DB 或 API）。

## 执行期间“动态获取日志”的实现

**测试还在跑，页面能看到实时日志滚动**。  
这个可以分两层：

1. **日志如何从“被测进程/工具”流出来**

2. **日志如何从“测试机/Worker”推到“测试平台前端”**

**推荐方案：利用 PowerJob 在线日志 + WebSocket 推流**

**核心思路：**

- Processor 启动子进程（服务 / 压测工具），一边读子进程的 stdout/stderr，一边 `omsLogger.info(line)`；

- PowerJob 把 `omsLogger` 的日志写到实例日志里；

- 测试平台通过 `/instance/log` + WebSocket 把这些日志推到前端（这条链我们已经写过一套代码骨架）。

**测试平台如何实时把在线日志推到前端**

1. 前端点击“开始测试”：
   
   - 调用 `POST /api/jobs/{jobId}/run` → 获取 `instanceId + appId`

2. 前端打开 WebSocket：
   
   - `ws://your-platform/ws/log?instanceId={instanceId}&appId={appId}`

3. WebSocket Handler 后台循环调用 PowerJob `/instance/log`：
   
   - `GET {serverAddress}/instance/log?appId={appId}&instanceId={instanceId}&index={index}`
   
   - 拿到一页 `StringPage`，逐行 `session.sendMessage(new TextMessage(line))`
   
   - 根据 `nextIndex / isEnd` 继续拉下一页，直到任务结束
