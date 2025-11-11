一、整体调用链设计（先有一张脑图）

以 测试机 A（跑推理服务），测试平台 B（你现有 LLM 测试系统），PowerJob 集群 S 为例：

PowerJob-Server（S）

按 CRON / 手动触发一个任务：start-llm-service.

Worker（部署在测试机 A 上）

接到任务 → 使用官方处理器 ShellProcessor 或 PythonProcessor，执行启动脚本。

启动脚本做的事：

杀掉旧进程（如果存在）

清理临时目录 / 日志（可选）

使用 nohup 或 systemd 拉起推理服务（vLLM / MindIE / 你自己的 server）

将启动日志写到某个固定文件（便于你后续采集）

（可选）脚本最后调用 测试平台 B 的 HTTP 回调：

告知：服务已拉起、端口、模型信息等

PowerJob 收到 Shell/Python 的退出码：

0 → 任务成功

非 0 → 任务失败，触发告警 / 重试

你关心的几个点：

“在哪里写启动命令？” → Shell 或 Python 脚本中

“如何配到 PowerJob 任务里？” → Console 填 官方处理器类名 + 脚本内容 / 下载 URL

“如何串到测试平台？” → 脚本最后调用你的 HTTP 接口（或者测试平台定时去探测服务）

下面直接上两个版本：Shell 方案 和 Python 方案。


二、方案 A：用 Shell 任务拉起推理服务
1. Worker 侧准备（测试机 A）

前提：测试机上 Worker 已连上 PowerJob-Server。

另外需要：

Linux 环境（有 bash）

推理服务启动脚本 / 启动命令，比如：

/opt/llm/venv/bin/python -m vllm.entrypoints.openai.api_server \
  --model /data/models/qwen-7b \
  --port 8000 \
  --tensor-parallel-size 1 \
  > /opt/llm/logs/qwen-7b-`date +%F_%T`.log 2>&1 &

我们会把这些包装到一个可复用的 Shell 脚本里。

2. 写启动脚本（示例）

假设你在测试机上存一个脚本：/opt/llm/scripts/start_qwen.sh：

#!/usr/bin/env bash
set -e

MODEL_NAME="qwen-7b"
MODEL_PATH="/data/models/qwen-7b"
PORT=8000
LOG_DIR="/opt/llm/logs"
PYTHON_BIN="/opt/llm/venv/bin/python"
CALLBACK_URL="$1"   # 可选：从参数里拿回调地址

mkdir -p "${LOG_DIR}"

echo "[`date`] [INFO] try to stop existing ${MODEL_NAME} service..."

# 杀掉已有进程（视你实际服务名调整匹配条件）
ps aux | grep "${MODEL_PATH}" | grep -v grep | awk '{print $2}' | xargs -r kill -9 || true

sleep 2

LOG_FILE="${LOG_DIR}/${MODEL_NAME}-$(date +%F_%H%M%S).log"

echo "[`date`] [INFO] start ${MODEL_NAME} on port ${PORT}, log=${LOG_FILE}"

nohup "${PYTHON_BIN}" -m vllm.entrypoints.openai.api_server \
  --model "${MODEL_PATH}" \
  --port "${PORT}" \
  --tensor-parallel-size 1 \
  > "${LOG_FILE}" 2>&1 &

PID=$!

sleep 5

if ps -p "${PID}" > /dev/null 2>&1; then
  echo "[`date`] [INFO] ${MODEL_NAME} started successfully, pid=${PID}"
  # 可选：回调你的测试平台
  if [ -n "${CALLBACK_URL}" ]; then
    curl -X POST "${CALLBACK_URL}" \
      -H "Content-Type: application/json" \
      -d "{\"model\":\"${MODEL_NAME}\",\"port\":${PORT},\"pid\":${PID},\"log_file\":\"${LOG_FILE}\"}" || true
  fi
  exit 0
else
  echo "[`date`] [ERROR] failed to start ${MODEL_NAME}, check log: ${LOG_FILE}"
  exit 1
fi


这个脚本是 真正干活的人，PowerJob 只是帮你定时执行它。

记得：

chmod +x /opt/llm/scripts/start_qwen.sh


