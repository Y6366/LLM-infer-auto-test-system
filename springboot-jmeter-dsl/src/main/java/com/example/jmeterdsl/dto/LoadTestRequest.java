package com.example.jmeterdsl.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.Collections;
import java.util.Map;

public class LoadTestRequest {

    @Min(value = 1, message = "线程数必须大于等于1")
    private int threads = 1;

    @Min(value = 1, message = "循环次数必须大于等于1")
    private int loopCount = 1;

    @NotBlank(message = "IP 地址不能为空")
    private String ip;

    @Min(value = 1, message = "端口必须大于0")
    private int port;

    private String protocol = "http";

    @NotBlank(message = "HTTP 方法不能为空")
    private String method;

    @NotBlank(message = "接口路径不能为空")
    private String path;

    private String contentType = "application/json";

    private Map<String, String> headers;

    private String body;

    public int getThreads() {
        return threads;
    }

    public void setThreads(int threads) {
        this.threads = threads;
    }

    public int getLoopCount() {
        return loopCount;
    }

    public void setLoopCount(int loopCount) {
        this.loopCount = loopCount;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        if (protocol != null && !protocol.isBlank()) {
            this.protocol = protocol;
        }
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        if (contentType != null && !contentType.isBlank()) {
            this.contentType = contentType;
        }
    }

    public Map<String, String> getHeaders() {
        return headers == null ? Collections.emptyMap() : headers;
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers = headers;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }
}
