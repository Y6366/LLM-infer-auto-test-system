一、核心实体与关系（概览）

环境与主机：env_host

服务模板（固定前缀/缺省参数/探针模板）：svc_template

一次服务拉起“计划”：svc_deploy

拉起计划的目标明细（主/从节点 N 个）：svc_deploy_target

PowerJob 调度与执行态：job_dispatch

进程与端口监控：proc_monitor

健康检查配置与事件：health_probe_cfg、health_probe_event

日志：流式日志片段（可选持久化）log_event、归档文件清单 log_archive

审计：op_audit_log

关系：一个 svc_deploy 对应 1..N 个 svc_deploy_target（master/worker*），每个 target 经过 PowerJob 产生 0..N 条 job_dispatch（重试/重拉），成功后得到 0..1 个 proc_monitor 活体记录；部

```
-- 1) 测试主机注册表：记录可调度的目标机
CREATE TABLE env_host (
  host_id           CHAR(36) PRIMARY KEY,
  hostname          VARCHAR(128) NOT NULL,
  ip_addr           VARCHAR(45)  NOT NULL,
  ssh_port          INT          NOT NULL DEFAULT 22,
  labels            JSON         NULL,            -- 自定义标签：{ "accel":"A100", "room":"A3", ... }
  agent_version     VARCHAR(32)  NULL,            -- 测试机上代理/采集器版本（若有）
  reachable         TINYINT(1)   NOT NULL DEFAULT 1,
  last_seen_at      DATETIME     NULL,
  remarks           VARCHAR(500) NULL,
  data_status       ENUM('ACTIVE','DISABLED','DELETED') NOT NULL DEFAULT 'ACTIVE',
  create_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  create_owner      VARCHAR(32)  NOT NULL,
  update_owner      VARCHAR(32)  NOT NULL,
  UNIQUE KEY uk_host_ip (ip_addr, ssh_port),
  KEY idx_labels ((CAST(JSON_EXTRACT(labels,'$.accel') AS CHAR(16))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 2) 服务模板：固定前缀/缺省参数/探针模板
CREATE TABLE svc_template (
  template_id       CHAR(36) PRIMARY KEY,
  scene_type        VARCHAR(20)  NOT NULL,          -- vllm/vllm_mindspore/vllm_ascend...
  model_backend     VARCHAR(32)  NOT NULL,          -- 与现有定义对齐
  vllm_ms_version   VARCHAR(20)  NOT NULL,
  fixed_prefix      VARCHAR(500) NOT NULL,          -- 启动命令固定前缀（后端写死时也可冗余记录）
  default_envs      JSON         NULL,              -- [{"name":"CUDA_VISIBLE_DEVICES","value":"0,1"}]
  default_master_args JSON       NULL,              -- [{"name":"model","value":"/mnt/..."},{"name":"tp","value":"2"},{"name":"enable-xxx","flag":true}]
  default_worker_args JSON       NULL,              -- 缺省 worker 形态参数
  probe_template    JSON         NULL,              -- 缺省健康探针配置（方法/URL/超时/期望码）
  remarks           VARCHAR(500) NULL,
  data_status       ENUM('ACTIVE','DISABLED','DELETED') NOT NULL DEFAULT 'ACTIVE',
  create_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  create_owner      VARCHAR(32)  NOT NULL,
  update_owner      VARCHAR(32)  NOT NULL,
  KEY idx_scene(scene_type, model_backend, vllm_ms_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3) 部署计划（一次拉起申请 + 汇总态）
CREATE TABLE svc_deploy (
  deploy_id         CHAR(36) PRIMARY KEY,
  template_id       CHAR(36) NOT NULL,
  model_info_id     CHAR(36) NULL,                  -- 可关联你现有的 model_info_details（可选）
  scene_type        VARCHAR(20)  NOT NULL,
  model_backend     VARCHAR(32)  NOT NULL,
  vllm_ms_version   VARCHAR(20)  NOT NULL,
  model_name        VARCHAR(64)  NOT NULL,
  envs_req          JSON         NULL,              -- 请求体 envs（KV结构化）
  run_cmd_req       JSON         NOT NULL,          -- 请求体 runcommand（含 masterArgs / workerArgs*）
  run_cmd_rendered  JSON         NULL,              -- 渲染后的最终命令：{ "master": "...", "workers":[ "...","..." ] }
  worker_count      INT          NOT NULL DEFAULT 0,
  status            ENUM('PENDING','DISPATCHING','RUNNING','SUCCEEDED','FAILED','CANCELLED','TIMEOUT') NOT NULL DEFAULT 'PENDING',
  owner_group       VARCHAR(20)  NOT NULL DEFAULT 'PUBLIC',
  data_status       ENUM('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
  create_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  create_owner      VARCHAR(32)  NOT NULL,
  update_owner      VARCHAR(32)  NOT NULL,
  remarks           VARCHAR(500) NULL,
  CONSTRAINT fk_svc_deploy_template FOREIGN KEY (template_id) REFERENCES svc_template(template_id),
  KEY idx_status(status, update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 4) 部署目标：master / worker[i] 映射到具体主机 & 渲染后命令
CREATE TABLE svc_deploy_target (
  target_id         CHAR(36) PRIMARY KEY,
  deploy_id         CHAR(36) NOT NULL,
  role              ENUM('MASTER','WORKER') NOT NULL,
  worker_index      INT       NULL,                -- WORKER 的编号，从 0 开始
  host_id           CHAR(36) NOT NULL,
  env_rendered      TEXT      NULL,                -- export 串（多行）
  cmd_rendered      TEXT      NULL,                -- 最终启动命令（含固定前缀 + 参数渲染）
  status            ENUM('PENDING','DISPATCHING','RUNNING','SUCCEEDED','FAILED','CANCELLED','TIMEOUT') NOT NULL DEFAULT 'PENDING',
  last_message      VARCHAR(500) NULL,
  data_status       ENUM('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
  create_time       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  create_owner      VARCHAR(32) NOT NULL,
  update_owner      VARCHAR(32) NOT NULL,
  CONSTRAINT fk_target_deploy FOREIGN KEY (deploy_id) REFERENCES svc_deploy(deploy_id),
  CONSTRAINT fk_target_host   FOREIGN KEY (host_id)   REFERENCES env_host(host_id),
  UNIQUE KEY uk_deploy_role_idx (deploy_id, role, worker_index),
  KEY idx_target_status (status, update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 5) PowerJob 调度与执行记录（一次转发=一条，重试多条）
CREATE TABLE job_dispatch (
  dispatch_id       CHAR(36) PRIMARY KEY,
  target_id         CHAR(36) NOT NULL,
  powerjob_instance_id BIGINT NULL,
  status            ENUM('QUEUED','SENT','STARTED','SUCCEEDED','FAILED','LOST') NOT NULL DEFAULT 'QUEUED',
  submit_time       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  start_time        DATETIME NULL,
  end_time          DATETIME NULL,
  error_msg         VARCHAR(500) NULL,
  data_status       ENUM('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
  CONSTRAINT fk_dispatch_target FOREIGN KEY (target_id) REFERENCES svc_deploy_target(target_id),
  KEY idx_pj (powerjob_instance_id),
  KEY idx_dispatch_status (status, submit_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 6) 进程/端口监控（活体）
CREATE TABLE proc_monitor (
  proc_id           CHAR(36) PRIMARY KEY,
  target_id         CHAR(36) NOT NULL,
  pid               BIGINT    NULL,
  listen_ports      JSON      NULL,                -- [8000, 8001]
  start_time        DATETIME  NULL,
  last_heartbeat    DATETIME  NULL,
  cpu_pct           DECIMAL(5,2) NULL,
  mem_mb            DECIMAL(10,2) NULL,
  status            ENUM('STARTING','RUNNING','EXITED','UNKNOWN') NOT NULL DEFAULT 'STARTING',
  data_status       ENUM('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
  CONSTRAINT fk_proc_target FOREIGN KEY (target_id) REFERENCES svc_deploy_target(target_id),
  KEY idx_proc_status (status, last_heartbeat)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 7) 健康探针配置（部署维度，可覆盖模板）
CREATE TABLE health_probe_cfg (
  probe_id          CHAR(36) PRIMARY KEY,
  deploy_id         CHAR(36) NOT NULL,
  method            ENUM('TCP','HTTP','CMD') NOT NULL,
  endpoint          VARCHAR(256) NULL,            -- host:port / http url / shell
  expect_code       INT          NULL,
  expect_keyword    VARCHAR(128) NULL,            -- 响应包含关键字
  interval_sec      INT NOT NULL DEFAULT 10,
  timeout_ms        INT NOT NULL DEFAULT 3000,
  retry             INT NOT NULL DEFAULT 3,
  data_status       ENUM('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
  CONSTRAINT fk_probe_deploy FOREIGN KEY (deploy_id) REFERENCES svc_deploy(deploy_id),
  KEY idx_probe_deploy (deploy_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 8) 健康探针事件流（历史）
CREATE TABLE health_probe_event (
  event_id          CHAR(36) PRIMARY KEY,
  probe_id          CHAR(36) NOT NULL,
  target_id         CHAR(36) NULL,                -- 哪个节点触发/归属（可选）
  pass_flag         TINYINT(1) NOT NULL,
  latency_ms        INT NULL,
  message           VARCHAR(500) NULL,
  event_time        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_event_probe  FOREIGN KEY (probe_id)  REFERENCES health_probe_cfg(probe_id),
  CONSTRAINT fk_event_target FOREIGN KEY (target_id) REFERENCES svc_deploy_target(target_id),
  KEY idx_probe_time (probe_id, event_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 9) 流式日志（可选持久化，若量大也可落 ES/对象存储）
CREATE TABLE log_event (
  log_id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  deploy_id         CHAR(36) NOT NULL,
  target_id         CHAR(36) NULL,
  source            ENUM('AGENT','POWERJOB','SERVICE','TEST_TOOL') NOT NULL,
  level             ENUM('DEBUG','INFO','WARN','ERROR') NOT NULL DEFAULT 'INFO',
  content           TEXT NOT NULL,
  ts                DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_deploy_ts (deploy_id, ts),
  KEY idx_target_ts (target_id, ts)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 10) 日志归档（打包后的文件位置）
CREATE TABLE log_archive (
  archive_id        CHAR(36) PRIMARY KEY,
  deploy_id         CHAR(36) NOT NULL,
  storage_url       VARCHAR(512) NOT NULL,        -- 如 s3://bucket/path.tgz 或 http(s) 可下载
  bytes             BIGINT       NULL,
  md5               CHAR(32)     NULL,
  created_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  remarks           VARCHAR(500) NULL,
  CONSTRAINT fk_archive_deploy FOREIGN KEY (deploy_id) REFERENCES svc_deploy(deploy_id),
  KEY idx_archive_time (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 11) 操作审计（可与现有审计表复用）
CREATE TABLE op_audit_log (
  audit_id          CHAR(36) PRIMARY KEY,
  entity_type       ENUM('DEPLOY','TARGET','JOB','PROC','PROBE','ARCHIVE') NOT NULL,
  entity_id         CHAR(36) NOT NULL,
  operation_type    ENUM('INSERT','UPDATE','DELETE','CANCEL','RETRY','ARCHIVE') NOT NULL,
  operator_user_id  VARCHAR(32) NOT NULL,
  changed_fields    JSON  NULL,
  old_data          JSON  NULL,
  new_data          JSON  NULL,
  remark            VARCHAR(500) NULL,
  op_time           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_entity (entity_type, entity_id, op_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

```

