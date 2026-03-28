package com.github.cyforkk.redis.aspect;

import com.github.cyforkk.redis.annotation.NoFallback;
import com.github.cyforkk.redis.util.DefaultValueUtil;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * 核心高可用控制切面：底层分布式熔断护盾 (Underlying Distributed Circuit Breaker)
 * <p>
 * 【架构语义】
 * 本切面是整个 Redis Starter 组件的“最后一道物理防线”。
 * 旨在通过 AOP 拦截 {@code RedisService} 的所有底层调用，构建一个微型的、自适应的断路器（Circuit Breaker）状态机。
 * 保护上层业务主链路（如：发帖、浏览）不被 Redis 的物理宕机或网络雪崩所拖垮。
 * <p>
 * 【四大核心架构机制】
 * 1. <b>柔性可用与静默放行 (Fail-Open)</b>：默认策略下，当探测到 Redis 物理故障或触发熔断时，将吞咽异常并返回安全默认值（如 null, 0），实现业务系统对缓存宕机的“零感知”。
 * 2. <b>爆炸半径控制 (Blast Radius Control)</b>：极其严格地通过 Exception 类型区分“网络/物理宕机”与“语法/代码Bug”。代码 Bug 绝不计入熔断阈值，拒绝为业务方的烂代码买单，防止引发全局限流/缓存大面积瘫痪。
 * 3. <b>高并发状态机 (High-Concurrency State Machine)</b>：摒弃沉重的锁机制，底层采用 {@link LongAdder} (分段 CAS 机制) 与 {@code volatile} (内存屏障) 维护熔断器的错误计数与冷却时间戳，榨干每一滴 CPU 性能。
 * 4. <b>动态防御策略路由 (Dynamic Policy Routing)</b>：利用代理穿透技术探查 {@link NoFallback} 注解，在遇到核心资金/安全链路时，瞬间从 Fail-Open 切换至 Fail-Closed（快速失败），保证强一致性。
 *
 * @Author cyforkk
 * @Create 2026/3/25 下午1:53
 * @Version 1.0
 */
