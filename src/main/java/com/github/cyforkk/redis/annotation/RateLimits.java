package com.github.cyforkk.redis.annotation;

import java.lang.annotation.*;

/**
 * 核心高可用控制注解：多维限流规则容器 (Rate Limits Container)
 * <p>
 * 【架构语义】
 * 本注解为 {@link RateLimit} 的专属复数容器，是支撑“多维漏斗防御（Multi-Dimensional Funnel Defense）”体系的核心基石。
 * 基于 Java 8+ 的 {@link Repeatable} 特性，它使得在同一业务方法上堆叠多个限流策略成为可能。
 * <p>
 * 【底层机制】
 * 业务开发者通常无需显式声明本注解。当业务层在同一方法上多次标注 {@code @RateLimit} 时，
 * Java 编译器会在 AST (抽象语法树) 阶段自动将它们打包进本容器的 {@code value()} 数组中。
 * 底层的限流引擎（{@code RateLimitAspect}）通过 {@code AnnotatedElementUtils} 穿透代理层提取该数组，
 * 并按照声明顺序依次进行 Lua 脚本的原子校验，任何一个维度的阈值被击穿，都会立即触发 Fail-Fast 熔断阻断。
 * <p>
 * 【最佳实践场景】
 * 适用于构建长短周期结合的阶梯式防御网。例如：
 * {@code @RateLimit(time = 1, maxCount = 3)}  // 防瞬时恶意并发（1秒3次）
 * {@code @RateLimit(time = 86400, maxCount = 100)} // 防单日总量耗尽（1天100次）
 *
 * @Author cyforkk
 * @Create 2026/3/25 下午5:10
 * @Version 1.0
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Documented
public @interface RateLimits {

    /**
     * 承载多个具体限流规则的数组容器。
     * * @return 业务方法上声明的所有 @RateLimit 规则集合
     */
    RateLimit[] value();
}