3. PowerJob Console 配置（Shell 任务）
3.1 基本信息
在 任务管理 → 新增任务：


应用：llm-test-platform（你之前在 PowerJob 中配置的 appName）


任务名称：start-qwen7b-shell


执行类型：单机执行（选绑定的 Worker：测试机 A）


处理器类型：普通 Java 处理器


处理器信息（类名）：
tech.powerjob.official.processors.impl.script.ShellProcessor




这个类名来自官方 powerjob-official-processors 包，是 Shell 处理器。



调度配置：


比如 CRON：0 0 2 * * ?（每天凌晨 2 点拉起一次，或者你选“手动触发”即可）




3.2 任务参数（脚本内容 / 命令）
ShellProcessor 支持你在“任务参数”里写脚本内容。我们用最简方式：直接调用上面那个脚本。
在“任务参数”填写：
#!/usr/bin/env bash
set -e

CALLBACK_URL="https://your-test-platform/api/report/llm-start"

bash /opt/llm/scripts/start_qwen.sh "${CALLBACK_URL}"

或者如果你不想搞回调，就简单一点：
#!/usr/bin/env bash
set -e

bash /opt/llm/scripts/start_qwen.sh


这样，PowerJob 下发任务 → Worker 调用 ShellProcessor → 在 Worker 所在机器执行这段脚本。


4. 测试平台（B）的对接方式
你有两个可选路径：


脚本回调测试平台（上面已经示例了 curl POST CALLBACK_URL）
测试平台里提供一个接口，例如：
POST /api/report/llm-start
Content-Type: application/json

{
  "model": "qwen-7b",
  "port": 8000,
  "pid": 12345,
  "log_file": "/opt/llm/logs/qwen-7b-2025-11-12_010203.log"
}

后端拿到这些信息：


更新“环境/服务实例”表


标记当前测试机的某个模型服务已就绪




测试平台主动探测
不用回调，只要：


确定启动端口固定（如 8000）


测试平台发起 http://test-machine-a:8000/v1/models 之类的探活
然后通过 PowerJob 的执行成功/失败 + 平台的探活状态联合判断。





三、方案 B：用 Python 任务拉起推理服务
如果你本身在测试机上已经大量用 Python 来管理环境，可以把整个启动逻辑写成一个 Python 脚本。
1. Python 启动脚本（示例）
假设你的 Python 脚本位于：/opt/llm/scripts/start_qwen.py：
#!/usr/bin/env python3
import os
import subprocess
import sys
import time
import json
import requests

MODEL_NAME = "qwen-7b"
MODEL_PATH = "/data/models/qwen-7b"
PORT = 8000
LOG_DIR = "/opt/llm/logs"
PYTHON_BIN = "/opt/llm/venv/bin/python"

CALLBACK_URL = None
if len(sys.argv) > 1:
    CALLBACK_URL = sys.argv[1]

os.makedirs(LOG_DIR, exist_ok=True)

print(f"[INFO] try to stop existing {MODEL_NAME} service...")

# kill old process
ps = subprocess.Popen(["ps", "aux"], stdout=subprocess.PIPE, text=True)
out, _ = ps.communicate()
for line in out.splitlines():
    if MODEL_PATH in line and "grep" not in line and "start_qwen.py" not in line:
        pid = int(line.split()[1])
        print(f"[INFO] kill old pid={pid}")
        try:
            os.kill(pid, 9)
        except Exception as e:
            print(f"[WARN] kill failed: {e}")

time.sleep(2)

log_file = os.path.join(LOG_DIR, f"{MODEL_NAME}-{time.strftime('%Y-%m-%d_%H%M%S')}.log")

print(f"[INFO] start {MODEL_NAME} on port {PORT}, log={log_file}")

cmd = [
    PYTHON_BIN, "-m", "vllm.entrypoints.openai.api_server",
    "--model", MODEL_PATH,
    "--port", str(PORT),
    "--tensor-parallel-size", "1",
]

with open(log_file, "w") as lf:
    proc = subprocess.Popen(cmd, stdout=lf, stderr=lf)

time.sleep(5)