四、典型查询
-- 部署态势：总体 + 节点
SELECT d.deploy_id, d.status, d.update_time,
       SUM(t.status='RUNNING') AS running_nodes,
       SUM(t.status IN ('FAILED','TIMEOUT')) AS bad_nodes
FROM svc_deploy d
JOIN svc_deploy_target t ON d.deploy_id=t.deploy_id
WHERE d.deploy_id = '...'
GROUP BY d.deploy_id, d.status, d.update_time;

-- 查看最近 5 分钟关键日志
SELECT source, level, content, ts
FROM log_event
WHERE deploy_id='...' AND ts > NOW() - INTERVAL 5 MINUTE
ORDER BY ts DESC;

-- 最近一次健康检查结果（按 probe）
SELECT e.*
FROM health_probe_event e
JOIN (
  SELECT probe_id, MAX(event_time) AS last_ts
  FROM health_probe_event
  WHERE probe_id IN (SELECT probe_id FROM health_probe_cfg WHERE deploy_id='...')
  GROUP BY probe_id
) x ON e.probe_id=x.probe_id AND e.event_time=x.last_ts
ORDER BY e.event_time DESC;



太好了！下面是“服务部署模块”一套可落地的数据表设计，并逐表逐字段给出用途、类型、约束与示例。它覆盖：服务模板、环境画像、节点、一次“服务拉起请求”、分解到各节点/角色的任务、阶段化日志、实时日志行、健康探测、产物与归档，以及审计。字段名、类型按 MySQL 8 习惯写；如无特别说明皆为 NOT NULL。

