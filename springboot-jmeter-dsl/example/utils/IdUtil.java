package com.example.shop.common.util;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * ID 生成工具：
 * - uuid(): 标准 UUIDv4
 * - shortUuid(): 使用 Base62 压缩的短 UUID（适合日志展示/URL）
 * - snowflake(): 雪花算法（64bit，趋势递增，适合主键）
 * - ulid(): ULID（时间有序，字符串排序即时间排序）
 */
public final class IdUtil {
    private IdUtil() {}

    // ---------------- UUID / ShortUUID ----------------
    public static String uuid() {
        return java.util.UUID.randomUUID().toString();
    }

    public static String shortUuid() {
        // 以 128 bit UUID 转 22 字符 Base64URL（无 '='）
        var uuid = java.util.UUID.randomUUID();
        var bb = new byte[16];
        putLong(bb, 0, uuid.getMostSignificantBits());
        putLong(bb, 8, uuid.getLeastSignificantBits());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bb);
    }

    private static void putLong(byte[] b, int off, long v) {
        for (int i = 7; i >= 0; i--) b[off + i] = (byte) (v & 0xFFL); v >>>= 8;
    }

    // ---------------- Snowflake ----------------
    private static final Snowflake SNOWFLAKE = new Snowflake(
            getPropAsLong("SNOWFLAKE_DATACENTER", 1),
            getPropAsLong("SNOWFLAKE_WORKER", 1)
    );

    public static long snowflake() {
        return SNOWFLAKE.nextId();
    }

    // 经典雪花实现
    public static final class Snowflake {
        private static final long EPOCH = 1577836800000L; // 2020-01-01
        private static final long WORKER_ID_BITS = 5L;
        private static final long DATACENTER_ID_BITS = 5L;
        private static final long SEQUENCE_BITS = 12L;

        private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);       // 31
        private static final long MAX_DATACENTER_ID = ~(-1L << DATACENTER_ID_BITS);// 31
        private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;                 // 12
        private static final long DATACENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS; // 17
        private static final long TIMESTAMP_LEFT_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATACENTER_ID_BITS; // 22
        private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS); // 4095

        private final long workerId;
        private final long datacenterId;
        private long sequence = 0L;
        private long lastTimestamp = -1L;

        public Snowflake(long datacenterId, long workerId) {
            if (workerId > MAX_WORKER_ID || workerId < 0)
                throw new IllegalArgumentException("workerId out of range");
            if (datacenterId > MAX_DATACENTER_ID || datacenterId < 0)
                throw new IllegalArgumentException("datacenterId out of range");
            this.workerId = workerId;
            this.datacenterId = datacenterId;
        }

        public synchronized long nextId() {
            long ts = timeGen();
            if (ts < lastTimestamp) {
                // 时钟回拨：等待至可用
                long offset = lastTimestamp - ts;
                try { Thread.sleep(Math.min(offset, 5)); } catch (InterruptedException ignored) {}
                ts = timeGen();
                if (ts < lastTimestamp) ts = lastTimestamp; // 兜底
            }

            if (lastTimestamp == ts) {
                sequence = (sequence + 1) & SEQUENCE_MASK;
                if (sequence == 0) ts = tilNextMillis(lastTimestamp);
            } else {
                sequence = ThreadLocalRandom.current().nextInt(1, 3); // 抖动，降低碰撞
            }
            lastTimestamp = ts;

            return ((ts - EPOCH) << TIMESTAMP_LEFT_SHIFT)
                    | (datacenterId << DATACENTER_ID_SHIFT)
                    | (workerId << WORKER_ID_SHIFT)
                    | sequence;
        }

        private long tilNextMillis(long lastTs) {
            long ts = timeGen();
            while (ts <= lastTs) ts = timeGen();
            return ts;
        }
        private long timeGen() { return System.currentTimeMillis(); }
    }

    private static long getPropAsLong(String key, long def) {
        String v = System.getenv(key);
        if (v == null) v = System.getProperty(key);
        if (v == null) return def;
        try { return Long.parseLong(v); } catch (Exception e) { return def; }
    }

    // ---------------- ULID ----------------
    private static final char[] ENCODING = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray(); // Crockford
    private static final SecureRandom SEC = new SecureRandom();

    /** 26位 ULID（字典序即时间序） */
    public static String ulid() {
        long time = Instant.now().toEpochMilli();
        byte[] rand = new byte[10]; SEC.nextBytes(rand);
        char[] out = new char[26];
        // 48-bit time -> 10 chars
        encodeBase32(out, 0, time >>> 40, 2);
        encodeBase32(out, 2, time >>> 32, 2);
        encodeBase32(out, 4, time >>> 24, 2);
        encodeBase32(out, 6, time >>> 16, 2);
        encodeBase32(out, 8, time >>> 8, 2);
        encodeBase32(out,10, time, 2);
        // 80-bit random -> 16 chars
        long r0 = bytesToLong(rand, 0);
        long r1 = ((long)(rand[8] & 0xFF) << 8) | (rand[9] & 0xFF);
        encodeBase32(out, 12, r0 >>> 32, 8);
        encodeBase32(out, 20, ((r0 & 0xFFFFFFFFL) << 16) | r1, 6);
        return new String(out);
    }
    private static void encodeBase32(char[] out, int pos, long val, int chars) {
        for (int i = chars - 1; i >= 0; i--) {
            out[pos + i] = ENCODING[(int)(val & 31)];
            val >>>= 5;
        }
    }
    private static long bytesToLong(byte[] b, int off) {
        long v = 0; for (int i = 0; i < 8; i++) v = (v << 8) | (b[off + i] & 0xFF); return v;
    }
}