# check process
if proc.poll() is None:
    print(f"[INFO] {MODEL_NAME} started, pid={proc.pid}")
    # optional callback
    if CALLBACK_URL:
        data = {
            "model": MODEL_NAME,
            "port": PORT,
            "pid": proc.pid,
            "log_file": log_file
        }
        try:
            requests.post(CALLBACK_URL, json=data, timeout=5)
        except Exception as e:
            print(f"[WARN] callback failed: {e}")
    sys.exit(0)
else:
    print(f"[ERROR] failed to start {MODEL_NAME}, check log: {log_file}")
    sys.exit(1)

记得：
chmod +x /opt/llm/scripts/start_qwen.py
# 确保 python 环境里有 requests 库
/opt/llm/venv/bin/pip install requests


2. PowerJob Console 配置（Python 任务）
这次在任务管理中新建一个任务：


名称：start-qwen7b-python


执行类型：单机


处理器类型：普通 Java 处理器


处理器信息（类名）：
tech.powerjob.official.processors.impl.script.PythonProcessor



调度配置：同样可以选手动 / CRON


任务参数（Python 脚本内容）
PythonProcessor 的任务参数可以是完整脚本，也可以是“下载链接 + 参数”。这里用直接调用本地脚本的方式：
#!/usr/bin/env python3
import subprocess
import sys

CALLBACK_URL = "https://your-test-platform/api/report/llm-start"

cmd = [
    "/opt/llm/venv/bin/python",
    "/opt/llm/scripts/start_qwen.py",
    CALLBACK_URL,
]

# 直接把子进程退出码作为本任务结果
result = subprocess.run(cmd)
sys.exit(result.returncode)


这个“任务参数脚本”就相当于一个小 wrapper，真正逻辑还是在 start_qwen.py 里。


四、Console 里还可以顺手做的优化配置
不论 Shell 还是 Python 方案，都推荐在任务配置里多做几个“实战化”设置：


任务参数模板化
可以改成接收 JSON 参数，让脚本根据参数决定模型名/端口，比如：


PowerJob 任务参数填：
{
  "model_name": "qwen-7b",
  "model_path": "/data/models/qwen-7b",
  "port": 8000,
  "callback_url": "https://your-test-platform/api/report/llm-start"
}



Shell/Python 脚本从 JobParams（通过 PowerJob 的上下文注入）读取 JSON → 高度通用化。
如果你需要，我可以给一版 读取 JobParams 的 Shell/Python 版本。




失败重试


失败重试次数：比如 2 次


失败间隔：60 秒


注意脚本要幂等（能多次 kill & 重启）




超时时间


启动服务一般 10～60 秒内能完，任务超时可以设 120 秒，避免任务挂很久。


若超时后服务却已经起来了，你那边的探活逻辑仍能兜底。




告警配置


对这个“拉起推理服务”的任务配置失败告警（邮件 / Webhook）


PowerJob + 你的测试平台共同形成监控闭环：


PowerJob：负责“我有没有成功把命令发出去且退出码=0”


平台：负责“服务健康度 / QPS / 延迟”







五、如果你要做“多模型 / 多环境”的升级版
后面你可以把这个模式升级为更通用的“服务管理任务”：


用一个 统一的 Shell/Python 脚本 + JSON 参数，支持：


action: start / stop / restart


model_name, model_path, port


env: dev / test / prod




多个 PowerJob 任务只是 不同参数配置 而已：


start-qwen7b-dev


start-qwen72b-test


restart-llama3-prod




再通过 PowerJob 的 工作流 把“拉起服务 → 预热 → 跑一组 smoke test → 通知结果”串成一个 DAG。


如果你愿意，我下一步可以：


直接给你一套“读取 JobParams 的 PythonProcessor 示例”：


从 TaskContext.getJobParams() 拿 JSON


按参数决定启动哪个模型 / 端口


完整 Worker 代码 + Console 配置说明。


一套“读取 JobParams 的 PythonProcessor 示例


好，直接给你一整套「JobParams(JSON) → Java Processor → Python 脚本启动推理服务」的例子，你可以直接抄到项目里改。

小结一句：
Console 里改 JSON 配置就能决定拉起哪个模型 / 端口 / 脚本，Java Processor 里用 TaskContext.getJobParams() 解析，然后用 ProcessBuilder 调 Python 脚本。