1) svc_service_def（服务模板/基线）
字段	类型	约束	说明	示例
service_def_id	CHAR(36) PK	PK	模板ID	UUID
name	VARCHAR(64)	UK(name)	模板名（如“vllm-llama3 基线”）	vllm-llama3
model_backend	ENUM('vllm','vllm_mindspore','vllm_ascend')		后端类型	vllm
scene_type	VARCHAR(32)		场景类型/标签	vllm
vllm_ms_version	VARCHAR(32)	可空	后端版本（如“n/a”“2.4.1”）	n/a
fixed_prefix	VARCHAR(512)		启动命令固定前缀（写死，前端不传）	python -m vllm.entrypoint ...
default_envs	JSON		默认环境变量KV	[{"name":"CUDA_VISIBLE_DEVICES","value":"0,1"}]
default_master_args	JSON		默认 master 参数KV	[{"name":"tp","value":"2"}]
default_worker_args	JSON		默认 worker 参数KV	[]
remarks	VARCHAR(255)	可空	说明	基线模板
create_time	DATETIME		创建时间	
update_time	DATETIME		修改时间	
owner	VARCHAR(32)		负责人	u001
status	ENUM('ACTIVE','DISABLED')		状态	ACTIVE

用途：沉淀“固定前缀/默认参数”，新请求可基于模板继承。

