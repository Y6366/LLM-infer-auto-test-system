package com.example.shop.common.error;

import com.example.shop.common.response.ApiResponse;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 业务异常（面向可预期的业务错误场景）
 * - 与 ErrorCode 配合使用，便于统一到 ApiResponse 的错误格式
 * - 非预期异常（NPE/DB宕机等）建议让 GlobalExceptionHandler 兜底为 500
 */
public class BizException extends RuntimeException {

    /** 机器可读错误码：如 ORD_NOT_FOUND、AUTH_UNAUTHORIZED */
    private final String code;

    /** 建议回传的 HTTP 状态码：如 400/401/403/404/409/422/429/500… */
    private final int http;

    /** 人类可读消息（可覆盖 ErrorCode 的 defaultMessage） */
    private final String messageOverride;

    /** 诊断/上下文信息：如 traceId、hint、service、额外上下文 */
    private final Map<String, Object> details;

    /** 字段级错误（与 ApiResponse.ErrorDetail 对齐） */
    private final List<ApiResponse.ErrorDetail> errors;

    // -------------------- 构造器 --------------------

    /**
     * 用 ErrorCode 构造（无 details/errors）
     */
    public BizException(ErrorCode ec) {
        this(ec, null, null, null, null);
    }

    /**
     * 用 ErrorCode + 自定义消息构造
     */
    public BizException(ErrorCode ec, String messageOverride) {
        this(ec, messageOverride, null, null, null);
    }

    /**
     * 用 ErrorCode + 详情/字段错误 构造
     */
    public BizException(ErrorCode ec,
                        String messageOverride,
                        Map<String, Object> details,
                        List<ApiResponse.ErrorDetail> errors) {
        this(ec, messageOverride, details, errors, null);
    }

    /**
     * 完整构造（包含 cause）
     */
    public BizException(ErrorCode ec,
                        String messageOverride,
                        Map<String, Object> details,
                        List<ApiResponse.ErrorDetail> errors,
                        Throwable cause) {
        super(messageOverride != null ? messageOverride : (ec != null ? ec.defaultMessage() : null), cause);
        if (ec == null) {
            // 兜底：没有 ErrorCode 时，使用通用内部错误
            this.code = "COMMON_INTERNAL_ERROR";
            this.http = 500;
            this.messageOverride = messageOverride != null ? messageOverride : "Internal server error";
        } else {
            this.code = ec.code();
            this.http = ec.http();
            this.messageOverride = messageOverride != null ? messageOverride : ec.defaultMessage();
        }
        this.details = details == null ? Collections.emptyMap() : Collections.unmodifiableMap(details);
        this.errors = errors == null ? Collections.emptyList() : List.copyOf(errors);
    }

    /**
     * 直接用 code/http/message 构造（不依赖 ErrorCode 枚举）
     */
    public BizException(String code, int http, String messageOverride) {
        super(messageOverride);
        this.code = code;
        this.http = http;
        this.messageOverride = messageOverride;
        this.details = Collections.emptyMap();
        this.errors = Collections.emptyList();
    }

    // -------------------- 静态工厂 --------------------

    public static BizException of(ErrorCode ec) {
        return new BizException(ec);
    }

    public static BizException of(ErrorCode ec, String messageOverride) {
        return new BizException(ec, messageOverride);
    }

    public static BizException of(ErrorCode ec,
                                  String messageOverride,
                                  Map<String, Object> details,
                                  List<ApiResponse.ErrorDetail> errors) {
        return new BizException(ec, messageOverride, details, errors);
    }

    public static BizException of(String code, int http, String message) {
        return new BizException(code, http, message);
    }

    // -------------------- 与统一响应对接 --------------------

    /**
     * 将本异常转换为统一响应 ApiResponse（错误分支）。
     * 注意：是否使用 http 作为 HTTP 返回码由 Controller/Advice 决定。
     */
    public ApiResponse<Void> toApiResponse() {
        ApiResponse<Void> resp = ApiResponse.error(this.code, this.http, this.messageOverride);
        if (!details.isEmpty()) {
            resp.setDetails(details);
        }
        if (!errors.isEmpty()) {
            resp.setErrors(errors);
        }
        return resp;
    }

    // -------------------- Getter --------------------

    public String getCode() {
        return code;
    }

    public int getHttp() {
        return http;
    }

    @Override
    public String getMessage() {
        return messageOverride;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    public List<ApiResponse.ErrorDetail> getErrors() {
        return errors;
    }

    // -------------------- 语法糖 --------------------

    /** 追加诊断信息返回新异常（不改变当前实例） */
    public BizException withDetails(Map<String, Object> extra) {
        return new BizException(
                ErrorCode.fromCode(this.code) != null ? ErrorCode.fromCode(this.code) : null,
                this.messageOverride,
                extra,
                this.errors,
                this.getCause()
        );
    }

    /** 追加字段错误返回新异常（不改变当前实例） */
    public BizException withErrors(List<ApiResponse.ErrorDetail> newErrors) {
        return new BizException(
                ErrorCode.fromCode(this.code) != null ? ErrorCode.fromCode(this.code) : null,
                this.messageOverride,
                this.details,
                newErrors,
                this.getCause()
        );
    }
}