1. Worker 端：自定义 “PythonProcessor” 读取 JobParams

这里我用「方法级注解」的写法：

Bean 名：llmPythonProcessor

方法名：pythonLauncher

Console 里可以写处理器：llmPythonProcessor#pythonLauncher 或 全限定类名#pythonLauncher 
CSDN
+1

package com.example.powerjob.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tech.powerjob.worker.core.processor.ProcessResult;
import tech.powerjob.worker.core.processor.TaskContext;
import tech.powerjob.worker.log.OmsLogger;
import tech.powerjob.worker.core.processor.sdk.PowerJobHandler;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component("llmPythonProcessor")
public class LlmPythonProcessor {

    private static final Logger log = LoggerFactory.getLogger(LlmPythonProcessor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 处理器信息可配置为：
     *   1）llmPythonProcessor#pythonLauncher
     *   2）com.example.powerjob.demo.LlmPythonProcessor#pythonLauncher
     */
    @PowerJobHandler(name = "pythonLauncher")
    public ProcessResult pythonLauncher(TaskContext context) throws Exception {
        OmsLogger omsLogger = context.getOmsLogger();

        String jobParams = context.getJobParams();
        omsLogger.info("[pythonLauncher] raw jobParams = {}", jobParams);

        if (StringUtils.isBlank(jobParams)) {
            omsLogger.warn("jobParams is blank, use default config");
            jobParams = "{}";
        }

        JsonNode root = MAPPER.readTree(jobParams);

        String modelName  = root.path("modelName").asText("qwen-7b");
        String modelPath  = root.path("modelPath").asText("/data/models/qwen-7b");
        int    port       = root.path("port").asInt(8000);
        String pythonBin  = root.path("pythonBin").asText("/opt/llm/venv/bin/python");
        String scriptPath = root.path("scriptPath").asText("/opt/llm/scripts/start_llm.py");
        String callbackUrl = root.path("callbackUrl").asText("");

        omsLogger.info("parsed params: modelName={}, modelPath={}, port={}, pythonBin={}, scriptPath={}, callbackUrl={}",
                modelName, modelPath, port, pythonBin, scriptPath, callbackUrl);

        // 组装命令：python start_llm.py --model-name xxx --model-path xxx --port xxx [--callback-url xxx]
        List<String> cmd = new ArrayList<>();
        cmd.add(pythonBin);
        cmd.add(scriptPath);
        cmd.add("--model-name");
        cmd.add(modelName);
        cmd.add("--model-path");
        cmd.add(modelPath);
        cmd.add("--port");
        cmd.add(String.valueOf(port));
        if (StringUtils.isNotBlank(callbackUrl)) {
            cmd.add("--callback-url");
            cmd.add(callbackUrl);
        }

        omsLogger.info("exec python cmd: {}", cmd);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        // 也可以在这里设置环境变量：pb.environment().put("XXX", "value");
        pb.redirectErrorStream(true);

        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
                // 打到 PowerJob 在线日志里
                omsLogger.info(line);
            }
        }

        int exitCode = process.waitFor();
        boolean success = (exitCode == 0);

        if (!success) {
            log.error("python script failed, exitCode={}, output:\n{}", exitCode, output);
            return new ProcessResult(false,
                    "python exitCode=" + exitCode + ", output:\n" + output);
        }

        log.info("python script success, output:\n{}", output);
        return new ProcessResult(true, output.toString());
    }
}

这个 Processor 做了什么？

TaskContext context → context.getJobParams() 拿到 Console 配的字符串（JSON）。

用 Jackson 解析成 JsonNode，取出：

modelName

modelPath

port

pythonBin

scriptPath

callbackUrl

拼成一条命令：

/opt/llm/venv/bin/python /opt/llm/scripts/start_llm.py \
  --model-name xxx --model-path xxx --port 8000 [--callback-url xxx]


用 ProcessBuilder 执行，实时把输出打到 PowerJob 在线日志里。

根据 exitCode 返回 ProcessResult 成功或失败。

2. Python 启动脚本：接收参数 → 拉起推理服务

一个对应的 start_llm.py 示例（你可以改成 vLLM / MindIE / 自己的 server）：