2) svc_env_profile（环境画像/可运行能力）
字段	类型	说明	示例
env_profile_id	CHAR(36) PK	环境画像ID	UUID
name	VARCHAR(64)	画像名	A100-2GPU-CUDA12
env_type	ENUM('GPU','ASCEND','CPU')	执行环境	GPU
os	VARCHAR(64)	系统	Ubuntu 22.04
arch	VARCHAR(32)	架构	x86_64
accelerator	VARCHAR(64)	加速卡信息	A100
runtime	JSON	运行时版本（cuda/cudnn/driver/python等）	{"cuda":"12.2","python":"3.10"}
labels	JSON	调度标签	["perf","low-latency"]
status	ENUM('READY','UNAVAILABLE')	状态	READY
create_time/update_time	DATETIME		
3) svc_server_node（测试机节点）
字段	类型	说明	示例
node_id	CHAR(36) PK	节点ID	UUID
hostname	VARCHAR(128)	主机名	worker-01
ip	VARCHAR(64)	IP	10.0.0.12
ssh_port	INT	SSH端口	22
env_profile_id	CHAR(36) FK	绑定环境画像	参考上表
labels	JSON	节点标签（如显存、机房）	{"mem_gb":128}
last_heartbeat	DATETIME	最近心跳	
status	ENUM('ONLINE','OFFLINE','BUSY')	节点状态	ONLINE
4) svc_deploy_request（一次“服务拉起请求”）

前端发起一次拉起：携带 envs 与 runcommand（后端拼接为完整命令），如果有多 worker，用 workerArgs* 动态键。

