- 前端**不传** `fixedPrefix`；固定前缀由**后端**根据业务写死（或通过配置托管）。
  
- 环境变量：前端只传**变量名+变量值**列表；后端拼成 `export NAME=VALUE; export ...;` 这样的**单行/多段**字符串后入库。
  
- 启动命令只拆 **masterArgs** 与 **workerArgs**（没有 commonArgs）。
  
- 主从（master / worker）**固定前缀一致**，只是**参数不同**。
  
- 数据库存**需要保存完整的字符串**（env + command）。
  

> 备注：你原表只有 `run_command`、`env_variable` 两列（单值）。如果要同时保存 master / worker 两套命令，有两种做法：
> 
> - **方案 A（推荐）**：数据库加列：`run_command_master`、`run_command_worker`、（可选）`env_variable_master`、`env_variable_worker`；
>   
> - **方案 B（无表变更）**：`run_command` 内保存 JSON 字符串：`{"master":"...","worker":"..."}`；`env_variable` 同理。
>   

下面给出**接口模型、装配规则、工具类、Service 侧代码骨架**，以及两种存储方案的片段。

1. 配置固定前缀（后端写死/可配置）

```
// src/main/java/org/example/config/CommandTemplates.java
@Component
@ConfigurationProperties(prefix = "launch")
@Data
public class CommandTemplates {
    /**
     * 一个固定前缀（主从一致）；也可按 backend/build/version 做多套映射
     * 例如: python -m vllm.entrypoints.api_server
     */
    private String fixedPrefix;

    // 可选：按 backend+build+version 定制（用 Map ）
    // key 示例: "vllm|v1|2.4.1"
    private Map<String, String> fixedPrefixMap = new HashMap<>();

    public String resolvePrefix(String backend, String build, String version) {
        String key = String.join("|",
                backend == null ? "" : backend,
                build == null ? "" : build,
                version == null ? "" : version);
        return fixedPrefixMap.getOrDefault(key, fixedPrefix);
    }
}

```

`application.yml` 示例：

```
launch:
  fixed-prefix: "python -m vllm.entrypoints.api_server"
  # 可选的精细化配置
  fixed-prefix-map:
    "vllm|v1|2.4.1": "python -m vllm.entrypoints.api_server"
    "vllm_mindspore|v1|2.4.1": "python -m vllm_mindspore.api_server"

```

2. 请求/响应 DTO（前端只传变量名/值 & masterArgs/workerArgs）

```
// src/main/java/org/example/dto/kv/EnvKV.java
@Data
public class EnvKV {
    @NotBlank private String name;
    @NotBlank private String value;
}

// src/main/java/org/example/dto/kv/ArgKV.java
@Data
public class ArgKV {
    /** 参数名，如 "model" 或 "tensor-parallel-size"；会渲染成 --name=value */
    @NotBlank private String name;
    /** 参数值；可为空，为空时渲染成 --name（开关类 flag） */
    private String value;
}

// src/main/java/org/example/dto/ModelCreateReq.java
@Data
public class ModelCreateReq {
    // 原有业务字段...（省略不变）
    @NotBlank private String sceneType;
    private String vllmMsVersion;     // 可选
    @NotBlank private String modelBackend; // vllm / vllm_mindspore / vllm_ascend
    private String vllmBuild;         // v0/v1

    // 环境变量：只传 name/value
    @NotEmpty private List<EnvKV> envVars;

    // 启动命令参数：仅 master / worker
    @NotNull  private List<ArgKV> masterArgs;
    @NotNull  private List<ArgKV> workerArgs;

    // 其它模型信息字段（如 ckptPath、modelName、parallel 等）照旧
}

// src/main/java/org/example/dto/ModelCreateResp.java
@Data
@AllArgsConstructor
public class ModelCreateResp {
    private String modelInfoId;
    private String envVariable;          // 若你只存一份
    private String runCommandMaster;     // 方案A：单列；方案B：返回拼好给前端看
    private String runCommandWorker;
}

```

## 3) 拼装规则（后端统一）

- **环境变量**：`export NAME=VALUE;` 多个用空格或换行连接；值含空格/特殊字符用**单引号**安全包裹：`export HF_HOME='/mnt/hf space';`
  
- **参数**：
  
  - 有值：`--name=value`；值需要 shell 安全（见下）。
    
  - 无值：`--flag`
    
  - 渲染顺序使用传入顺序（也可按 name 排序，便于缓存命中与审计对比，一致性更好）。
    
- **固定前缀**：后端从 `CommandTemplates` 解析得到，**主从一致**。
  
- **最终命令**：`<fixedPrefix> <renderedArgs>`。  
  如果需要把 env 与命令连一起给 agent：`<envString> <fixedPrefix> <args>` 或用 `sh -lc` 执行。
  

4. 安全拼接工具（避免空格/特殊字符问题）

```
// src/main/java/org/example/utils/ShellRender.java
public final class ShellRender {
    private ShellRender() {}

    /** 将值转成安全的单引号字面量：abc -> 'abc'；包含单引号时做 POSIX 转义 */
    public static String shQuote(String s) {
        if (s == null) return "''";
        if (s.isEmpty()) return "''";
        // 将单引号 ' 变为 '\'' 以在单引号上下文中安全表示
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }

    /** export 字符串：export NAME=VALUE; ... */
    public static String renderEnv(List<EnvKV> envs) {
        if (envs == null || envs.isEmpty()) return "";
        StringJoiner sj = new StringJoiner(" ");
        for (EnvKV kv : envs) {
            String name = kv.getName().trim();
            String val  = kv.getValue() == null ? "" : kv.getValue();
            // 简单校验：变量名只允许字母/数字/下划线
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException("Bad env name: " + name);
            }
            String rhs = val.matches("^[A-Za-z0-9_./:-]*$") ? val : shQuote(val);
            sj.add("export " + name + "=" + rhs + ";");
        }
        return sj.toString().trim();
    }

    /** 将 ArgKV 列表渲染成 --name=value 或 --flag */
    public static String renderArgs(List<ArgKV> args) {
        if (args == null || args.isEmpty()) return "";
        return args.stream().map(kv -> {
            String name = kv.getName().trim();
            if (!name.matches("[A-Za-z0-9][A-Za-z0-9-]*")) {
                throw new IllegalArgumentException("Bad arg name: " + name);
            }
            String v = kv.getValue();
            if (v == null || v.isBlank()) {
                return "--" + name;
            }
            String val = v.matches("^[A-Za-z0-9_./:-]*$") ? v : shQuote(v);
            return "--" + name + "=" + val;
        }).collect(Collectors.joining(" "));
    }

    /** 最终启动命令（prefix + args） */
    public static String renderCommand(String prefix, List<ArgKV> args) {
        String a = renderArgs(args);
        return a.isBlank() ? prefix : (prefix + " " + a);
    }
}

```