#!/usr/bin/env python3
import argparse
import subprocess
import time
import os
import sys

try:
    import requests
except ImportError:
    requests = None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-name", required=True)
    parser.add_argument("--model-path", required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--callback-url", default=None)

    args = parser.parse_args()

    log_dir = "/opt/llm/logs"
    os.makedirs(log_dir, exist_ok=True)
    log_file = os.path.join(
        log_dir,
        f"{args.model_name}-{time.strftime('%Y-%m-%d_%H%M%S')}.log"
    )

    # 这里写你真实的推理服务启动命令
    cmd = [
        "/opt/llm/venv/bin/python",
        "-m", "vllm.entrypoints.openai.api_server",
        "--model", args.model_path,
        "--port", str(args.port),
        "--tensor-parallel-size", "1",
    ]

    print(f"[INFO] start {args.model_name} on port {args.port}, log={log_file}")
    with open(log_file, "w") as f:
        proc = subprocess.Popen(cmd, stdout=f, stderr=f)

    # 等一会儿看进程是否正常跑着
    time.sleep(5)
    if proc.poll() is not None:
        print(f"[ERROR] service exited immediately, code={proc.returncode}, log={log_file}")
        sys.exit(1)

    print(f"[INFO] service started, pid={proc.pid}, log={log_file}")

    # 可选：回调你的测试平台
    if args.callback_url:
        if not requests:
            print("[WARN] requests not installed, skip callback")
        else:
            try:
                resp = requests.post(
                    args.callback_url,
                    json={
                        "model": args.model_name,
                        "port": args.port,
                        "pid": proc.pid,
                        "log_file": log_file
                    },
                    timeout=5,
                )
                print(f"[INFO] callback status={resp.status_code}")
            except Exception as e:
                print(f"[WARN] callback failed: {e}")

    # 这里只负责「拉起」，不阻塞等到服务退出，所以直接返回 0
    sys.exit(0)


if __name__ == "__main__":
    main()


注意：

requests 不是必须，如果你用回调就装一下。

真正的推理命令你按自己环境换掉就行。

3. Console 上怎么配置这个任务
3.1 执行配置

在 PowerJob 控制台「任务管理 → 新增任务」：

执行类型：单机（先选单机，绑定指定测试机 Worker）

处理器类型：内置 Java 处理器

处理器信息 可以用两种写法之一： 
CSDN
+1

llmPythonProcessor#pythonLauncher（用 Bean 名）

com.example.powerjob.demo.LlmPythonProcessor#pythonLauncher（用全限定类名）

3.2 任务参数（JobParams）JSON 示例

比如你要在某台测试机上拉起 qwen-7b：

{
  "modelName": "qwen-7b",
  "modelPath": "/data/models/qwen-7b",
  "port": 8000,
  "pythonBin": "/opt/llm/venv/bin/python",
  "scriptPath": "/opt/llm/scripts/start_llm.py",
  "callbackUrl": "https://your-test-platform/api/report/llm-start"
}


之后：

你只要改这里的 JSON，就可以：

换模型：改 modelName / modelPath

换端口：改 port

换环境：不同 Worker 上用相同脚本路径 / 不同 Job；或者 JSON 再加 env 字段，由 Python 自己决定。

Java 侧完全不用改代码，Processor 通吃所有配置。

4. 整体执行链再过一遍

你在 Console 点「运行一次」或按 CRON 调度任务。

PowerJob-Server 调度到目标 Worker（测试机）。

Worker 调用 LlmPythonProcessor.pythonLauncher(TaskContext)：

TaskContext.getJobParams() 拿到上面的 JSON

解析 → 拼命令行 → 调 start_llm.py

start_llm.py 按参数拉起推理服务：

杀旧进程（如果你想要的话）

起新服务 → 打日志 → （可选）回调测试平台

Python 退出码为 0 → PowerJob 认为任务成功；否则标记失败，可触发告警 / 重试。

如果你愿意再往上抽象一层，我们可以把 JobParams 里的字段再多设计一点（env / action / gpu 索引等），做成一个真正的「服务管理任务」，你只要在 Console 写 JSON，就能做 start/stop/restart + 多模型多环境调度。你要的话我可以基于这套再帮你升级一版「action=start/stop/restart」的 Processor。

我希望执行期间测试平台可以动态获取到执行日志，应该如何处理

核心思路其实就一句话：不要指望测试平台“去读文件 tail -f”，而是让 PowerJob 或脚本在执行时“顺带把日志往外推”，测试平台只用订阅/轮询即可。

我给你三个层次的方案，按“改动成本从小到大 + 实时性从一般到很好”来排。

一、总思路先定一下

一个“实时日志”链路可以抽象成三段：

日志源头：

你的自定义 Processor（Java）

子进程脚本（Shell / Python）

推理服务本身

日志分发通道：

PowerJob 自带的在线日志（OmsLogger）
CSDN
+1

你自己搭的 HTTP / WebSocket / Kafka / Redis topic

测试平台展示端：

页面上拿着 instanceId，不断来“要增量日志”，像在线版 tail -f

你现在已经有：

自定义 LlmPythonProcessor

PowerJob 在线日志（用 context.getOmsLogger()）
CSDN
+1

Shell / Python 启动脚本

现在要做的是：把日志从“仅在 PowerJob 控制台看得到” → “测试平台也能实时看到”。

二、方案一：测试平台“去拉 PowerJob 在线日志”（侵入最小）

PowerJob 自带一套“在线日志系统”，Worker 用 OmsLogger 写日志，Server 存到 DB（H2/MySQL 等），Console 实时展示。
CSDN
+1

你现在自定义 Processor 里已经在这样写：

OmsLogger omsLogger = context.getOmsLogger();
omsLogger.info(lineFromPython);


这类日志 已经在 PowerJob Server 里了。在线日志“实时”的原理，就是 Worker 不断通过接口把日志上报到 Server。
CSDN
+1

1）调用链设计

测试平台调用 PowerJob 的 OpenAPI 去触发某个任务（或在后台配好），拿到 instanceId。
Stack Overflow

测试平台的后端提供一个接口，例如：

GET /llm/logs?instanceId=xxx&fromLine=0

这个接口内部再去调用 PowerJob-Server 的“实例日志接口”：

最简单的做法：直接打开 PowerJob 控制台，F12 抓网络请求，看“在线日志”按钮点下去的 URL & 参数，把这一套 HTTP 调用复用到你的后台。

后端只拿“新增的部分日志”（fromLine 之类参数），返回给前端。

前端每 1～2 秒轮询一次，就能看到“执行中的实时日志”。

优点：

不用改 Worker / 脚本逻辑；

日志只上报 PowerJob 一份，测试平台只是读出来；

运维简单。

缺点：

实时性是“秒级轮询”，不是真正的流式；

强绑定 PowerJob 的内部接口（不过你本来就强耦合了问题也不大）；

抓接口比较“野路子”，但内部系统完全可行。

这个方案胜在快糙猛，适合先跑起来看效果。

三、方案二：Processor 里“双写日志 + 推给测试平台”（推荐）

如果你接受在 Worker 里多写一点代码，我更推荐 在 Java Processor 里一边用 OmsLogger 写在线日志，一边把日志 batch 推给测试平台。

1）改造 LlmPythonProcessor：读子进程输出时同步推日志

伪代码（核心思想）：

@Component("llmPythonProcessor")
public class LlmPythonProcessor {

    @PowerJobHandler(name = "pythonLauncher")
    public ProcessResult pythonLauncher(TaskContext context) throws Exception {
        OmsLogger omsLogger = context.getOmsLogger();
        Long instanceId = context.getInstanceId(); // 关键：给测试平台标记这次执行

        // ... 解析 jobParams，拼 python 命令 cmd 省略

        Process process = new ProcessBuilder(cmd).start();

        List<String> buffer = new ArrayList<>();
        long lastFlushTime = System.currentTimeMillis();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                // 1. 写到 PowerJob 在线日志
                omsLogger.info(line);

                // 2. 写到你自己的缓冲区
                buffer.add(line);

                long now = System.currentTimeMillis();
                // 满足「行数达到 N」或「时间超过 T」就 flush 一次到测试平台
                if (buffer.size() >= 20 || now - lastFlushTime > 2000) {
                    flushToTestPlatform(instanceId, buffer);
                    buffer.clear();
                    lastFlushTime = now;
                }
            }
        }

        // 把尾巴也发出去
        if (!buffer.isEmpty()) {
            flushToTestPlatform(instanceId, buffer);
        }

        int exitCode = process.waitFor();
        boolean success = (exitCode == 0);
        return new ProcessResult(success, "exitCode=" + exitCode);
    }

    private void flushToTestPlatform(Long instanceId, List<String> lines) {
        try {
            // 这里随便用 HttpClient / RestTemplate / OkHttp 都可以
            // 伪代码：
            /*
            POST https://your-test-platform/api/log/append
            {
              "instanceId": 123,
              "timestamp": 1731355200000,
              "lines": ["...","..."]
            }
            */
        } catch (Exception e) {
            // 不要影响任务本身执行，只打个 warn
        }
    }
}