字段	类型	说明	示例
deploy_req_id	CHAR(36) PK	请求ID	UUID
service_def_id	CHAR(36) FK 可空	关联模板（可选）	
scene_type	VARCHAR(32)	场景	vllm
model_backend	ENUM(...)	后端	vllm
vllm_ms_version	VARCHAR(32)	版本	n/a
model_name	VARCHAR(64)	模型名	llama3-8b
envs	JSON	环境变量KV	[{"name":"CUDA_VISIBLE_DEVICES","value":"0,1"}]
run_command_src	JSON	原始结构化参数（masterArgs、workerArgs0..n）	见请求体
run_command_rendered	JSON	后端渲染结果（含前缀+每角色完整命令）	{"master":"export ... && python ...","workers":["...","..."]}
requester	VARCHAR(32)	发起人	u001
request_time	DATETIME	发起时间	
status	ENUM('PENDING','DISPATCHING','RUNNING','READY','FAILED','CANCELLED')	总体状态	RUNNING
powerjob_job_id	BIGINT 可空	关联PowerJob JobId	123
remarks	VARCHAR(255) 可空	备注	

run_command_rendered.rendered 的作用：记录“落库时真正执行的完整命令串”，可复现实验与审计（即便模板/拼接策略日后改变，历史仍可重现）。

5) svc_deploy_task（拆解到节点/角色的执行任务）

一次请求会拆成 master 任务 + N 个 worker 任务；通过 PowerJob 分发。

字段	类型	说明	示例
task_id	CHAR(36) PK	任务ID	UUID
deploy_req_id	CHAR(36) FK	归属请求	
role	ENUM('MASTER','WORKER')	角色	WORKER
role_index	INT	worker 序号（MASTER=0）	1
node_id	CHAR(36) FK 可空	选中的目标节点	
powerjob_instance_id	BIGINT 可空	PowerJob实例ID	56789
status	ENUM('DISPATCHED','RUNNING','SUCCEEDED','FAILED','CANCELLED','TIMED_OUT')	任务状态	RUNNING
lease_version	BIGINT	围栏令牌	1
lease_expire_at	DATETIME	租约到期	
started_at/finished_at	DATETIME 可空	起止时间	
exit_code	INT 可空	进程退出码	0
script_uri	VARCHAR(255) 可空	执行脚本URI	s3://.../start.sh
env_file_uri	VARCHAR(255) 可空	导出的 env 文件URI	
6) svc_stage_log（阶段日志元信息）

将拉起流程拆成阶段：准备、渲染env、渲染命令、启动、健康探测、就绪、归档……

字段	类型	说明	示例
stage_log_id	CHAR(36) PK	阶段日志ID	UUID
task_id	CHAR(36) FK	关联任务	
stage	ENUM('PREPARE','RENDER_ENV','RENDER_CMD','START_PROCESS','HEALTH_CHECK','READY','ARCHIVE_LOGS','ERROR')	阶段名	START_PROCESS
log_path	VARCHAR(255) 可空	节点本地日志文件	/var/logs/.../start.log
ws_channel_id	VARCHAR(64) 可空	WebSocket 通道ID（实时推送）	
bytes	BIGINT	当前累计大小	10240
first_ts/last_ts	DATETIME 可空	首尾时间	
7) svc_log_line（实时日志行，按需落库/抽样）
字段	类型	说明
log_line_id	BIGINT PK AUTO	日志行ID
stage_log_id	CHAR(36) FK	归属阶段
ts	DATETIME	时间戳
level	ENUM('DEBUG','INFO','WARN','ERROR')	日志级别
seq	BIGINT	有序序列（断线续传对齐）
line	TEXT	日志内容

此表可能很大：可做冷热分层（近实时在表里，久远归档到对象存储，仅保留索引）。

