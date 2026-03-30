package com.github.cyforkk.redis.core;

/**
 * ClassName: CyforkkIdGenerator
 * Package: com.github.cyforkk.redis.core
 *
 * @Author cyforkk
 * @Create 2026/3/30 下午9:42
 * @Version 1.0
 */

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 *  Cyforkk 工业级全局唯一 ID 生成器
 *
 */
public class CyforkkIdGenerator {
    // 1. 变成普通实例变量
    private final long beginTimestamp;

    private static final int COUNT_BITS = 32;

    private final StringRedisTemplate stringRedisTemplate;

    // 2. 构造函数强制要求传入纪元起点
    public CyforkkIdGenerator(StringRedisTemplate stringRedisTemplate, long beginTimestamp) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.beginTimestamp = beginTimestamp;
    }

    public long nextId(String keyPrefix){
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);

        // 3. 使用动态配置的起点来计算时间戳差值
        long timestamp = nowSecond - this.beginTimestamp;

        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        Long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        if (count == null) {
            throw new RuntimeException("Redis 生成 ID 失败");
        }

        return timestamp << COUNT_BITS | count;
    }
}
