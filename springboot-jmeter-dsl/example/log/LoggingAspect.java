package com.example.shop.support.aspect;

import com.example.shop.common.util.JsonUtil;
import com.example.shop.common.util.MaskUtil;
import com.example.shop.common.util.TraceUtil;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger; 
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Parameter;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 请求 / 响应 / 耗时 日志切面
 * - 仅作用于 @RestController 标注的类
 * - 需要配合 TraceFilter/TraceUtil，确保 MDC 中有 requestId
 */
@Aspect
@Component
public class LoggingAspect {

    private static final Logger log = LoggerFactory.getLogger("HTTP_LOG");

    /** 最大打印长度（避免日志爆量） */
    private static final int MAX_PRINT = 2000;

    /** 允许打印的 Header 白名单（可按需扩展） */
    private static final Set<String> HEADER_WHITELIST = Set.of(
            "x-request-id", "user-agent", "content-type", "accept", "x-forwarded-for", "authorization"
    );

    private static final DefaultParameterNameDiscoverer NAME_DISCOVERER = new DefaultParameterNameDiscoverer();

    @Pointcut("@within(org.springframework.web.bind.annotation.RestController)")
    public void anyRestController() {}

    @Around("anyRestController()")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        long startNs = System.nanoTime();
        HttpServletRequest req = currentRequest();
        HttpServletResponse resp = currentResponse();

        // 确保 requestId 存在
        String reqId = TraceUtil.ensure();

        MethodSignature ms = (MethodSignature) pjp.getSignature();
        String classMethod = ms.getDeclaringType().getSimpleName() + "#" + ms.getName();

        // 请求上下文
        String method = req != null ? req.getMethod() : "N/A";
        String uri = req != null ? req.getRequestURI() : classMethod;
        String query = req != null ? emptyIfNull(req.getQueryString()) : "";
        Map<String, String> headers = req != null ? extractHeaders(req) : Map.of();

        // 入参快照（带脱敏/忽略）
        Object argsView = buildArgsView(pjp);

        // 打印请求日志
        if (log.isInfoEnabled()) {
            log.info("REQ | id={} | {} {}{} | headers={} | args={}",
                    reqId, method, uri, formatQuery(query), truncate(JsonUtil.toJson(headers)),
                    truncate(JsonUtil.toJson(argsView)));
        }

