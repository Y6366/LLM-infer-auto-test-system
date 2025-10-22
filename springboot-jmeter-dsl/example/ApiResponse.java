package com.example.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import java.time.Instant;
import java.util.*;

/**
 * 统一响应封装：与前后端固定消息体契合
 * - 成功：status.code="OK"，返回 data/meta/links
 * - 失败：status.code=业务码，返回 errors/details
 * 约定：成功不返回 errors；失败不返回 data（除非你定义 PARTIAL_OK 等语义）
 */
@JsonInclude(Include.NON_NULL)
public class ApiResponse<T> {

    /** 消息体版本，便于演进 */
    private String version = "1.0";
    /** UTC 时间戳 */
    private Instant timestamp = Instant.now();
    /** 请求链路ID（可用拦截器从MDC/Trace注入） */
    private String requestId;
    /** 状态区：机器码/HTTP/人类可读消息 */
    private Status status;
    /** 业务有效载荷（成功时） */
    private T data;
    /** 错误明细（失败时） */
    private List<ErrorDetail> errors;
    /** 诊断上下文（traceId、service、hint…） */
    private Map<String, Object> details;
    /** 元信息（分页、统计口径等） */
    private Meta meta;
    /** HATEOAS 风格链接 */
    private Links links;

    // ---------- 静态工厂（Success） ----------
    public static <T> ApiResponse<T> ok(T data) {
        ApiResponse<T> r = baseOk();
        r.setData(data);
        return r;
    }

    public static <T> ApiResponse<T> ok(T data, Meta meta) {
        ApiResponse<T> r = baseOk();
        r.setData(data);
        r.setMeta(meta);
        return r;
    }

    public static <T> ApiResponse<T> ok(T data, Meta meta, Links links) {
        ApiResponse<T> r = baseOk();
        r.setData(data);
        r.setMeta(meta);
        r.setLinks(links);
        return r;
    }

    private static <T> ApiResponse<T> baseOk() {
        ApiResponse<T> r = new ApiResponse<>();
        r.setStatus(new Status("OK", 200, "Success"));
        return r;
    }

    // ---------- 静态工厂（Error） ----------
    public static <T> ApiResponse<T> error(String code, int http, String message) {
        ApiResponse<T> r = new ApiResponse<>();
        r.setStatus(new Status(code, http, message));
        return r;
    }

    public static <T> ApiResponse<T> error(String code, int http, String message,
                                           List<ErrorDetail> errors) {
        ApiResponse<T> r = new ApiResponse<>();
        r.setStatus(new Status(code, http, message));
        r.setErrors(errors);
        return r;
    }

    public static <T> ApiResponse<T> error(String code, int http, String message,
                                           List<ErrorDetail> errors,
                                           Map<String, Object> details) {
        ApiResponse<T> r = new ApiResponse<>();
        r.setStatus(new Status(code, http, message));
        r.setErrors(errors);
        r.setDetails(details);
        return r;
    }

    // ---------- 链式辅助（可选） ----------
    public ApiResponse<T> withRequestId(String requestId) {
        this.requestId = requestId;
        return this;
    }

    public ApiResponse<T> withDetailsEntry(String key, Object value) {
        if (this.details == null) this.details = new LinkedHashMap<>();
        this.details.put(key, value);
        return this;
    }

    // ---------- 内部类型 ----------
    @JsonInclude(Include.NON_NULL)
    public static class Status {
        private String code;     // "OK" 或业务错误码（如 ORD_INVALID_PARAM）
        private Integer http;    // HTTP 状态码镜像
        private String message;  // 人类可读消息（可配合 i18n key 使用）

        public Status() {}
        public Status(String code, Integer http, String message) {
            this.code = code; this.http = http; this.message = message;
        }

        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public Integer getHttp() { return http; }
        public void setHttp(Integer http) { this.http = http; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }

    @JsonInclude(Include.NON_NULL)
    public static class ErrorDetail {
        private String code;      // 如 VALIDATION_FAILED / SKU_NOT_FOUND
        private String field;     // 绑定字段路径：items[0].quantity
        private String message;   // 错误描述
        private String expected;  // 期望值/约束描述
        private String actual;    // 实际值（注意脱敏）

        public ErrorDetail() {}
        public ErrorDetail(String code, String field, String message) {
            this.code = code; this.field = field; this.message = message;
        }