5. Service 实现要点（入库前组装）

```
@Service
@RequiredArgsConstructor
public class ModelInfoServiceImpl implements ModelInfoService {

    private final CommandTemplates templates;
    private final ModelInfoMapper modelInfoMapper;

    @Transactional
    @Override
    public String create(ModelCreateReq req, String operator) {
        // 1) 解析固定前缀（主从一致）
        String prefix = templates.resolvePrefix(
                req.getModelBackend(), req.getVllmBuild(), req.getVllmMsVersion()
        );

        // 2) 渲染 env 与两套命令
        String envStr      = ShellRender.renderEnv(req.getEnvVars());
        String cmdMaster   = ShellRender.renderCommand(prefix, req.getMasterArgs());
        String cmdWorker   = ShellRender.renderCommand(prefix, req.getWorkerArgs());

        // 3) 入库（给出两种存储方案）

        // 方案 A：独立列（推荐，查询友好）
        ModelInfoDetails po = new ModelInfoDetails();
        po.setModelInfoId(UUID.randomUUID().toString());
        // ... 其它字段赋值（sceneType/ckptPath/...）
        po.setEnvVariable(envStr);
        po.setRunCommandMaster(cmdMaster);
        po.setRunCommandWorker(cmdWorker);
        po.setCreateOwner(operator);
        po.setUpdateOwner(operator);
        po.setDataStatus(DataStatus.ACTIVE);
        modelInfoMapper.insert(po);

        return po.getModelInfoId();
    }
}

```

## 6) Mapper 片段（方案 A & 方案 B）

### 方案 A：加列（推荐）

- 表结构变更：

```
ALTER TABLE model_info_details
    ADD COLUMN run_command_master VARCHAR(800) NULL AFTER run_command,
    ADD COLUMN run_command_worker VARCHAR(800) NULL AFTER run_command_master;
-- 可选：若需要主从不同的 env
-- ALTER TABLE model_info_details ADD COLUMN env_variable_master VARCHAR(400) NULL, ADD COLUMN env_variable_worker VARCHAR(400) NULL;

```

Mapper `insert`（示例）：

```
@Insert({
 "INSERT INTO model_info_details(",
 "  model_info_id, ..., env_variable, run_command_master, run_command_worker, create_owner, update_owner, data_status",
 ") VALUES(",
 "  #{modelInfoId}, ..., #{envVariable}, #{runCommandMaster}, #{runCommandWorker}, #{createOwner}, #{updateOwner}, #{dataStatus}",
 ")"
})
int insert(ModelInfoDetails po);

```

方案 B：不改表（JSON 塞进原列里）

```
String cmdJson = "{\"master\":" + JsonUtils.toJson(cmdMaster) +
                 ",\"worker\":" + JsonUtils.toJson(cmdWorker) + "}";
po.setRunCommand(cmdJson);
po.setEnvVariable(envStr);  // 或 env 也做成 {"master":"...","worker":"..."}

```

7. Controller 示例（创建）

```
@RestController
@RequestMapping("/api/models")
@RequiredArgsConstructor
public class ModelInfoController {
    private final ModelInfoService service;

    @PostMapping
    public ModelCreateResp create(@RequestBody @Valid ModelCreateReq req,
                                  @RequestHeader(value = "X-Operator", required = false) String operator) {
        String op = (operator == null || operator.isBlank()) ? "system" : operator;
        String id = service.create(req, op);

        // 为了便于前端调试，返回拼好的命令与 env（生产可去掉或通过详情查询拿）
        // 如果你选择方案B（JSON 存储），这里可以直接反序列化返回 master/worker。
        return new ModelCreateResp(
                id,
                ShellRender.renderEnv(req.getEnvVars()),
                ShellRender.renderCommand(
                        service.getPrefixFor(req), req.getMasterArgs()),
                ShellRender.renderCommand(
                        service.getPrefixFor(req), req.getWorkerArgs())
        );
    }
}

```

`service.getPrefixFor(req)` 可封装为从 `CommandTemplates` 解析的公共方法。

## 8) 前后端对齐的请求/响应示例

**请求（POST /api/models）：**

```
{
  "sceneType": "vllm",
  "vllmMsVersion": "2.4.1",
  "modelBackend": "vllm",
  "vllmBuild": "v1",

  "envVars": [
    {"name":"CUDA_VISIBLE_DEVICES","value":"0,1"},
    {"name":"HF_HOME","value":"/mnt/hf space"}
  ],
  "masterArgs": [
    {"name":"model","value":"/mnt/ckpt/llama3-8b"},
    {"name":"tensor-parallel-size","value":"2"},
    {"name":"enable-chunked-prefill"}  // flag
  ],
  "workerArgs": [
    {"name":"model","value":"/mnt/ckpt/llama3-8b"},
    {"name":"tensor-parallel-size","value":"2"},
    {"name":"worker-index","value":"0"}
  ]
}

```

**服务端实际入库字符串（示意）：**

- `env_variable`  
  `export CUDA_VISIBLE_DEVICES=0,1; export HF_HOME='/mnt/hf space';`
  
- `run_command_master`  
  `python -m vllm.entrypoints.api_server --model=/mnt/ckpt/llama3-8b --tensor-parallel-size=2 --enable-chunked-prefill`
  
- `run_command_worker`  
  `python -m vllm.entrypoints.api_server --model=/mnt/ckpt/llama3-8b --tensor-parallel-size=2 --worker-index=0`
  

## 9) 审计 & 可维护性提示

