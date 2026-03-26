package com.github.cyforkk.redis.annotation;

import java.lang.annotation.*;

/**
 * 核心高可用控制注解：分布式原子限流防线 (Rate Limiting Shield)
 * <p>
 * 【架构语义】
 * 本注解旨在为核心业务接口提供方法级别的“流量整形（Traffic Shaping）”与防刷保护。
 * 底层基于 Redis + Lua 脚本执行，保障了分布式环境下并发递增（INCR）与设置过期时间（EXPIRE）的绝对原子性，
 * 彻底杜绝高并发下的“超卖/超流”竞态条件 Bug。
 * <p>
 * 【核心机制】
 * 1. <b>多维漏斗防御</b>：本注解已标注 {@link Repeatable}，支持在同一方法上堆叠多个规则（如：限制某 IP 1分钟访问5次，且1天最多访问50次），构建多级限流漏斗。
 * 2. <b>动态上下文提取</b>：集成高性能 SpEL (Spring Expression Language) 引擎，可直接解析方法入参对象，实现极细粒度的业务级限流（如按用户 ID、手机号限流）。
 * 3. <b>方法级物理隔离</b>：底层自动捕获并拼接“类名+方法名”作为隔离命名空间，杜绝全站同名方法的限流桶碰撞。
 * <p>
 * 【阻断策略】
 * 当触发限流阈值时，底层的切面引擎将立刻阻断业务流程，并抛出专属异常 {@code RateLimitException}，实现快速失败 (Fail-Fast)。
 *
 * @Author cyforkk
 * @Create 2026/3/25 下午4:59
 * @Version 1.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(RateLimits.class)
public @interface RateLimit {

    /**
     * 流量统计的时间窗口（固定窗口算法），单位：秒。
     * <p>
     * 架构提示：当达到该窗口期后，Redis 底层的 Key 将自动过期释放，限流计数器随之清零。
     * * @return 窗口时长，默认 60 秒
     */
    long time() default 60;

    /**
     * 在规定的时间窗口内，允许放行的最大绝对流量水位阈值（Max Permitted Count）。
     * <p>
     * 当 {@code currentCount > maxCount} 时，立刻触发限流熔断。
     *
     * @return 最大访问次数，默认 5 次
     */
    int maxCount() default 5;

    /**
     * 限流路由策略（策略模式枚举）。
     * <p>
     * 决定底层如何构建 Redis Key：
     * - 选择 {@link LimitType#IP}：进行 L4/L7 泛用型防刷（依赖 Web 容器的真实 IP 提取）。
     * - 选择 {@link LimitType#CUSTOM}：进行业务定制型防刷（需配合 {@link #key()} 使用）。
     *
     * @return 路由策略类型，默认 IP 模式
     */
    LimitType type() default LimitType.IP;

    /**
     * 动态业务上下文的 SpEL 提取表达式（仅当 {@code type = LimitType.CUSTOM} 时生效）。
     * <p>
     * <b>最佳实践：</b>
     * <ul>
     * <li>提取简单参数：{@code @RateLimit(key = "#phone")}</li>
     * <li>提取对象属性：{@code @RateLimit(key = "#userDTO.id")}</li>
     * </ul>
     * 安全兜底：如果设置为 CUSTOM 模式但未提供此表达式，底层引擎将尝试安全降级，提取方法签名中的第一个基础数据类型参数作为标识。
     *
     * @return SpEL 表达式字符串
     */
    String key() default "";

    /**
     * 触发限流后的自定义友好提示语
     */
    String message() default "请求过于频繁，请稍后再试";

    /**
     * 流量管控策略枚举
     */
    public enum LimitType {
        /**
         * 物理/网络层级防御：按照请求者的真实远端 IP 地址进行限流。
         * 适用场景：防爬虫、全局接口恶意调用拦截。
         */
        IP,

        /**
         * 业务逻辑层级防御：按照自定义的 SpEL 表达式提取的业务字段进行限流。
         * 适用场景：短信发送频控（按手机号）、点赞频控（按用户ID）、高价值资源下载频控。
         */
        CUSTOM
    }
}