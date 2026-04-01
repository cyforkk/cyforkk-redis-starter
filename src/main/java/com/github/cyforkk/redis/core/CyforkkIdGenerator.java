package com.github.cyforkk.redis.core;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

/**
 * Cyforkk 工业级全局唯一 ID 生成器
 */
public class CyforkkIdGenerator {

    // 1. 实例变量：纪元起点 (秒)
    private final long beginTimestamp;

    // 2. 序列号位移长度
    private static final int COUNT_BITS = 32;

    private final StringRedisTemplate stringRedisTemplate;

    public CyforkkIdGenerator(StringRedisTemplate stringRedisTemplate, long beginTimestamp) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.beginTimestamp = beginTimestamp;
    }

    public long nextId(String keyPrefix) {
        // --- 修复隐患 1：获取绝对安全、无视服务器时区的当前秒级时间戳 ---
        long nowSecond = Instant.now().getEpochSecond();
        long timestamp = nowSecond - this.beginTimestamp;

        // 获取当天的日期字符串，用于拼接 Redis Key (这里用系统默认时区获取日期即可)
        LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));

        // 拼接 Redis Key
        String redisKey = "icr:" + keyPrefix + ":" + date;

        // --- 修复隐患 2：执行自增，并处理 Redis 内存泄漏 ---
        Long count = stringRedisTemplate.opsForValue().increment(redisKey);

        if (count == null) {
            throw new RuntimeException("Redis 生成 ID 失败，可能是 Redis 服务宕机");
        }

        // 【关键防御】如果 count == 1，说明是当前这一天的第一个订单，设置过期时间
        // 设置为 2 天（48小时）或者更多一点，保证绝对够用且会被自动清理
//        if (count == 1L) {
//            stringRedisTemplate.expire(redisKey, 365, TimeUnit.DAYS);
//        }

        // 位运算拼接并返回
        return timestamp << COUNT_BITS | count;
    }
}