- **把请求参数（envVars/masterArgs/workerArgs）也记入审计**（old/new + changed_fields），便于复盘。
  
- **固定前缀可配置**，但**不要暴露给前端**；如需灰度，可按 backend/build/version 匹配。
  
- **Shell 安全**：统一用单引号包裹复杂值，避免空格/特殊符号破坏命令行。
  
- **长度限制**：表字段 `VARCHAR` 适当加大（如 800），并在后端校验总长度。
  
- **幂等/复现**：建议把渲染后的“最终命令”与“原始 KV 列表 JSON”**两者都存**（一个检索易读、一个可重渲染）。
  

使用不改表直接用「JSON 放 run_command」的版本

好的，我们就按“**不改表结构**、把 `run_command` 字段里塞 **JSON 字符串**”来实现。下面给你一套**接口设计 + DTO + 组装逻辑 + Mapper 保持不变**的完整落地方案（只贴关键代码，能直接放进你现有工程）。

# 一、`run_command` 内部 JSON 约定

把 `run_command` 存成 **JSON 字符串**，同时兼顾“原始参数”和“拼好的可执行串”，建议结构：

```
{
  "version": 1,
  "fixedPrefix": "python -m vllm.entrypoints.api_server",
  "env": [
    {"name": "CUDA_VISIBLE_DEVICES", "value": "0"},
    {"name": "HF_HOME", "value": "/opt/hf"}
  ],
  "masterArgs": ["--model", "llama3-8b", "--tp", "2"],
  "workerArgs": ["--model", "llama3-8b", "--worker", "1"],
  "rendered": {
    "envLine": "export CUDA_VISIBLE_DEVICES=0; export HF_HOME=/opt/hf;",
    "master": "export CUDA_VISIBLE_DEVICES=0; export HF_HOME=/opt/hf; python -m vllm.entrypoints.api_server --model llama3-8b --tp 2",
    "worker": "export CUDA_VISIBLE_DEVICES=0; export HF_HOME=/opt/hf; python -m vllm.entrypoints.api_server --model llama3-8b --worker 1"
  }
}

```

> 说明
> 
> - `fixedPrefix`：**后端写死/按场景选择**，前端不传。
>   
> - `env`：前端只传“名称+值”，后端生成 `envLine`（`export A=B; ...`）。
>   
> - `masterArgs` / `workerArgs`：只区分主/从（不再拆 commonArgs）。
>   
> - `rendered.master/worker`：直接可执行的完整命令串，便于 UI 一键复制和审计回放。
>   
> - 日后若扩展（如 pipeline），只需把 `version` 升到 2 并加字段即可。
>   

> ⚠️ 你的列是 `VARCHAR(500)`，若参数较多可能超长。**不改表**的前提下请注意长度；建议尽量精简参数名或把 `rendered` 的可执行串只保留一份（如只保留 `master`，worker 运行时再生成）。如果后续确实不够，再一次性把列改成 `TEXT`。

二、请求 DTO（前端不传 fixedPrefix）

```
// 新增/更新请求：只传“环境变量列表 + 主/从参数列表”
@Data
public class ModelCommandPart {
    @NotBlank private String name;
    @NotBlank private String value;
}

@Data
public class CommandArgsReq {
    // 环境变量：名称 & 值
    @NotNull @Size(max = 50)
    private List<@Valid ModelCommandPart> env;

    // 主、从 节点参数（顺序即为拼接顺序）
    @NotNull @Size(max = 100)
    private List<@NotBlank String> masterArgs;

    @NotNull @Size(max = 100)
    private List<@NotBlank String> workerArgs;
}

// 模型信息新增请求（只列与命令相关字段，其他照你原来的）
@Data
public class ModelCreateReq {
    @NotBlank private String sceneType;        // vllm / vllm_mindspore / vllm_ascend
    private String vllmMsVersion;              // 仅 mindspore 场景需要
    @NotBlank private String modelBackend;     // vllm / vllm_mindspore / vllm_ascend
    @NotBlank private String modelName;
    // ... 其他必填字段省略

    @Valid @NotNull private CommandArgsReq command; // 前端只传 env / masterArgs / workerArgs
}

```

# 三、后端固定 `fixedPrefix` 的选择策略

```
@Component
public class CommandPresetService {
    public String resolveFixedPrefix(String sceneType, String modelBackend) {
        // 你可以根据不同的类型/版本在此切换固定前缀
        // 例：vllm 家族共用:
        return "python -m vllm.entrypoints.api_server";
        // 如需区分：
        // if ("vllm_mindspore".equals(modelBackend)) return "python -m vllm_ms.entrypoints.api_server";
    }
}

```

四、拼装与存储：`CommandBuilder` 工具

```
@Component
@RequiredArgsConstructor
public class CommandBuilder {

    private final ObjectMapper om; // 你已有 Jackson Bean，已启用 JSR310 模块

    public String buildRunCommandJson(String fixedPrefix, CommandArgsReq req) {
        String envLine = renderEnv(req.getEnv());
        String master = renderFull(envLine, fixedPrefix, req.getMasterArgs());
        String worker = renderFull(envLine, fixedPrefix, req.getWorkerArgs());

        Map<String,Object> json = new LinkedHashMap<>();
        json.put("version", 1);
        json.put("fixedPrefix", fixedPrefix);
        json.put("env", req.getEnv());           // 原始对列表
        json.put("masterArgs", req.getMasterArgs());
        json.put("workerArgs", req.getWorkerArgs());
        Map<String,String> rendered = new LinkedHashMap<>();
        rendered.put("envLine", envLine);
        rendered.put("master", master);
        rendered.put("worker", worker);
        json.put("rendered", rendered);

        try {
            return om.writeValueAsString(json);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("serialize run_command failed", e);
        }
    }

    private String renderEnv(List<ModelCommandPart> env) {
        if (env == null || env.isEmpty()) return "";
        return env.stream()
            .map(kv -> "export " + shellQuoteName(kv.getName()) + "=" + shellQuoteValue(kv.getValue()) + ";")
            .collect(Collectors.joining(" "));
    }

    private String renderFull(String envLine, String fixedPrefix, List<String> args) {
        String argsJoined = String.join(" ", args.stream().map(this::safeToken).toList());
        if (envLine == null) envLine = "";
        String prefix = envLine.isBlank() ? "" : (envLine + " ");
        return (prefix + fixedPrefix + (argsJoined.isBlank() ? "" : " " + argsJoined)).trim();
    }

    // 简化的转义，至少把有空格/特殊字符的参数用引号包起来
    private String safeToken(String s) {
        if (s.matches("^[A-Za-z0-9._\\-/:=]+$")) return s;
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }

    private String shellQuoteName(String name){
        // 变量名用简单校验：只允许 A-Z0-9_，否则抛异常（避免注入）
        if (!name.matches("^[A-Z_][A-Z0-9_]*$")) {
            throw new IllegalArgumentException("Bad env name: " + name);
        }
        return name;
    }
    private String shellQuoteValue(String val){
        return safeToken(val);
    }
}

```