2）测试平台这边做什么

提供一个日志追加接口，例如：

POST /api/log/append
Content-Type: application/json

{
  "instanceId": 1008611,
  "timestamp": 1731355200000,
  "lines": [
    "[2025-11-12 01:00:00] start xxx",
    "[2025-11-12 01:00:02] load model done"
  ]
}


后端把日志按 instanceId 存到：

内存缓存（比如 Map<instanceId, List<LogLine>>，适合短日志 + 小并发）

或持久化存 DB / Elasticsearch / ClickHouse / Loki

前端页面拿着 instanceId 建一个 WebSocket / SSE 连接，后端每次收到追加就推到对应连接上。
这样前端就是“准实时”看到日志滚动。

优点：

实时性比轮询好很多；

日志可以完全按照你测试平台的格式 / 结构组织；

同时还能继续用 PowerJob 自带在线日志（双写）。

缺点：

Worker 侧要引入 HTTP 客户端，并做好失败兜底（不能因为日志推送失败把任务搞挂了）；

测试平台要做一点“日志存储 + 推送”的开发。

结合你的常用技术栈（Spring Boot + Kafka + Redis），你也可以把 flushToTestPlatform 做成：

往 Kafka 打一条消息：topic=llm-task-log，value 里带 instanceId 和日志行；

