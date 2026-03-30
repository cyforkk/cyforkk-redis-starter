package com.github.cyforkk.redis.aspect;

import cn.hutool.core.util.StrUtil;
import com.github.cyforkk.redis.annotation.Idempotent;
import com.github.cyforkk.redis.exception.IdempotentException;
import com.github.cyforkk.redis.service.RedisService;
import com.github.cyforkk.redis.util.SpelUtil;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/**
 * 核心高可用控制切面：分布式接口幂等性与防抖引擎 (Distributed Idempotency & Anti-Debounce Engine)
 * <p>
 * 【架构基调】
 * 本切面是微服务防御体系中应对“用户表单连点”、“网络超时重试”及“恶意脚本并发重放攻击”的 P0 级防线。
 * 依托 Redis 的 SETNX (Set if Not eXists) 原子指令，在指定的时间窗口内构建业务维度的物理互斥屏障，
 * 确保同一业务特征的请求在分布式集群中只被绝对放行一次，从根源上杜绝资损级脏数据的产生。
 * <p>
 * 【三大核心架构策略】
 * 1. <b>原子性互斥 (Atomic Mutex)</b>：将“状态探测”与“标记写入”合并为单次 Redis I/O，彻底消灭高并发下的 Check-Then-Act 竞态漏洞。
 * 2. <b>柔性降级可用 (Fail-Open)</b>：在 Redis 物理宕机的极端恶劣环境下，主动放弃防抖能力，誓死保障核心业务（如提交订单）的主干链路畅通。
 * 3. <b>快速熔断阻断 (Fail-Fast)</b>：一旦识别出重复的重放流量，瞬间斩断当前线程的继续执行，将海量脏流量阻挡在脆弱的数据库 I/O 之外。
 *
 * @Author cyforkk
 * @Create 2026/3/29 下午1:13
 * @Version 1.0 (Architectural Strict Edition)
 */
@Aspect
@Slf4j
// 【架构排兵布阵】：限流挡掉机器恶意刷量(@Order(1)) -> 防抖挡掉正常用户的重复提交(@Order(2)) -> 最后才允许访问缓存与数据库(@Order(10))
@Order(20)
public class IdempotentAspect {

    private final RedisService redisService;
    private final SpelUtil spelUtil;

    public IdempotentAspect(RedisService redisService, SpelUtil spelUtil) {
        this.redisService = redisService;
        this.spelUtil = spelUtil;
    }

    /**
     * 幂等性生命周期管控中枢 (Idempotency Lifecycle Controller)
     *
     * @param joinPoint  AOP 代理执行连接点
     * @param idempotent 方法上的幂等性契约注解
     * @return 真实业务的执行结果
     * @throws Throwable 业务异常或防抖阻断异常 (IdempotentException)
     */
    @Around("@annotation(idempotent)")
    public Object checkIdempotency(ProceedingJoinPoint joinPoint, Idempotent idempotent) throws Throwable {

        // ==========================================
        // Phase 1: 代理穿透与运行时元数据提取 (Proxy Penetration)
        // ==========================================
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        // 【防擦除防御】：剥离 Spring AOP 代理外壳（如 CGLIB 子类），获取原始目标类的真实方法。
        // 确保后续 SpEL 引擎的“字节码侦探”能够精准还原形参名称，防止发生 IllegalArgumentException。
        Method mostSpecificMethod = ClassUtils.getMostSpecificMethod(method, joinPoint.getTarget().getClass());

        // 委派 AST 解析引擎，基于运行时的真实参数矩阵，动态计算出唯一的幂等性锁标识
        String redisKey = generateKey(joinPoint, idempotent, mostSpecificMethod);
        Boolean isFirstRequest = false;
        // 1. 生成本次请求的唯一标识（护城河标记）
        String lockValue = java.util.UUID.randomUUID().toString();
        // ==========================================
        // Phase 2: 分布式并发原语抢占 (Concurrent Primitive Execution)
        // ==========================================
        try {
            // 利用 SETNX 原语进行绝对串行化的锁抢占。只有第一个到达的线程能写入成功并返回 true。
            isFirstRequest = redisService.setIfAbsent(
                    redisKey,
                    lockValue, // 占据内存舱位的魔法值，内容本身无业务意义
                    idempotent.expireTime(),
                    TimeUnit.SECONDS
            );
        } catch (Exception e) {
            // ==========================================
            // Phase 3: 架构级灾难隔离 (Fail-Open Degradation)
            // ==========================================
            // 若此处将 Redis 异常抛出，将导致全站所有带有 @Idempotent 的接口（如发帖、下单）集体瘫痪。
            // 故采取 Swallow（吞咽）策略，记录 ERROR 级告警日志，并强行放行当前请求，交由底层数据库的主键或乐观锁进行最终兜底。
            log.error("[Redis-Starter] 幂等性防抖组件底层 I/O 故障，触发柔性放行！Key: {}", redisKey, e);
            return joinPoint.proceed();
        }

        // ==========================================
        // Phase 4: 重放攻击判决与快速失败 (Fail-Fast Blocking)
        // ==========================================
        if (isFirstRequest != null && !isFirstRequest) {
            // 返回 false，证明该业务标识在指定的 Time Window 内已被前序线程抢占。
            // 判定为重复提交，抛出专属异常中断执行链路，全局异常处理器应将其捕获并响应友好的 HTTP 提示。
            // 【修改点】：不再打印“👉开发提示”，只做最基础的底层动作记录（或者改为 log.debug）
            log.info("[Cyforkk-Redis] 拦截到重复防抖请求: {}", redisKey);
            throw new IdempotentException(idempotent.message());
        }

        try {
            // 抢占成功，执行真实的业务逻辑
            return joinPoint.proceed();
        } catch (Throwable throwable) {
            // 【核心修复】：如果业务逻辑抛出异常（说明业务执行失败），主动删除防抖锁！
            // 防止用户修改表单后立即重试被误杀
            try {
                // 3. 【核心修复】：带上自己的标识去解锁，绝不误删别人的锁！
                redisService.safeDelete(redisKey, lockValue);
            } catch (Exception ex) {
                // 吞咽删除失败的异常，不掩盖原始业务异常
                log.warn("[Redis-Starter] 业务执行失败，主动释放防抖锁时发生网络异常。Key: {}", redisKey);
            }
            throw throwable; // 原样抛出业务异常
        }
    }

    /**
     * 内部路由：高精度幂等性 Key 动态生成器
     * <p>
     * 架构契约：
     * 1. 静态退化：未配置 SpEL 表达式时，默认锁住整个接口（全局防抖）。
     * 2. 动态提取：严格调用底层的高性能 AST 引擎进行强类型计算，杜绝模糊匹配。
     */
    private String generateKey(ProceedingJoinPoint joinPoint, Idempotent idempotent, Method method) {
        String prefix = idempotent.keyPrefix();
        String spEL = idempotent.key();

        if (StrUtil.isBlank(spEL)) {
            // 未声明动态 SpEL，退化为全局静态 Key，极大概率用于“全局仅允许单人操作”的超低频管理端接口
            return prefix;
        }

        // 将环境沙箱 (Target) 与运行时实参 (Args) 交予 AST 编译器执行提取
        String id = spelUtil.parse(spEL, method, joinPoint.getArgs(), joinPoint.getTarget());
        return prefix + id;
    }
}