五、Service：新增/更新时写 JSON 到 `run_command`

```
@Service
@RequiredArgsConstructor
public class ModelInfoServiceImpl implements ModelInfoService {

    private final CommandPresetService preset;
    private final CommandBuilder commandBuilder;
    private final ModelInfoMapper modelInfoMapper;
    private final ModelInfoLogMapper logMapper;

    @Transactional
    public String create(ModelCreateReq req, String operator){
        var fixedPrefix = preset.resolveFixedPrefix(req.getSceneType(), req.getModelBackend());
        var runCommandJson = commandBuilder.buildRunCommandJson(fixedPrefix, req.getCommand());

        ModelInfoDetails po = new ModelInfoDetails();
        po.setModelInfoId(UUID.randomUUID().toString());
        // ... 其他字段赋值
        po.setRunCommand(runCommandJson);          // 关键：JSON 字符串塞进原字段
        po.setCreateOwner(operator);
        po.setUpdateOwner(operator);
        // data_status=ACTIVE 等
        modelInfoMapper.insert(po);

        // 写 INSERT 审计（old=null, new=po）
        // ...
        return po.getModelInfoId();
    }

    @Transactional
    public String updateRunCommand(String modelInfoId, CommandArgsReq args, String operator, String changeReason, String remark){
        var before = modelInfoMapper.findById(modelInfoId);
        if (before == null || before.getDataStatus() == DataStatus.DELETED){
            throw new NotFoundException("model not found: " + modelInfoId);
        }
        var fixedPrefix = preset.resolveFixedPrefix(before.getSceneType(), before.getModelBackend());
        var runCommandJson = commandBuilder.buildRunCommandJson(fixedPrefix, args);

        ModelInfoDetails patch = new ModelInfoDetails();
        patch.setModelInfoId(modelInfoId);
        patch.setRunCommand(runCommandJson);
        patch.setUpdateOwner(operator);
        modelInfoMapper.updateSelective(patch); // 用 <set> 动态 SQL，update_time=NOW()

        var after = modelInfoMapper.findById(modelInfoId);
        // 审计：记录 changed_fields 至少包含 "runCommand"
        // ...
        return modelInfoId;
    }
}

```

六、Controller：新增/专门的更新命令接口

```
@RestController
@RequestMapping("/api/models")
@RequiredArgsConstructor
public class ModelInfoController {

    private final ModelInfoService service;

    @PostMapping
    public String create(@Valid @RequestBody ModelCreateReq req,
                         @RequestHeader(value="X-Operator", required=false) String operator){
        return service.create(req, operator == null ? "system" : operator);
    }

    // 单独更新启动命令 + 环境变量
    @PutMapping("/{id}/run-command")
    public String updateRunCommand(@PathVariable("id") String id,
                                   @Valid @RequestBody CommandArgsReq req,
                                   @RequestHeader(value="X-Operator", required=false) String operator,
                                   @RequestParam(value="reason", required=false) String reason,
                                   @RequestParam(value="remark", required=false) String remark){
        return service.updateRunCommand(id, req,
                operator == null ? "system" : operator,
                Optional.ofNullable(reason).orElse("调整启动参数"),
                Optional.ofNullable(remark).orElse(""));
    }
}

```

七、Mapper（**保持原表不变**）

```
@Mapper
public interface ModelInfoMapper {

    @Insert({
        "INSERT INTO model_info_details(",
        "  model_info_id, /* ... */, run_command, /* ... */ create_owner, update_owner, data_status",
        ") VALUES (",
        "  #{modelInfoId}, /* ... */, #{runCommand}, /* ... */ #{createOwner}, #{updateOwner}, #{dataStatus}",
        ")"
    })
    int insert(ModelInfoDetails po);

    @Update({
        "<script>",
        "UPDATE model_info_details",
        "<set>",
        "  <if test='runCommand != null'> run_command = #{runCommand}, </if>",
        "  <if test='remarks    != null'> remarks     = #{remarks},    </if>",
        "  update_owner = #{updateOwner},",
        "  update_time  = NOW()",
        "</set>",
        "WHERE model_info_id = #{modelInfoId}",
        "</script>"
    })
    int updateSelective(ModelInfoDetails po);

    @Select("SELECT * FROM model_info_details WHERE model_info_id = #{id}")
    ModelInfoDetails findById(@Param("id") String id);
}

```

# 八、请求/响应示例

**新增（POST /api/models）**

```
{
  "sceneType": "vllm",
  "modelBackend": "vllm",
  "modelName": "llama3-8b",
  "command": {
    "env": [
      {"name":"CUDA_VISIBLE_DEVICES","value":"0"},
      {"name":"HF_HOME","value":"/opt/hf"}
    ],
    "masterArgs": ["--model","llama3-8b","--tp","2"],
    "workerArgs": ["--model","llama3-8b","--worker","1"]
  }
}

```

**返回**：`"8c7fdf73-4d77-4306-bb1b-e37b488f9c33"`

**更新命令（PUT /api/models/{id}/run-command?reason=扩容&remark=AB组）**

```
{
  "env": [{"name":"CUDA_VISIBLE_DEVICES","value":"0"}],
  "masterArgs": ["--model","llama3-8b","--tp","2"],
  "workerArgs": ["--model","llama3-8b","--worker","2"]
}

```

**返回**：`"8c7fdf73-4d77-4306-bb1b-e37b488f9c33"`