8) svc_health_check（健康探测记录）
字段	类型	说明	示例
check_id	BIGINT PK AUTO	记录ID	
task_id	CHAR(36) FK	关联任务	
url	VARCHAR(255)	探测目标	http://10.0.0.12:8000/generate
method	ENUM('GET','POST')	方法	POST
req_body	JSON 可空	请求体	{"prompt":"hello"}
res_status	INT 可空	返回码	200
res_ms	INT 可空	耗时ms	123
ok	TINYINT(1)	是否成功	1
ts	DATETIME	时间	
9) svc_artifact（产物：脚本、env文件、打包包等）
字段	类型	说明	示例
artifact_id	CHAR(36) PK	产物ID	UUID
task_id	CHAR(36) FK	关联任务	
kind	ENUM('SCRIPT','ENV_FILE','ARCHIVE','OTHER')	分类	SCRIPT
uri	VARCHAR(255)	存储位置	s3://logs/run123/start.sh
checksum	VARCHAR(64) 可空	完整性	sha256
size	BIGINT 可空	字节数	2048
created_at	DATETIME	生成时间	
10) svc_log_archive（归档包：整次流程日志打包）
字段	类型	说明
archive_id	CHAR(36) PK	归档ID
deploy_req_id	CHAR(36) FK	关联请求
bundle_uri	VARCHAR(255)	归档ZIP/TAR位置
size	BIGINT	大小
checksum	VARCHAR(64) 可空	校验
created_at	DATETIME	归档时间
11) svc_audit_log（操作与关键状态审计）
字段	类型	说明
audit_id	CHAR(36) PK	审计ID
entity_type	VARCHAR(32)	实体（如 deploy_request/deploy_task）
entity_id	CHAR(36)	实体ID
operation	ENUM('INSERT','UPDATE','DELETE','DISPATCH','START','READY','CANCEL')	操作
operator	VARCHAR(32)	用户/系统
before_data	JSON 可空	变更前
after_data	JSON 可空	变更后
changed_fields	JSON 可空	变更字段列表
reason	VARCHAR(255) 可空	变更原因
ts	DATETIME	时间
关键设计要点（与你现有后端对齐）

命令渲染：前端只传结构化 envs 与 runcommand.*Args；后端读取模板中的 fixed_prefix，渲染出：

export ...; export ...;（由 envs 生成）

master 与 workers[] 完整启动串（由 masterArgs、workerArgs* 生成）

落库到 svc_deploy_request.run_command_rendered，保证可重现和可审计。

多 worker：请求体里 workerArgs0/1/... → 后端解析为数组，存入 run_command_src，并在拆任务落到 svc_deploy_task(role=WORKER, role_index=i)。

日志链路：PowerJob 执行脚本将日志通过 WebSocket/HTTP 流式推送；后端写 svc_log_line（或仅写文件，表里记录 stage_log 元数据信息）；流程结束后把所有单机日志打包，落 svc_log_archive + svc_artifact。

健康探测：启动后后台周期性探测，记录到 svc_health_check，首个成功即把任务/请求置为 READY。

幂等与状态机：svc_deploy_task 带 lease_version 与 status，便于重试/重派；重要状态变更写 svc_audit_log。





1) svc_blueprint（服务蓝图）

作用：抽象“一类可部署的模型服务”的标准形态（固定前缀命令、目录结构、启动/停止协议、健康探针协议等）的长期元数据。

边界：不承载具体模型权重与参数，只描述“怎么拉起一类服务”。

读写路径：研发/平台同学维护；部署时只读。

关联：svc_version（具体化此蓝图）、svc_script_template、svc_probe_template。

生命周期/分区：低变更；全量保留。

典型查询：按蓝图名、后端类型（vllm/vllm_ascend等）检索。

2) svc_version（服务版本/装配）

作用：在蓝图上填入具体模型名/版本、固定前缀命令、镜像/依赖等形成可直接复用的“装配版本”。

边界：仍不含本次部署的临时参数；固定部分写死（如固定 fixedPrefix、镜像标签、必须的运行时包版本）。

读写路径：发布流程写入；部署流程读取。

关联：svc_blueprint、svc_artifact（镜像/包）、svc_script_template。

生命周期/分区：按版本冻结；可“发布/下线”。

