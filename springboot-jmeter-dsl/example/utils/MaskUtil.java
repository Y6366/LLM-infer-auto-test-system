// 常见字段脱敏：手机/邮箱/姓名/证件号

package com.example.shop.common.util;

public final class MaskUtil {
    private MaskUtil() {}

    public static String phone(String raw) {
        if (raw == null || raw.length() < 7) return raw;
        return raw.substring(0, 3) + "****" + raw.substring(raw.length() - 4);
    }

    public static String email(String raw) {
        if (raw == null || !raw.contains("@")) return raw;
        String[] p = raw.split("@", 2);
        String name = p[0];
        String masked = name.length() <= 2 ? name.charAt(0) + "*" : name.substring(0, 2) + "***";
        return masked + "@" + p[1];
    }

    public static String name(String raw) {
        if (raw == null || raw.isEmpty()) return raw;
        if (raw.length() == 1) return "*";
        return raw.charAt(0) + "*".repeat(raw.length() - 1);
    }

    /** 通用证件号/卡号：保留前 6 和后 4 */
    public static String idGeneric(String raw) {
        if (raw == null || raw.length() <= 10) return raw;
        return raw.substring(0, 6) + "****" + raw.substring(raw.length() - 4);
    }
}
