package com.github.cyforkk.redis.annotation;

import java.lang.annotation.*;
import java.util.concurrent.TimeUnit;

/**
 * 核心高可用控制注解：分布式缓存驱动 (Distributed Cache-Aside Engine)
 * <p>
 * 【架构语义】
 * 本注解实现了工业级的旁路缓存模式（Cache-Aside Pattern）。
 * 旨在通过 AOP 切面实现缓存逻辑与业务逻辑的绝对解耦。业务开发者只需声明本注解，
 * 即可瞬间获得高性能的 Redis 读写分离能力，且无需在业务代码中混杂任何 Redis API 调用。
 * <p>
 * 【核心机制】
 * 1. <b>防缓存穿透（Anti-Penetration）</b>：当数据库查询结果为空时，底层引擎将自动在 Redis 中生成极短生命周期的 {@code @@NULL@@} 魔法值，强力阻断黑客利用无效 ID 疯狂砸击数据库的恶意行为。
 * 2. <b>序列化自愈（Self-Healing）</b>：内置 Jackson 序列化引擎，自动处理基础类型拆箱防护与 String 类型的包装剥离。如遇脏数据导致反序列化失败，切面会自动清除死链并放行至数据库，实现自愈。
 * 3. <b>柔性高可用（Fail-Open）</b>：结合底层的熔断护盾，当 Redis 集群发生物理宕机时，缓存切面将静默放行所有请求直达数据库，保障全站业务的弱依赖可用性。
 *
 * @Author cyforkk
 * @Create 2026/3/25 下午9:16
 * @Version 1.0
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Documented
public @interface RedisCache {

    /**
     * 缓存 Key 的物理隔离前缀（Namespace）。
     * <p>
     * <b>架构规范：</b> 必须强制填写，用于区分不同业务线的数据。
     * 建议遵循 {@code 业务线:模块:实体:} 的三级命名法，例如：{@code "citypulse:post:detail:"}。
     *
     * @return 缓存前缀字符串
     */
    String keyPrefix();

    /**
     * 动态业务上下文的 SpEL 提取表达式。
     * <p>
     * 结合 {@link #keyPrefix()} 生成最终的 Redis Key。
     * <p>
     * <b>最佳实践：</b>
     * <ul>
     * <li>提取简单参数：{@code @RedisCache(keyPrefix = "user:", key = "#id")}</li>
     * <li>提取对象属性：{@code @RedisCache(keyPrefix = "user:", key = "#req.userId")}</li>
     * </ul>
     * <b>架构契约：</b>若未提供该表达式（空串），底层引擎将退化为【静态全局缓存】，
     * 直接使用 keyPrefix 作为完整的 Redis Key。严禁框架瞎猜参数！
     *
     * @return SpEL 表达式字符串
     */
    String key() default "";

    /**
     * 缓存数据的生命周期（TTL）。
     * <p>
     * <b>穿透防御提示：</b>
     * 针对数据库返回 {@code null} 的无效查询，底层引擎不会使用此完整 TTL，
     * 而是智能地将防穿透空值的存活时间缩短为此 TTL 的 1/5（最低保障 1 个单位时间），
     * 既保护了数据库，又避免了内存被无效死键长期占据。
     *
     * @return 存活时间，默认 30
     */
    long ttl() default 30;

    /**
     * 缓存生命周期的时间单位。
     *
     * @return 时间单位，默认为分钟 (MINUTES)
     */
    TimeUnit unit() default TimeUnit.MINUTES;

    // 随机抖动范围（默认 0，表示不抖动）
    // 如果设置为 10，则实际过期时间会在 ttl 基础上随机增减 0~10 的值
    int jitter() default 0;
}