典型查询：按模型名、后端、版本筛选，给前端“下拉选择”。

3) svc_deploy_request（部署请求/一次发起）

作用：一次“发起部署”的业务单据，承载本次的 envs、masterArgs、workerArgs*、目标节点/分组、操作者、备注等**。

边界：这是本次动作（而非长期版本）；它决定“本次如何渲染脚本与命令”。

读写路径：前端提交写入；部署/重试时读取；状态机更新（PENDING→DISPATCHED→RUNNING→SUCCESS/FAILED/CANCELLED）。

关联：svc_version、svc_deploy_instance、svc_job、svc_log_file、svc_event。

生命周期/分区：按时间分区（月/日），保留期结合审计要求。

典型查询：我的部署单、某时间段内成功率、失败原因聚合。

4) svc_deploy_instance（部署实例/主从维度）

作用：将一次 deploy_request 拆解为主实例（master）与多个从实例（workerN）的执行单元，记录每个实例的渲染结果、执行节点、PID、端口、状态、重试次数。

边界：一行代表一个具体节点上的一个进程/容器。

读写路径：渲染阶段写入初值；执行/心跳/探活时更新。

关联：svc_deploy_request、svc_node、svc_job、svc_process、svc_endpoint、svc_log_chunk。

生命周期/分区：按时间或 deploy_request_id 分区；保留供溯源。

典型查询：某次部署下各实例状态；某节点最近 N 次部署失败实例。

5) svc_node（执行节点/服务器清单）

作用：登记测试机/执行节点，含：节点标签（GPU/910B 等）、资源配额、SSH/Agent 信息、可用状态。

边界：不存敏感密钥明文（用 secret_ref）。

读写路径：资源管理维护；调度/筛选节点时读取。

关联：svc_deploy_instance、svc_endpoint。

生命周期/分区：长生命周期，软删。

典型查询：按标签选择可用节点、最近心跳/负载。

6) svc_script_template（脚本模板库）

作用：存放Shell/Python 模板（固定前缀 + 插值位点），供渲染生成最终执行脚本。

边界：模板版本化；与蓝图/版本绑定。

读写路径：发布更新；部署渲染时只读。

关联：svc_blueprint、svc_version、svc_script_rendered。

生命周期/分区：版本化保留。

典型查询：蓝图/版本对应的模板历史。

7) svc_script_rendered（渲染产物留痕）

作用：本次部署实际下发的脚本 & 命令留痕（含 export ... 的环境变量拼接、masterArgs/workerArgsN 渲染后的最终命令）。

边界：不可变历史（便于“复盘/重现/审计”）。

读写路径：渲染后写入；回溯/下载时读取。

关联：svc_deploy_request、svc_deploy_instance。

生命周期/分区：按时间或请求分区；大小受限可归档到对象存储，表里放元数据与 URI。

典型查询：某次部署的最终脚本、对比两次渲染差异。

8) svc_job（与 PowerJob 绑定）

作用：关联网关/Job 平台（PowerJob）的JobId/InstanceId/状态到业务侧 deploy_request/instance。

边界：只存映射和运行快照；详情从 PowerJob API 拉取或落增量。

读写路径：派发时创建/更新；回调/轮询时同步。

关联：svc_deploy_request、svc_deploy_instance。

生命周期/分区：按时间分区；保留期与任务保留策略一致。

典型查询：定位某次部署对应的 PowerJob 实例。

9) svc_log_chunk（流式日志片段）

作用：WebSocket/Agent 推送的实时日志按时间切片落库（或 Kafka→ES），支持在线追踪与回放。

边界：高写入量；建议冷热分层：热表存近 1–3 天；归档到文件/对象存储。

读写路径：执行期写入；前端“实时查看”查询拉取。

关联：svc_deploy_instance、svc_log_file（归档后形成文件）。

生命周期/分区：强烈建议按日/小时分区或落 ES；MySQL 仅保近时段。

典型查询：按实例 + 时间范围分页拉取最近日志。

10) svc_log_file（日志归档索引）

