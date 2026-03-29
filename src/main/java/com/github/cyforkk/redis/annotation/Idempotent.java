package com.github.cyforkk.redis.annotation;

import java.lang.annotation.*;
import java.util.concurrent.TimeUnit;

/**
 * 核心高可用控制注解：分布式接口幂等性与防抖护盾 (Idempotency Shield)
 * <p>
 * 【架构语义】
 * 旨在解决高并发场景下的“表单重复提交”、“脚本恶意重放”等资损级灾难。
 * 底层依靠 Redis 的 SETNX (Set if Not eXists) 机制，保障在指定的时间窗口内，
 * 具有相同业务标识（Key）的请求，只能有 1 个被放行执行。
 *
 * @Author cyforkk
 * @Version 1.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Idempotent {

    /**
     * 1. 【前缀】：幂等性 Key 的物理隔离前缀
     * 规范：通常以业务动作命名，例如 "idempotent:order:submit:"
     */
    String keyPrefix();

    /**
     * 2. 【Key】：动态业务上下文提取（支持 SpEL 表达式）
     * 最佳实践：提取具有唯一性的业务 ID。例如 "#order.orderNo" 或 "#req.userId"
     */
    String key() default "";

    /**
     * 3. 【时间窗口】：防抖的锁定时间
     * 默认锁定 5 秒。在这 5 秒内，同样的业务 Key 发起的请求会被直接打回。
     */
    long expireTime() default 5;

    /**
     * 4. 【报错提示】：触发防抖拦截时的友好提示文案
     */
    String message() default "请勿重复提交，正在处理中...";
}