        Object result = null;
        Throwable ex = null;
        try {
            result = pjp.proceed();
            return result;
        } catch (Throwable t) {
            ex = t;
            throw t;
        } finally {
            long costMs = (System.nanoTime() - startNs) / 1_000_000;
            int status = resp != null ? resp.getStatus() : (ex == null ? 200 : 500);

            // 响应体（尽力取，避免超大/循环引用）
            String resultStr;
            try {
                resultStr = truncate(JsonUtil.toJson(result));
            } catch (Exception e) {
                resultStr = "<unserializable>";
            }

            if (ex == null) {
                log.info("RESP | id={} | {} {} | status={} | costMs={} | body={}",
                        reqId, method, uri, status, costMs, resultStr);
            } else {
                log.warn("RESP | id={} | {} {} | status={} | costMs={} | ex={} | body={}",
                        reqId, method, uri, status, costMs, ex.toString(), resultStr);
            }
        }
    }

    // ----------------- helpers -----------------

    private HttpServletRequest currentRequest() {
        RequestAttributes ra = RequestContextHolder.getRequestAttributes();
        if (ra instanceof ServletRequestAttributes sra) return sra.getRequest();
        return null;
    }

    private HttpServletResponse currentResponse() {
        RequestAttributes ra = RequestContextHolder.getRequestAttributes();
        if (ra instanceof ServletRequestAttributes sra) return sra.getResponse();
        return null;
    }

    private String formatQuery(String q) {
        if (q == null || q.isEmpty()) return "";
        try {
            String decoded = URLDecoder.decode(q, StandardCharsets.UTF_8);
            return "?" + decoded;
        } catch (Exception e) {
            return "?" + q;
        }
    }

    private Map<String, String> extractHeaders(HttpServletRequest req) {
        Map<String, String> m = new LinkedHashMap<>();
        Enumeration<String> names = req.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            if (HEADER_WHITELIST.contains(name.toLowerCase())) {
                String v = req.getHeader(name);
                // Authorization 仅打印前后缀
                if ("authorization".equalsIgnoreCase(name) && v != null) {
                    v = maskAuth(v);
                }
                m.put(name, v);
            }
        }
        return m;
    }

    private String maskAuth(String v) {
        if (v == null) return null;
        String s = v.trim();
        if (s.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = s.substring(7);
            if (token.length() <= 12) return "Bearer ****";
            return "Bearer " + token.substring(0, 6) + "****" + token.substring(token.length() - 4);
        }
        return "***";
    }

    /** 构造入参视图：支持 @LogIgnore 忽略与常见字段脱敏 */
    private Object buildArgsView(ProceedingJoinPoint pjp) {
        Object[] args = pjp.getArgs();
        if (args == null || args.length == 0) return List.of();

        MethodSignature ms = (MethodSignature) pjp.getSignature();
        Parameter[] params = ms.getMethod().getParameters();
        String[] names = NAME_DISCOVERER.getParameterNames(ms.getMethod());

        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String name = names != null && i < names.length ? names[i] : ("arg" + i);
            Object val = args[i];
            if (skipSpringInfra(val)) continue; // 跳过 request/response 之类

            // 如果参数或其类型上标注了 @LogIgnore，则忽略
            if (hasLogIgnore(params[i])) {
                list.add(Map.of(name, "<ignored>"));
            } else {
                list.add(Map.of(name, safeMask(val)));
            }
        }
        return list;
    }

    private boolean hasLogIgnore(Parameter p) {
        if (AnnotatedElementUtils.hasAnnotation(p, LogIgnore.class)) return true;
        // 如果是简单类型/集合就不再深入判断字段注解
        return false;
    }

    private boolean skipSpringInfra(Object v) {
        if (v == null) return false;
        return v instanceof HttpServletRequest
                || v instanceof HttpServletResponse
                || v instanceof org.springframework.web.multipart.MultipartFile
                || v instanceof org.springframework.web.multipart.MultipartFile[];
    }

    /** 对常见敏感字段做脱敏（name/phone/email/idCard 等），并尽量避免递归过深 */
    private Object safeMask(Object obj) {
        if (obj == null) return null;
        if (obj instanceof CharSequence s) {
            return truncate(s.toString());
        }
        if (obj instanceof Number || obj instanceof Boolean) return obj;
        if (obj instanceof Collection<?> col) {
            return col.stream().map(this::safeMask).limit(50).collect(Collectors.toList());
        }
        if (obj instanceof Map<?,?> m) {
            Map<String,Object> r = new LinkedHashMap<>();
            int i = 0;
            for (var e : m.entrySet()) {
                if (i++ > 50) break;
                r.put(String.valueOf(e.getKey()), safeMaskField(String.valueOf(e.getKey()), e.getValue()));
            }
            return r;
        }
        // POJO：反射读取字段（最多 50 个字段，避免深递归）
        Map<String,Object> r = new LinkedHashMap<>();
        Field[] fs = obj.getClass().getDeclaredFields();
        int cnt = 0;
        for (Field f : fs) {
            if (cnt++ > 50) break;
            if (AnnotatedElementUtils.hasAnnotation(f, LogIgnore.class)) {
                r.put(f.getName(), "<ignored>");
                continue;
            }
            ReflectionUtils.makeAccessible(f);
            Object v = ReflectionUtils.getField(f, obj);
            r.put(f.getName(), safeMaskField(f.getName(), v));
        }
        return r;
    }

    private Object safeMaskField(String name, Object v) {
        if (v == null) return null;
        String lower = name.toLowerCase();
        if (lower.contains("phone") || lower.contains("mobile")) {
            return MaskUtil.phone(String.valueOf(v));
        } else if (lower.contains("email")) {
            return MaskUtil.email(String.valueOf(v));
        } else if (lower.contains("name") || lower.contains("realname")) {
            return MaskUtil.name(String.valueOf(v));
        } else if (lower.contains("idcard") || lower.contains("id_no") || lower.contains("cardno")) {
            return MaskUtil.idGeneric(String.valueOf(v));
        } else if (lower.contains("password") || lower.contains("pwd") || lower.contains("token")) {
            return "<hidden>";
        }
        // 默认处理为字符串截断，防止超大对象
        if (v instanceof CharSequence s) return truncate(s.toString());
        return v;
    }

    private String truncate(String s) {
        if (s == null) return null;
        if (s.length() <= MAX_PRINT) return s;
        return s.substring(0, MAX_PRINT) + "...(" + (s.length() - MAX_PRINT) + " more chars)";
    }

    private String emptyIfNull(String s) { return s == null ? "" : s; }
}