作用：保存最终日志包（路径、大小、MD5、下载 URL、存储位置如 S3/NAS）的索引，供浏览/下载。

边界：文件本体在对象存储；此表是元数据/权限控制。

读写路径：执行完成时写入；用户下载/后台比对时读取。

关联：svc_deploy_request、svc_deploy_instance。

生命周期/分区：按时间；可设置到期清理策略。

典型查询：某部署的日志下载清单，MD5 校验。

11) svc_probe_template / svc_health_status

作用：

svc_probe_template：健康检查/就绪探针的协议模板（HTTP ping、模型单测请求样例、超时/重试阈值）。

svc_health_status：每个实例在启动阶段及驻留阶段的探测结果快照（通过/失败、延迟、错误原因）。

边界：模板可版本化；状态表高频写。

读写路径：探活进程/Sidecar 写入状态；运维页面读取。

关联：svc_deploy_instance、svc_version。

生命周期/分区：状态表按时间分区；只保留近 N 天。

典型查询：近一小时健康率、失败原因 topN。

12) svc_endpoint（服务端点）

作用：记录每个实例暴露的端口/URL/协议，供“探活、压测、联调”使用。

边界：与实例强关联；随着实例起停而更新。

读写路径：启动成功后写入；停止时关闭。

关联：svc_deploy_instance、svc_node。

生命周期/分区：跟随实例生命周期。

典型查询：某实例的推理 HTTP 地址、批量导出端点给压测。

13) svc_process（进程元信息/围栏）

作用：保存实例上的PID、启动时间、退出码，以及租约/围栏令牌（lease_version）用于防止“旧进程回写”。

边界：不是系统级进程监控替代；只做业务侧一致性控制。

读写路径：启动/心跳更新；退出/超时写终态。

关联：svc_deploy_instance。

生命周期/分区：按时间；短保留。

典型查询：定位“僵尸/孤儿”进程，重派/清理。

14) svc_artifact（制品/镜像/权重）

作用：挂接镜像、模型权重、脚本包等制品元数据与可访问位置。

边界：不存大文件本体；只存引用与校验。

读写路径：发布/入库时写入；渲染/部署读取。

关联：svc_version、svc_deploy_request（如本次临时包）。

生命周期/分区：版本化；到期清理。

典型查询：某版本依赖的镜像与权重集合。

15) svc_event（业务事件/审计）

作用：记录关键状态变迁（创建请求、派发、启动成功/失败、健康通过、归档完成、下线等），附带操作者/原因。

边界：不可变；用于审计/回放。

读写路径：全链路写入；运维审计读取。

关联：svc_deploy_request、svc_deploy_instance、svc_job。

生命周期/分区：按时间分区；长期保留或冷归档。

典型查询：还原一次部署的完整时间线。

关键设计取舍（与你现有方案对齐）

命令渲染落地：
固定前缀在 svc_version；请求体只带 envs 与 runcommand.masterArgs / workerArgsN（结构化 KV）。渲染结果（含 export 拼接+最终命令字符串）写 svc_script_rendered，并按 master 与 workerN 拆成多条，分别关联 svc_deploy_instance。

主从多实例：
一个 deploy_request → 多个 deploy_instance（1 个 master，N 个 worker）。PowerJob 侧要么一个工作流编排多子任务，要么 N 次并行子任务；都映射在 svc_job。

日志与可观测性：
执行期实时日志写 svc_log_chunk（或经 Kafka→ES）；结束后归档大包落对象存储并登记到 svc_log_file。页面“实时看”读 chunk，“历史查/下载”读 file。

健康探活与驻留：
启动窗口内使用 svc_probe_template 渲染请求样例（包括“发给模型的单次请求 + 验证回包”）；探测结果落 svc_health_status；通过后登记 svc_endpoint 以供压测/联调。

一致性控制：
svc_process 存围栏令牌（lease_version） + 心跳时间；回写/归档/状态推进都带令牌做CAS，避免“旧实例覆盖新状态”。

审计/回放：
任何状态切换都写 svc_event；渲染产物持久化在 svc_script_rendered，保证“可复现”。