        // 全参方便快速创建
        public ErrorDetail(String code, String field, String message, String expected, String actual) {
            this.code = code; this.field = field; this.message = message;
            this.expected = expected; this.actual = actual;
        }

        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getField() { return field; }
        public void setField(String field) { this.field = field; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public String getExpected() { return expected; }
        public void setExpected(String expected) { this.expected = expected; }
        public String getActual() { return actual; }
        public void setActual(String actual) { this.actual = actual; }
    }

    @JsonInclude(Include.NON_NULL)
    public static class Meta {
        private Pagination pagination;
        private Map<String, Object> extra;

        public Meta() {}
        public Meta(Pagination pagination) { this.pagination = pagination; }

        public Pagination getPagination() { return pagination; }
        public void setPagination(Pagination pagination) { this.pagination = pagination; }
        public Map<String, Object> getExtra() { return extra; }
        public void setExtra(Map<String, Object> extra) { this.extra = extra; }
    }

    @JsonInclude(Include.NON_NULL)
    public static class Pagination {
        private Integer page;      // 1 基
        private Integer size;      // 每页大小
        private Long total;        // 总条数
        private Boolean hasNext;   // 是否有下一页
        private String cursor;     // 可选：游标分页

        public Pagination() {}
        public Pagination(Integer page, Integer size, Long total, Boolean hasNext) {
            this.page = page; this.size = size; this.total = total; this.hasNext = hasNext;
        }

        public Integer getPage() { return page; }
        public void setPage(Integer page) { this.page = page; }
        public Integer getSize() { return size; }
        public void setSize(Integer size) { this.size = size; }
        public Long getTotal() { return total; }
        public void setTotal(Long total) { this.total = total; }
        public Boolean getHasNext() { return hasNext; }
        public void setHasNext(Boolean hasNext) { this.hasNext = hasNext; }
        public String getCursor() { return cursor; }
        public void setCursor(String cursor) { this.cursor = cursor; }
    }

    @JsonInclude(Include.NON_NULL)
    public static class Links {
        private String self;
        private String next;
        private String prev;
        private Map<String, String> related;

        public Links() {}

        public static Links ofSelf(String self) {
            Links l = new Links(); l.setSelf(self); return l;
        }

        public String getSelf() { return self; }
        public void setSelf(String self) { this.self = self; }
        public String getNext() { return next; }
        public void setNext(String next) { this.next = next; }
        public String getPrev() { return prev; }
        public void setPrev(String prev) { this.prev = prev; }
        public Map<String, String> getRelated() { return related; }
        public void setRelated(Map<String, String> related) { this.related = related; }
    }

    // ---------- Getter / Setter ----------
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public T getData() { return data; }
    public void setData(T data) { this.data = data; }
    public List<ErrorDetail> getErrors() { return errors; }
    public void setErrors(List<ErrorDetail> errors) { this.errors = errors; }
    public Map<String, Object> getDetails() { return details; }
    public void setDetails(Map<String, Object> details) { this.details = details; }
    public Meta getMeta() { return meta; }
    public void setMeta(Meta meta) { this.meta = meta; }
    public Links getLinks() { return links; }
    public void setLinks(Links links) { this.links = links; }

    // ---------- 便捷创建器 ----------
    public static Meta pageMeta(int page, int size, long total, boolean hasNext) {
        return new Meta(new Pagination(page, size, total, hasNext));
    }

    public static <T> ApiResponse<T> badRequest(String message, List<ErrorDetail> errors) {
        return error("BAD_REQUEST", 400, message, errors);
    }

    public static <T> ApiResponse<T> unauthorized(String message) {
        return error("UNAUTHORIZED", 401, message);
    }

    public static <T> ApiResponse<T> forbidden(String message) {
        return error("FORBIDDEN", 403, message);
    }

    public static <T> ApiResponse<T> notFound(String message) {
        return error("NOT_FOUND", 404, message);
    }

    public static <T> ApiResponse<T> conflict(String message) {
        return error("CONFLICT", 409, message);
    }

    public static <T> ApiResponse<T> validationFailed(List<ErrorDetail> errors) {
        return error("VALIDATION_FAILED", 422, "Invalid request parameters", errors);
    }

    public static <T> ApiResponse<T> tooManyRequests(String message) {
        return error("TOO_MANY_REQUESTS", 429, message);
    }

    public static <T> ApiResponse<T> serverError(String message) {
        return error("INTERNAL_ERROR", 500, message);
    }
}