测试平台消费这个 topic，把日志推到前端。

四、方案三：把启动脚本 / 推理服务接入统一日志中心

再往上就是“企业级玩法”：

Shell / Python / 推理服务统统写到标准输出或某个文件；

在测试机上部署一个日志 Agent：Filebeat / Vector / FluentBit；

把对应目录收集到统一日志中心（ELK / Loki / ClickHouse + Grafana）；

测试平台只是查“某个 instanceId 对应的 traceId / tag”的日志。

PowerJob 这边只需要：

在 JobParams 里带上 traceId/instanceId；

启动推理服务时把这个 ID 传进去，推理服务日志里按统一格式打出来；

日志中心里按 traceId 查询，即可定位。

优点：

日志统一治理，适合你后面要搞大模型测试平台的全链路；

和 PowerJob 解耦，哪怕以后换调度框架也能复用。

缺点：

一整套日志平台搭建成本比较高；

对“我就想先把这一个任务跑起来”来说有点重。

五、实际建议：你现在可以这样落地

结合你目前状态，我建议的落地顺序：

立刻可做：方案二（Processor 双写 + HTTP/Kafka）

改造你现在的 LlmPythonProcessor，给我上面的 flushToTestPlatform 那一段；

测试平台先简单用内存 Map 存日志，前端用轮询也行，先验证“执行期间能看到日志”。

等整条链跑顺了：

再考虑把日志落到统一 store（ES / ClickHouse）；

再考虑是否接入 Filebeat 等，做成完整日志平台。

方案一（直接读 PowerJob 在线日志）可以作为兜底：

后端有个“调试模式”接口：如果你的日志推送链路有问题，还可以从 PowerJob 在线日志补一部分信息。
CSDN
+1

如果你愿意，我可以帮你再往前推一步：

直接给出一个 完整的 Spring Boot Controller + WebSocket 示例，演示“测试平台页面打开后，连接后端→后端订阅 instanceId → Processor 一边执行一边推日志，前端实时滚动展示”的代码骨架。