@Aspect
// 【架构排兵布阵】：设置为全局最高优先级。
// 必须确保本切面包裹在 @RateLimit 和 @RedisCache 的最内层，作为直面底层物理连接的最后一道壁垒。
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class RedisFaultToleranceAspect {

    /** 熔断跳闸阈值：时间窗口内连续发生物理故障的次数 */
    private static final int ERROR_THRESHOLD = 5;

    /** 熔断冷却时间窗口 (Half-Open 探测准备期)，单位：毫秒 */
    private static final long BREAK_WINDOW = 10000L;

    /** * 分布式错误计数器
     * 架构考量：放弃 AtomicLong，使用 LongAdder 应对高并发下 CPU 自旋带来的性能损耗 (分离热点数据)
     */
    private final LongAdder errorCount = new LongAdder();

    /** * 最后一次物理异常发生的时间戳
     * 架构考量：使用 volatile 建立内存可见性屏障，确保所有线程实时感知跳闸时间
     */
    private volatile long lastErrorTime = 0L;
    // 【新增】：探针互斥锁，保障半开状态下只有一个线程能去探测后端
    private final AtomicBoolean isProbing = new AtomicBoolean(false);
    /**
     * 物理拦截探针：精确锚定本 Starter 内部暴露的 Redis 门面服务类
     */
    @Pointcut("execution(public * com.github.cyforkk.redis.service.RedisService.*(..))")
    public void servicePointCut() {
    }

    /**
     * 熔断器生命周期核心引擎 (Circuit Breaker Lifecycle)
     *
     * @param joinPoint 底层连接切点
     * @return 真实的 Redis 执行结果或降级兜底值
     * @throws Throwable 快速失败抛出的业务异常或底层阻断异常
     */
    @Around("servicePointCut()")
    public Object doAround(ProceedingJoinPoint joinPoint) throws Throwable {
        long currentTime = System.currentTimeMillis();

        // ==========================================
        // Phase 1: 状态机探测 (State Evaluation) - 处于 Open (开启) 状态
        // ==========================================
        // 如果错误次数达标，且距离上次错误发生还在冷却期内，直接拦截，拒绝向下游发送 I/O 请求

        if (errorCount.intValue() >= ERROR_THRESHOLD) {
            if ((currentTime - lastErrorTime) < BREAK_WINDOW) {
                // 1. 冷却期内 (Open状态)：全量拦截
                return handleFallback(joinPoint, null);
            } else {
                // 2. 冷却期结束 (Half-Open状态)：尝试获取唯一的探针执行权 (CAS操作无锁化)
                if (isProbing.compareAndSet(false, true)) {
                    try {
                        log.info("Redis 熔断器进入半开状态，发送单点探针试探...");
                        Object result = joinPoint.proceed();
                        // 探针试探成功，彻底重置熔断器 (Closed)
                        errorCount.reset();
                        log.info("Redis 探针试探成功，熔断器彻底闭合！");
                        return result;
                    } catch (Throwable t) {
                        // 探针试探失败，更新时间戳，继续熔断 (Open)
                        lastErrorTime = System.currentTimeMillis();
                        throw t;
                    } finally {
                        // 无论探针成功与否，必须释放探针锁
                        isProbing.set(false);
                    }
                } else {
                    // 3. 没有抢到探针权的并发请求，继续走降级拦截逻辑，绝不给 Redis 增加压力
                    return handleFallback(joinPoint, null);
                }
            }
        }

        try {
            // ==========================================
            // Phase 2: 执行探测 (Execution) & 闭合恢复 (Closed/Half-Open)
            // ==========================================
            Object result = joinPoint.proceed();

            // 如果曾有报错记录，但本次执行成功，说明网络抖动已恢复，瞬间重置状态机计数
            if (errorCount.intValue() > 0) {
                errorCount.reset();
                log.info("Redis 服务恢复正常，熔断重置！");
            }
            return result;

        } catch (Throwable throwable) {
            // ==========================================
            // Phase 3: 异常爆炸半径控制 (Blast Radius Isolation)
            // ==========================================

            // 场景 A：真实的物理/网络层级宕机
            if (throwable instanceof RedisConnectionFailureException || throwable instanceof QueryTimeoutException) {
                errorCount.increment();
                lastErrorTime = System.currentTimeMillis();
                log.error("Redis 物理连接或超时故障，当前连续错误计数：{}", errorCount.intValue());

                // 进入降级路由进行兜底
                return handleFallback(joinPoint, throwable);
            } else {
                // 场景 B：业务语法错误、类型转换错误、空指针等代码 Bug
                // 【架构铁律】：绝不能生吞代码 Bug，也绝不将其计入熔断阈值导致全站误伤跳闸。必须 Fail-Fast！
                log.warn("Redis 发生非网络层面异常，拒绝降级，直接抛出：{}", throwable.getMessage());
                throw throwable;
            }
        }
    }

    /**
     * 智能降级与阻断路由分发器 (Fallback & Block Router)
     *
     * @param joinPoint     拦截的上下文
     * @param originalError 引发降级的原始异常（若为 null 则代表处于熔断冷却拦截期）
     * @return 降级的安全默认值
     * @throws Throwable 若命中了 @NoFallback 契约，则拒绝降级，直接向上层抛错
     */
    private Object handleFallback(ProceedingJoinPoint joinPoint, Throwable originalError) throws Throwable {
        // 1. 获取方法签名元数据
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        // 2. AOP 代理穿透：探查底层被 CGLIB/JDK 代理包装的真实目标方法
        Method specificMethod = ClassUtils.getMostSpecificMethod(method, joinPoint.getTarget().getClass());

        // 3. 契约探查：探测业务方是否强制要求了 Fail-Closed（快速失败）阻断策略
        NoFallback noFallback = AnnotationUtils.findAnnotation(specificMethod, NoFallback.class);

        if (noFallback != null) {
            // 命中了强一致性契约，拒绝兜底！
            if (originalError != null) {
                throw originalError; // 由真实宕机引发的，原样上抛让业务方感知
            } else {
                // 由熔断器拦截引发的，抛出专属熔断阻断异常
                throw new RuntimeException("Redis 熔断器已开启，关键业务拒绝执行并抛出异常");
            }
        }

        // 4. 柔性兜底 (Fail-Open)：
        // 委派工具类，基于目标方法的返回类型，智能生成安全的基础类型默认值 (0, false 等) 或 null。
        // 彻底杜绝因 AOP 切面强行返回 null 导致业务调用方引发基础数据类型的拆箱 NullPointerException。
        return DefaultValueUtil.getPrimitiveDefaultValue(specificMethod.getReturnType());
    }
}