> 审计里 `changed_fields` 会包含 `"runCommand"`（如果你做了字段 diff 也可包含 `runCommand.rendered.worker` 等更细粒度路径）。

# 九、查询端的解析（可选）

如果前端需要把 `run_command` 解析回结构化对象，在 **Service/Controller 的查询接口**把字符串反序列化后一起返回（不改表）：

```
@Data
public class ModelInfoVO {
    // ...
    private String runCommandRaw;     // 原始 JSON 字符串
    private Map<String,Object> runCommand; // 解析后的对象，便于前端直接使用
}

public ModelInfoVO toVO(ModelInfoDetails po, ObjectMapper om){
    ModelInfoVO vo = new ModelInfoVO();
    // ...
    vo.setRunCommandRaw(po.getRunCommand());
    try {
        vo.setRunCommand(om.readValue(po.getRunCommand(), new TypeReference<>(){}));
    } catch (Exception e) {
        vo.setRunCommand(Map.of("parseError", e.getMessage()));
    }
    return vo;
}

```

# 十、几点工程化提醒

- **长度控制**：`VARCHAR(500)` 易超长；如果必须严格不改表，建议把 `rendered.worker` 省略，运行时按 `workerArgs` 现拼。
  
- **安全**：对 `env.name` 做正则白名单（已在 `shellQuoteName` 中处理），避免命令注入；对参数值统一安全包裹。
  
- **可测试性**：把 `CommandBuilder` 做成独立 Bean，给它加单测覆盖各种边界（空 env、含空格参数、引号转义）。
  
- **多场景**：如 `vllm_mindspore` 固定前缀不同，集中在 `CommandPresetService` 切换即可。
  
- **审计**：`run_command` 改为 JSON 后，日志表 `old_data/new_data` 仍是全对象 JSON，`changed_fields` 可只写 `"runCommand"`，或做更细粒度 JSON diff（比如只标出 `rendered.worker` 变化）。
  

---

这样你无需改动表结构，就把“**固定前缀后端决定**、**环境变量由后端拼 export**、**主/从命令分开**”全部落地到一个 `run_command` JSON 里，**前端简单**、**后端可控**、**审计清晰**、**未来可演进**。

为什么run_command内存储rendered作用是什么，新的请求示例中为什么"masterArgs": ["--model","llama3-8b","--tp","2"],是这种形式，而不是保持前面"masterArgs": [
 {"name":"model","value":"/mnt/ckpt/llama3-8b"},
 {"name":"tensor-parallel-size","value":"2"},
 {"name":"enable-chunked-prefill"} // flag
 ],这种形式

## 1) 为啥在 `run_command` 里要存 `rendered`

把 **最终可直接执行的命令**（含 prefix、env、参数、主/从差异）以字符串快照 `rendered` 存起来，有这些好处：

- **可审计/可复现**：日志里能一眼看到当时实际跑了什么；过一段时间模型版本、参数含义变了，也能复跑那一版。
  
- **避免二次解析不一致**：以后后端拼装逻辑升级（别名、缺省值、转义规则变化），历史任务仍以当时的 `rendered` 为准。
  
- **便于对比**：改动评审看 diff，`rendered` 一比就知道哪几个参数变了。
  
- **执行性能**：下发到 agent 时无需再做模板渲染，直接 `execve(argv)` 或 `sh -lc "$rendered"`。
  
- **灾难恢复**：消息重放/补偿时不依赖业务代码版本，只取 `rendered` 复跑。
  

> 简单说：结构化参数用于**理解/检索/校验**，`rendered` 字符串用于**落地执行**与**审计快照**。

## 2) 为啥 `masterArgs` 变成 `["--model","llama3-8b","--tp","2"]` 这种 token 数组？

把参数存成 **argv token 列表** 比 KV 对象更贴近进程实际启动形态，有这些优点：

- **顺序保真**：有些 CLI 对顺序敏感（先后覆盖、`--` 分隔），token 列表天然保持顺序。
  
- **天然支持 flag**：没值的开关（如 `--enable-chunked-prefill`）直接一个 token 即可；KV 需要特殊约定。
  
- **转义/空格**：带空格/引号的值（路径、复合参数）在 token 化时就处理好了，避免渲染期再猜怎么 quote。
  
- **零歧义映射**：有的参数既有 `-t` 又有 `--tp`，KV 形式还得决定用哪个 key；token 不需要映射。
  
- **直接执行**：`ProcessBuilder(commandArgv)`/`execve(argv)` 无需再拼字符串。
  

KV 形式也有好处（易校验、易做表单），所以**最实用的做法**是：**接口兼容两种输入**，后端统一**归一化为 token 列表**，并额外生成 `rendered` 存档。这样既不丢结构化语义，也不牺牲执行确定性。

## 3) 在“不改表，直接把 JSON 放进 `run_command` 字段”的设计建议

沿用你当前表结构（`run_command` 是 `varchar(500)` 或更大），存 JSON。建议后端生成并落库以下结构（示例，主/从前缀一致）：

```
{
  "fixedPrefix": "python -m vllm.entrypoints.openai.api_server",
  "env": {
    "kv": {
      "CUDA_VISIBLE_DEVICES": "0,1",
      "HF_HOME": "/mnt/cache/hf"
    },
    "argv": ["export","CUDA_VISIBLE_DEVICES=0,1","HF_HOME=/mnt/cache/hf"],
    "rendered": "export CUDA_VISIBLE_DEVICES=0,1 HF_HOME=/mnt/cache/hf"
  },
  "master": {
    "argsKV": [
      {"name":"model","value":"/mnt/ckpt/llama3-8b"},
      {"name":"tensor-parallel-size","value":"2"}
    ],
    "argv": ["--model","/mnt/ckpt/llama3-8b","--tensor-parallel-size","2"],
    "rendered": "python -m vllm.entrypoints.openai.api_server --model /mnt/ckpt/llama3-8b --tensor-parallel-size 2"
  },
  "worker": {
    "argsKV": [
      {"name":"model","value":"/mnt/ckpt/llama3-8b"},
      {"name":"tensor-parallel-size","value":"2"},
      {"name":"worker","value":"1"}
    ],
    "argv": ["--model","/mnt/ckpt/llama3-8b","--tensor-parallel-size","2","--worker","1"],
    "rendered": "python -m vllm.entrypoints.openai.api_server --model /mnt/ckpt/llama3-8b --tensor-parallel-size 2 --worker 1"
  },
  "rendered": {
    "master": "export CUDA_VISIBLE_DEVICES=0,1 HF_HOME=/mnt/cache/hf && python -m vllm.entrypoints.openai.api_server --model /mnt/ckpt/llama3-8b --tensor-parallel-size 2",
    "worker": "export CUDA_VISIBLE_DEVICES=0,1 HF_HOME=/mnt/cache/hf && python -m vllm.entrypoints.openai.api_server --model /mnt/ckpt/llama3-8b --tensor-parallel-size 2 --worker 1"
  }
}

```

