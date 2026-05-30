package com.ironbro.interviewhub.common.util;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 随机数工具类（后面考虑统一替换为 ThreadLocalRandom）
 */
public final class RandomGenerator {

    private RandomGenerator() {
    }

    public static int nextInt(int bound) {
        return ThreadLocalRandom.current().nextInt(bound);
    }

    public static int nextInt(int origin, int bound) {
        return ThreadLocalRandom.current().nextInt(origin, bound);
    }

    public static long nextLong(long bound) {
        return ThreadLocalRandom.current().nextLong(bound);
    }

    public static String randomString(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append((char) ('a' + ThreadLocalRandom.current().nextInt(26)));
        }
        return sb.toString();
    }
}
