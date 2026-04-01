package com.github.cyforkk.redis.core;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 轻量级 Redis 分布式锁
 */
public class CyforkkRedisLock {

    private final StringRedisTemplate redisTemplate;
    private final String name; // 锁的名称 (业务前缀)
    private final String idPrefix; // 当前 JVM 的唯一标识

    // 提前写好 Lua 脚本，保证释放锁时的原子性（判断是不是自己的锁 -> 是就删除）
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setScriptText(
                "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                "return redis.call('del', KEYS[1]) " +
                "else return 0 end"
        );
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public CyforkkRedisLock(String name, StringRedisTemplate redisTemplate) {
        this.name = name;
        this.redisTemplate = redisTemplate;
        // 使用 UUID 作为当前线程标识的前缀，防止不同服务器线程号冲突
        this.idPrefix = UUID.randomUUID() + "-";
    }

    /**
     * 尝试获取锁
     * @param timeoutSec 锁的自动过期时间（兜底，防死锁）
     */
    public boolean tryLock(long timeoutSec) {
        // 获取线程标示 (UUID + 线程ID)
        String threadId = idPrefix + Thread.currentThread().getId();
        // 执行 SETNX 命令: SET lock:name threadId EX timeoutSec NX
        Boolean success = redisTemplate.opsForValue()
                .setIfAbsent("lock:" + name, threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success); // 防止自动拆箱空指针
    }

    /**
     * 释放锁 (使用 Lua 脚本保证防误删的安全)
     */
    public void unlock() {
        // 执行 Lua 脚本
        redisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList("lock:" + name),
                idPrefix + Thread.currentThread().getId()
        );
    }
}