要点：

- 前端**不传** `fixedPrefix`，后端写死。
  
- 环境变量前端传 **名称 + 值** 列表；后端拼 `export` 的 `argv` 与 `rendered`。
  
- 启动命令**只拆** `masterArgs` / `workerArgs`；不设 `commonArgs`，由后端在渲染期自动把公共项复用到两侧。
  
- 入库 JSON 里同时保留 `argsKV`（便于人看/二次编辑）、`argv`（确定性执行）、以及最终 `rendered`（快照）。
  

## 4) 接口层如何兼容两种入参

**请求体（两种都支持）**：

- 形式 A：结构化 KV（你之前那种）

```
{
  "sceneType": "vllm",
  "env": [
    {"name":"CUDA_VISIBLE_DEVICES","value":"0,1"}
  ],
  "masterArgs": [
    {"name":"model","value":"/mnt/ckpt/llama3-8b"},
    {"name":"tensor-parallel-size","value":"2"},
    {"name":"enable-chunked-prefill": true}
  ],
  "workerArgs": [
    {"name":"model","value":"/mnt/ckpt/llama3-8b"},
    {"name":"tensor-parallel-size","value":"2"},
    {"name":"worker","value":"1"}
  ]
}

```

形式 B：直接传 token 数组

`"sceneType": "vllm",   "env": [{"name":"CUDA_VISIBLE_DEVICES","value":"0,1"}],   "masterArgs": ["--model","/mnt/ckpt/llama3-8b","--tp","2","--enable-chunked-prefill"],   "workerArgs": ["--model","/mnt/ckpt/llama3-8b","--tp","2","--worker","1"]`

**后端处理**：

- 如果是 KV：做一次 **规范化映射**（如把 `tensor-parallel-size` 映到 `--tensor-parallel-size`，flag 布尔 `true` → 仅生成 `--flag`）。
  
- 不管哪种入参，都产出 **统一的 `argv`**，再连同 `argsKV` 一起塞进 `run_command` JSON，并生成 `rendered`。
  
- 渲染规则固定化（单测覆盖）：
  
  - `fixedPrefix` + `argv` join 时负责合理 quote（含空格/特殊字符）。
    
  - env 的 `rendered` 形如 `export K=V H=V2`；Windows/非 bash 情况留扩展点。
    

5. 代码拼装（伪代码）

```
List<String> normalizeKVToArgv(List<ArgKV> kv, Map<String,String> mapping) {
  List<String> argv = new ArrayList<>();
  for (ArgKV a : kv) {
    String flag = mapping.getOrDefault(a.getName(), "--" + a.getName());
    if (a.isFlagTrue()) {
      argv.add(flag);
    } else if (a.getValue() != null) {
      argv.add(flag); argv.add(a.getValue());
    }
  }
  return argv;
}

String renderCmd(String prefix, String envRendered, List<String> argv) {
  String cmd = prefix + " " + shellJoin(argv); // shellJoin 负责安全 quote
  return envRendered.isBlank() ? cmd : envRendered + " && " + cmd;
}

String renderEnvExport(Map<String,String> env) {
  // 输出：export K=V K2=V2
  return "export " + env.entrySet().stream()
      .map(e -> e.getKey() + "=" + e.getValue())
      .collect(Collectors.joining(" "));
}

```

## 6) 小结

- `rendered` 是**执行快照**，为审计、复现、重放和性能服务；必须存。
  
- `masterArgs`/`workerArgs` 用 **argv token 数组**能消除顺序/flag/转义歧义，直接可执行。
  
- 兼容两种入参（KV 或 token），后端统一**归一化→渲染**，把 `argsKV`（可读）、`argv`（可执行）和 `rendered`（快照）**全部**存入 `run_command` 的 JSON 字段，不改表即可落地。
  

太好了，我们就按你说的“基础形式 A：结构化 KV 进行数据处理”，给一份**从 Controller → Service → 渲染工具 → Mapper/实体**的完整样例。要点如下：

- 前端**只传结构化 KV**：`envVars[].{name,value}`、`masterArgs[].{name,value,flag}`、`workerArgs[].{name,value,flag}`。
  
- 后端**写死固定前缀**（`fixedPrefix`），并把结构化 KV **渲染成可执行字符串**，与原始结构一并放进 `run_command` 的 **JSON**，最终整个 JSON 作为字符串写入原有字段（不改表）。
  
- 主/从（master/worker）共用同一 `fixedPrefix`，只在 args 上区分。
  
- `flag=true` 或 `value=null/""` → 渲染为 `--name`；否则渲染为 `--name value`。
  
- env 以 `export NAME=VALUE;` 形式拼接，放在命令最前。
  

> 下面代码以包名 `org.example`、表 `model_info_details` 为例；Jackson 已启用 JSR310 模块与 ISO 8601 输出，避免 LocalDateTime 报错。

目录结构（节选）

```
org/example
 ├─ controller/ModelInfoController.java
 ├─ service/ModelInfoService.java
 ├─ service/impl/ModelInfoServiceImpl.java
 ├─ mapper/ModelInfoMapper.java
 ├─ domain/ModelInfoDetails.java
 ├─ dto/ModelInfoCreateReq.java
 ├─ dto/Kv.java
 ├─ dto/ArgKv.java
 ├─ util/CliRenderers.java
 ├─ util/JsonUtils.java
 └─ config/JacksonConfig.java

```

