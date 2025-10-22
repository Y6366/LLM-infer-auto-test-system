// 请求链路ID与 MDC 透传
package com.example.shop.common.util;

import org.slf4j.MDC;

import java.util.Optional;

/**
 * Trace 工具：统一获取/设置 requestId（与过滤器/拦截器配合）
 * - put/remove：手动操作（如任务线程池中透传）
 * - current(): 获取当前线程 MDC 的 requestId
 * - ensure(): 若无则生成一个并放入 MDC
 */
public final class TraceUtil {
    private TraceUtil() {}
    public static final String REQUEST_ID = "requestId";

    public static void put(String requestId) {
        if (requestId != null && !requestId.isBlank()) {
            MDC.put(REQUEST_ID, requestId);
        }
    }

    public static void remove() {
        MDC.remove(REQUEST_ID);
    }

    public static Optional<String> current() {
        return Optional.ofNullable(MDC.get(REQUEST_ID));
    }

    /** 若没有，则生成一个（短UUID），并放入 MDC，返回最终 requestId */
    public static String ensure() {
        String rid = MDC.get(REQUEST_ID);
        if (rid == null || rid.isBlank()) {
            rid = IdUtil.shortUuid();
            MDC.put(REQUEST_ID, rid);
        }
        return rid;
    }
}
