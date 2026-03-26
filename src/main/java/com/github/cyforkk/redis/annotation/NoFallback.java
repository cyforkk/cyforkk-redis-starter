package com.github.cyforkk.redis.annotation;

import java.lang.annotation.*;

/**
 * 核心高可用控制注解：严格阻断标记 (Fail-Closed)
 * <p>
 * 【架构语义】
 * 默认情况下，本组件的底层容错护盾（RedisFaultToleranceAspect）采用“静默放行（Fail-Open）”的柔性可用策略。
 * 即当 Redis 发生物理宕机或连接超时时，切面会捕获异常并返回安全默认值（如 null 或 0），以防止缓存组件拖垮整个业务主链路。
 * <p>
 * 【核心机制】
 * 当目标方法贴有 {@code @NoFallback} 注解时，代表该方法为“核心安全链路”。
 * 此时，容错护盾将切换为“严格阻断（Fail-Closed）”策略。遇到任何底层物理异常，
 * 切面将拒绝执行降级兜底逻辑，而是直接将异常向上抛出，实现“快速失败（Fail-Fast）”。
 * <p>
 * 【适用场景】
 * 适用于绝不允许出现“假冒放行”或“状态机不一致”的强依赖性 Redis 业务。
 * 例如：验证码核验、分布式锁的抢占与释放、高价值资产的防刷校验等。
 *
 * @Author cyforkk
 * @Create 2026/3/25 下午4:43
 * @Version 1.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface NoFallback {
}