DTO：请求体（结构化 KV）

```
package org.example.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class ModelInfoCreateReq {
    // 业务基本字段（示例，按需扩展/校验）
    @NotBlank private String sceneType;         // vllm / vllm_mindspore / vllm_ascend
    @NotBlank private String vllmMsVersion;     // 例如 "2.4.1"
    @NotBlank private String envType;           // GPU / 910B / 300iduo
    @NotBlank private String modelName;         // "llama3-8b"
    private String ckptPath;                    // /mnt/ckpt/xxx
    private String remarks;

    // 结构化 KV
    @NotNull private List<Kv> envVars;          // 环境变量：NAME / VALUE
    @NotNull private List<ArgKv> masterArgs;    // 主节点参数
    @NotNull private List<ArgKv> workerArgs;    // 从节点参数
}

```

```
package org.example.dto;

import lombok.Data;

/** 一般的 KV（环境变量用） */
@Data
public class Kv {
    private String name;   // 变量名，如 CUDA_VISIBLE_DEVICES
    private String value;  // 变量值，如 0
}

```

```
package org.example.dto;

import lombok.Data;

/** CLI 参数的 KV */
@Data
public class ArgKv {
    private String name;    // 参数名（不含前缀），如 model、tensor-parallel-size
    private String value;   // 参数值，可空（为空时若flag=true渲染为 --name）
    private Boolean flag;   // 是否为布尔开关（true→仅 "--name"）
}

```

## 渲染工具：把结构化 KV → 字符串，并组装 run_command JSON

```
package org.example.util;

import org.example.dto.ArgKv;
import org.example.dto.Kv;

import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

public final class CliRenderers {
    private CliRenderers() {}

    /** 渲染环境变量：export NAME=VALUE; export XXX=YYY; */
    public static String renderEnvExports(List<Kv> envs) {
        if (envs == null || envs.isEmpty()) return "";
        StringJoiner sj = new StringJoiner(" ");
        for (Kv kv : envs) {
            if (kv == null || isBlank(kv.getName())) continue;
            String v = kv.getValue() == null ? "" : kv.getValue();
            sj.add("export " + kv.getName() + "=" + v + ";");
        }
        return sj.toString().trim();
    }

    /** 渲染 CLI 参数：--name value / --flag */
    public static String renderArgs(List<ArgKv> args) {
        if (args == null || args.isEmpty()) return "";
        StringJoiner sj = new StringJoiner(" ");
        for (ArgKv a : args) {
            if (a == null || isBlank(a.getName())) continue;
            boolean isFlag = Boolean.TRUE.equals(a.getFlag());
            String key = "--" + a.getName().trim();
            if (isFlag || isBlank(a.getValue())) {
                sj.add(key);
            } else {
                sj.add(key + " " + a.getValue().trim());
            }
        }
        return sj.toString().trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}

```

Jackson 配置 & JSON 工具

```
package org.example.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConfig {
    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper om = new ObjectMapper();
        om.registerModule(new JavaTimeModule());
        om.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        om.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        return om;
    }
}

```

```
package org.example.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JsonUtils {
    private final ObjectMapper objectMapper;

    public String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON serialize failed", e);
        }
    }
}

```

实体 & Mapper（只展示关键字段）

```
package org.example.domain;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ModelInfoDetails {
    private String modelInfoId;      // uuid（外部生成）
    private String vllmMsVersion;
    private String sceneType;
    private String sceneDescription;
    private String ckptPath;
    private String envType;
    private String modelName;
    private Long   nodeNum;
    private String vllmBuild;
    private String modelBackend;
    private String executionModel;
    private String parallel;

    private String envVariable;      // 这里也可以继续保留原字符串（可选）
    private String runCommand;       // JSON（字符串）

    private String remarks;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private String createOwner;
    private String updateOwner;
    private String ownerGroup;
    private String dataStatus;
}

```

```
package org.example.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.example.domain.ModelInfoDetails;

@Mapper
public interface ModelInfoMapper {
    @Insert({
      "INSERT INTO model_info_details(",
      " model_info_id, vllm_ms_version, scene_type, scene_description, ckpt_path,",
      " env_type, model_name, node_num, vllm_build, model_backend, execution_model, parallel,",
      " env_variable, run_command, remarks, create_owner, update_owner, owner_group, data_status, create_time, update_time",
      ") VALUES (",
      " #{modelInfoId}, #{vllmMsVersion}, #{sceneType}, #{sceneDescription}, #{ckptPath},",
      " #{envType}, #{modelName}, #{nodeNum}, #{vllmBuild}, #{modelBackend}, #{executionModel}, #{parallel},",
      " #{envVariable}, #{runCommand}, #{remarks}, #{createOwner}, #{updateOwner}, #{ownerGroup}, #{dataStatus}, NOW(), NOW()",
      ")"
    })
    int insert(ModelInfoDetails po);
}

```

Service：固定前缀写死 + 渲染 + 组装 run_command JSON

```
package org.example.service;

import org.example.dto.ModelInfoCreateReq;

public interface ModelInfoService {
    String create(ModelInfoCreateReq req, String operator);
}

```

