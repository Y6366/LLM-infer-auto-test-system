# Spring Boot JMeter Java DSL 压测服务

该模块提供一个基于 Spring Boot + JMeter Java DSL 的压测用例模板。通过 REST 接口即可动态指定压测参数，例如线程数、循环次数、目标服务地址与端口、HTTP 方法、请求路径以及请求体等，并立即执行压测。

## 快速开始

### 1. 构建与运行

```bash
mvn -f springboot-jmeter-dsl/pom.xml spring-boot:run
```

### 2. 调用压测接口

向 `http://localhost:8080/load-test/run` 发送 POST 请求，并携带如下 JSON 结构：

```json
{
  "threads": 10,
  "loopCount": 5,
  "ip": "127.0.0.1",
  "port": 8081,
  "protocol": "http",
  "method": "POST",
  "path": "/api/test",
  "contentType": "application/json",
  "headers": {
    "X-Custom-Header": "value"
  },
  "body": "{\"message\":\"hello\"}"
}
```

接口将返回 JMeter 执行后的关键指标，例如请求总数、错误数、平均响应时间、95 分位响应时间以及吞吐量。

### 3. 自定义扩展

- 可以在 `LoadTestRequest` 中继续扩展更多的压测入参（如思考时间、断言等）。
- `LoadTestService` 使用 JMeter Java DSL 构建测试计划，如需添加断言或监听器，可在 `testPlan` 中继续组合。

