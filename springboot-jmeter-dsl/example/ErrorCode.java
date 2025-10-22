package com.example.shop.common.error;

import java.text.MessageFormat;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

// 如项目已集成统一响应，可解开以下 import，并保留 toResponse(...) 方法
import com.example.shop.common.response.ApiResponse;

/**
 * 错误码规范：
 * - 机械可读 code：模块前缀 + 下划线，例如：ORD_NOT_FOUND、AUTH_UNAUTHORIZED
 * - http：与 HTTP 状态码语义一致（400/401/403/404/409/422/429/500/502/504 等）
 * - defaultMessage：默认人类可读消息（可被 i18n 覆盖）
 *
 * 约定：
 * - 成功不使用 ErrorCode；仅错误/异常使用本枚举。
 * - 前端/客户端以 status.code 对应本枚举值，进行分支处理与文案映射。
 */
public enum ErrorCode {

    // ---------- 通用 ----------
    COMMON_BAD_REQUEST("COMMON_BAD_REQUEST", 400, "Bad request"),
    COMMON_NOT_FOUND("COMMON_NOT_FOUND", 404, "Resource not found"),
    COMMON_METHOD_NOT_ALLOWED("COMMON_METHOD_NOT_ALLOWED", 405, "Method not allowed"),
    COMMON_UNSUPPORTED_MEDIA_TYPE("COMMON_UNSUPPORTED_MEDIA_TYPE", 415, "Unsupported media type"),
    COMMON_CONFLICT("COMMON_CONFLICT", 409, "Conflict"),
    COMMON_VALIDATION_FAILED("COMMON_VALIDATION_FAILED", 422, "Invalid request parameters"),
    COMMON_TOO_MANY_REQUESTS("COMMON_TOO_MANY_REQUESTS", 429, "Too many requests"),
    COMMON_INTERNAL_ERROR("COMMON_INTERNAL_ERROR", 500, "Internal server error"),

    // ---------- 鉴权/权限 ----------
    AUTH_UNAUTHORIZED("AUTH_UNAUTHORIZED", 401, "Unauthorized"),
    AUTH_FORBIDDEN("AUTH_FORBIDDEN", 403, "Forbidden"),
    AUTH_TOKEN_EXPIRED("AUTH_TOKEN_EXPIRED", 401, "Token expired"),
    AUTH_TOKEN_INVALID("AUTH_TOKEN_INVALID", 401, "Invalid token"),

    // ---------- 幂等等/重放 ----------
    COMMON_IDEMPOTENT_REPLAY("COMMON_IDEMPOTENT_REPLAY", 409, "Idempotency key has been used"),

    // ---------- 订单（Order） ----------
    ORD_NOT_FOUND("ORD_NOT_FOUND", 404, "Order not found"),
    ORD_STATE_ILLEGAL("ORD_STATE_ILLEGAL", 409, "Illegal order state"),
    ORD_DUPLICATE("ORD_DUPLICATE", 409, "Duplicate order"),
    ORD_INVALID_PARAM("ORD_INVALID_PARAM", 422, "Invalid order parameter"),

    // ---------- 库存（Inventory） ----------
    INV_OUT_OF_STOCK("INV_OUT_OF_STOCK", 422, "Out of stock"),
    INV_RESERVE_FAILED("INV_RESERVE_FAILED", 503, "Inventory reservation failed"),
    INV_RELEASE_FAILED("INV_RELEASE_FAILED", 503, "Inventory release failed"),

    // ---------- 支付（Payment） ----------
    PAY_FAILED("PAY_FAILED", 400, "Payment failed"),
    PAY_TIMEOUT("PAY_TIMEOUT", 504, "Payment timeout"),
    PAY_NOTIFY_INVALID_SIGNATURE("PAY_NOTIFY_INVALID_SIGNATURE", 400, "Invalid payment notification signature"),

    // ---------- 网关/外部依赖 ----------
    GATEWAY_DEPENDENCY_ERROR("GATEWAY_DEPENDENCY_ERROR", 502, "Upstream dependency error"),
    GATEWAY_DEPENDENCY_TIMEOUT("GATEWAY_DEPENDENCY_TIMEOUT", 504, "Upstream dependency timeout");

    private final String code;
    private final int http;
    private final String defaultMessage;

    ErrorCode(String code, int http, String defaultMessage) {
        this.code = code;
        this.http = http;
        this.defaultMessage = defaultMessage;
    }

    /** 机器可读错误码（如：ORD_NOT_FOUND） */
    public String code() {
        return code;
    }

    /** 建议使用的 HTTP 状态码 */
    public int http() {
        return http;
    }

    /** 默认的人类可读说明（可被上层 i18n 或自定义 message 覆盖） */
    public String defaultMessage() {
        return defaultMessage;
    }

    // ---------- 静态查找 ----------
    private static final Map<String, ErrorCode> INDEX;
    static {
        Map<String, ErrorCode> m = new HashMap<>();
        for (ErrorCode ec : values()) {
            m.put(ec.code, ec);
        }
        INDEX = Collections.unmodifiableMap(m);
    }

    /** 通过 code 查找枚举；未匹配返回 null */
    public static ErrorCode fromCode(String code) {
        if (code == null) return null;
        return INDEX.get(code);
    }

    /**
     * 使用 {@link MessageFormat} 对默认消息进行占位符替换。
     * 例：defaultMessage = "Order {0} not found", formatMessage(orderNo)
     */
    public String formatMessage(Object... args) {
        if (args == null || args.length == 0) return defaultMessage;
        return MessageFormat.format(defaultMessage, args);
    }

    // ---------- 与统一响应对接（可选辅助） ----------
    /**
     * 将错误码快速转为统一响应 ApiResponse。
     * @param messageOverride 可为 null：使用 defaultMessage
     * @param details 诊断信息（traceId、hint 等），可为 null
     * @return ApiResponse<Void>
     */
    public ApiResponse<Void> toResponse(String messageOverride, Map<String, Object> details) {
        String msg = (messageOverride == null || messageOverride.isBlank()) ? defaultMessage : messageOverride;
        ApiResponse<Void> resp = ApiResponse.error(this.code, this.http, msg);
        if (details != null && !details.isEmpty()) {
            resp.setDetails(new HashMap<>(details));
        }
        return resp;
    }

    public ApiResponse<Void> toResponse() {
        return toResponse(null, null);
    }

    @Override
    public String toString() {
        return code + "(" + http + "): " + defaultMessage;
    }

    // ---------- 语法糖 ----------
    public boolean isClientError() { return http >= 400 && http < 500; }
    public boolean isServerError() { return http >= 500; }
    public boolean isUnauthorized() { return this == AUTH_UNAUTHORIZED || this == AUTH_TOKEN_EXPIRED || this == AUTH_TOKEN_INVALID; }
    public boolean isConflict() { return http == 409; }
}