```
package org.example.service.impl;

import lombok.RequiredArgsConstructor;
import org.example.domain.ModelInfoDetails;
import org.example.dto.ModelInfoCreateReq;
import org.example.mapper.ModelInfoMapper;
import org.example.service.ModelInfoService;
import org.example.util.CliRenderers;
import org.example.util.JsonUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ModelInfoServiceImpl implements ModelInfoService {

    private final ModelInfoMapper modelInfoMapper;
    private final JsonUtils json;

    /** 后端写死固定前缀（主/从一致） */
    private static final String FIXED_PREFIX = "python -m vllm.entrypoint.api_server";

    @Override
    @Transactional
    public String create(ModelInfoCreateReq req, String operator) {
        // 1) 渲染 env & args
        String envRendered     = CliRenderers.renderEnvExports(req.getEnvVars());
        String masterArgsStr   = CliRenderers.renderArgs(req.getMasterArgs());
        String workerArgsStr   = CliRenderers.renderArgs(req.getWorkerArgs());

        // 2) 组装最终可执行串（先 export，再 fixedPrefix，再 args）
        String renderedMaster  = joinNonBlank(envRendered, FIXED_PREFIX, masterArgsStr);
        String renderedWorker  = joinNonBlank(envRendered, FIXED_PREFIX, workerArgsStr);

        // 3) 构造 run_command JSON（既保留结构化，也提供 rendered）
        Map<String, Object> runCmd = new LinkedHashMap<>();
        runCmd.put("fixedPrefix", FIXED_PREFIX);

        Map<String, Object> env = new LinkedHashMap<>();
        env.put("kv", req.getEnvVars());
        env.put("rendered", envRendered);
        runCmd.put("env", env);

        Map<String, Object> master = new LinkedHashMap<>();
        master.put("kv", req.getMasterArgs());
        master.put("rendered", masterArgsStr);
        runCmd.put("masterArgs", master);

        Map<String, Object> worker = new LinkedHashMap<>();
        worker.put("kv", req.getWorkerArgs());
        worker.put("rendered", workerArgsStr);
        runCmd.put("workerArgs", worker);

        Map<String, Object> rendered = new LinkedHashMap<>();
        rendered.put("master", renderedMaster);
        rendered.put("worker", renderedWorker);
        runCmd.put("rendered", rendered);

        // 4) 入库实体
        ModelInfoDetails po = new ModelInfoDetails();
        po.setModelInfoId(UUID.randomUUID().toString());
        po.setVllmMsVersion(req.getVllmMsVersion());
        po.setSceneType(req.getSceneType());
        po.setEnvType(req.getEnvType());
        po.setModelName(req.getModelName());
        po.setCkptPath(req.getCkptPath());
        po.setRemarks(req.getRemarks());

        // 可选：保留 env 的渲染结果到 env_variable（兼容旧字段）
        po.setEnvVariable(envRendered);

        // 关键：整段 JSON 字符串写入 run_command（不改表）
        po.setRunCommand(json.toJson(runCmd));

        po.setCreateOwner(operator);
        po.setUpdateOwner(operator);
        po.setOwnerGroup("PUBLIC");
        po.setDataStatus("ACTIVE");

        modelInfoMapper.insert(po);
        return po.getModelInfoId();
    }

    private static String joinNonBlank(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                if (!sb.isEmpty()) sb.append(' ');
                sb.append(p.trim());
            }
        }
        return sb.toString();
    }
}

```

Controller：创建接口

```
package org.example.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.dto.ModelInfoCreateReq;
import org.example.service.ModelInfoService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/models")
@RequiredArgsConstructor
public class ModelInfoController {

    private final ModelInfoService modelInfoService;

    @PostMapping
    public String create(@RequestBody @Valid ModelInfoCreateReq req,
                         @RequestHeader(value = "X-Operator", required = false) String operator) {
        String op = (operator == null || operator.isBlank()) ? "u001" : operator;
        return modelInfoService.create(req, op);
    }
}

```

示例请求（“基础形式 A：结构化 KV”，不传 fixedPrefix）

```
POST /api/models
Content-Type: application/json
X-Operator: u001

{
  "sceneType": "vllm_mindspore",
  "vllmMsVersion": "2.4.1",
  "envType": "GPU",
  "modelName": "llama3-8b",
  "ckptPath": "/mnt/ckpt/llama3-8b",
  "remarks": "新增-实验A",
  "envVars": [
    {"name": "CUDA_VISIBLE_DEVICES", "value": "0,1"},
    {"name": "HF_HOME", "value": "/mnt/hf"}
  ],
  "masterArgs": [
    {"name":"model", "value":"/mnt/ckpt/llama3-8b"},
    {"name":"tensor-parallel-size", "value":"2"},
    {"name":"enable-chunked-prefill", "flag": true}
  ],
  "workerArgs": [
    {"name":"model", "value":"/mnt/ckpt/llama3-8b"},
    {"name":"tensor-parallel-size", "value":"2"}
  ]
}

```

**入库 `run_command`（示例）** 将是类似这样的 JSON 字符串（字段顺序可能不同）：

```
{
  "fixedPrefix": "python -m vllm.entrypoint.api_server",
  "env": {
    "kv":[{"name":"CUDA_VISIBLE_DEVICES","value":"0,1"},{"name":"HF_HOME","value":"/mnt/hf"}],
    "rendered":"export CUDA_VISIBLE_DEVICES=0,1; export HF_HOME=/mnt/hf;"
  },
  "masterArgs": {
    "kv":[{"name":"model","value":"/mnt/ckpt/llama3-8b"},{"name":"tensor-parallel-size","value":"2"},{"name":"enable-chunked-prefill","flag":true}],
    "rendered":"--model /mnt/ckpt/llama3-8b --tensor-parallel-size 2 --enable-chunked-prefill"
  },
  "workerArgs": {
    "kv":[{"name":"model","value":"/mnt/ckpt/llama3-8b"},{"name":"tensor-parallel-size","value":"2"}],
    "rendered":"--model /mnt/ckpt/llama3-8b --tensor-parallel-size 2"
  },
  "rendered": {
    "master":"export CUDA_VISIBLE_DEVICES=0,1; export HF_HOME=/mnt/hf; python -m vllm.entrypoint.api_server --model /mnt/ckpt/llama3-8b --tensor-parallel-size 2 --enable-chunked-prefill",
    "worker":"export CUDA_VISIBLE_DEVICES=0,1; export HF_HOME=/mnt/hf; python -m vllm.entrypoint.api_server --model /mnt/ckpt/llama3-8b --tensor-parallel-size 2"
  }
}

```

## 为什么要有 `rendered`？

- **结构化（kv）**便于**后续编辑、回显、对比审计**；
  
- **rendered**便于**直接执行**与**人眼核对**；
  
- 两者共存，既**易维护**又**可落地执行**。主/从分开渲染，方便多角色启动。
  

---

如果你要把 `fixedPrefix` 改得更贴合你环境（比如 vLLM MS/Ascend 的具体 entrypoint），只需修改 `FIXED_PREFIX` 常量即可；其余流程不变。需要我再补一条“更新接口”的同风格实现（只改 master/workerArgs/envVars 并重渲染 `run_command`）也没问题。
