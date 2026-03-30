package com.github.cyforkk.redis.aspect;

import cn.hutool.core.util.StrUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.cyforkk.redis.annotation.RedisCache;
import com.github.cyforkk.redis.service.RedisService;
import com.github.cyforkk.redis.util.DefaultValueUtil;
import com.github.cyforkk.redis.util.SpelUtil;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 核心高可用控制切面：工业级分布式旁路缓存引擎 (Distributed Cache-Aside Engine)
 * <p>
 * 【架构基调】
 * 本切面是微服务与 Redis 基础设施之间的“物理隔离带”。通过 AOP 拦截器构建标准状态机，
 * 实现零侵入的缓存读写分离。系统性剿灭缓存领域的三大灾难：穿透、雪崩、击穿。
 * <p>
 * 【五大架构防御机制】
 * 1. <b>防穿透 (Anti-Penetration)</b>：采用 {@code @@NULL@@} 哨兵值 + TTL 智能衰减，阻断恶意伪造 ID 攻击。
 * 2. <b>防雪崩 (Anti-Avalanche)</b>：依托 {@link ThreadLocalRandom} 无锁并发注入 TTL 随机扰动 (Jitter)，打散批量失效时间点。
 * 3. <b>防击穿 (Anti-Breakdown)</b>：落地细粒度 Double-Checked Locking (DCL) 双重检查锁，保证千万并发下单一 Key 绝对串行回源。
 * 4. <b>竞态免疫 (Race-Condition Immunity)</b>：基于 Caffeine WeakValues 构建 GC 级锁池，彻底消除并发场景下手动释放锁的内存泄漏与误删隐患。
 * 5. <b>柔性高可用 (Fail-Open)</b>：I/O 故障、网络抖动、序列化雪崩均被局部隔离（Swallow），在极端恶劣环境下誓死保障核心业务的降级可用。
 *
 * @Author cyforkk
 * @Create 2026/3/25 下午9:47
 * @Version 3.0 (Ultimate DCL & Fail-Open Refactored)
 */
@Aspect
@Order(40) // 【架构约束】：必须在限流、熔断切面之后执行，严禁为被拦截的非法流量提供缓存服务
@Slf4j
public class CacheAsideAspect {

    private final RedisService redisService;
    private final SpelUtil spelUtil;
    private final ObjectMapper objectMapper;

    /**
     * 【架构级内存基建】：GC 驱动的弱引用互斥锁池 (GC-Driven Weak Reference Lock Pool)
     * <p>
     * <b>设计考量：</b>
     * 放弃危险的手动 {@code Map.remove()}，将锁对象的生命周期管理权移交至 JVM 垃圾回收器。
     * 当该 Key 的并发洪峰退去，且没有任何线程栈帧持有该 {@link ReentrantLock} 的强引用时，
     * 下一次 Minor/Major GC 将自动回收该锁并清理 Map 节点，实现绝对安全的内存闭环。
     */
    private final Cache<String, ReentrantLock> lockMap = Caffeine.newBuilder()
            .weakValues()
            .build();

    /**
     * 【防穿透哨兵】：统一 Null 标记
     * 架构考量：消除各序列化器对 null 处理的歧义，提供明确的运维审计特征码。
     */
    private static final String CACHE_NULL_VALUE = "@@NULL@@";

    public CacheAsideAspect(RedisService redisService, SpelUtil spelUtil, ObjectMapper objectMapper) {
        this.redisService = redisService;
        this.spelUtil = spelUtil;
        this.objectMapper = objectMapper;
    }

    @SneakyThrows
    @Around("@annotation(redisCache)")
    public Object around(ProceedingJoinPoint joinPoint, RedisCache redisCache) {
        // ==========================================
        // Phase 1: 元数据提取与强隔离 Key 生成
        // ==========================================
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        // 【P0 级反射穿透】：剥离 Spring AOP 代理外壳，获取真实的业务方法实体。
        // 防止 SpEL 引擎因代理类丢失形参名而引发 IllegalArgumentException。
        Method specificMethod = ClassUtils.getMostSpecificMethod(method, joinPoint.getTarget().getClass());

        // 【性能剪枝】：void 方法无产出价值，越过状态机直达底层。
        if (specificMethod.getReturnType() == void.class) {
            return joinPoint.proceed();
        }

        String key;
        try {
            key = generateKey(joinPoint, redisCache, specificMethod);
        } catch (Exception e) {
            // 【Fail-Open 降级】：SpEL 语法错误或上下文缺失，不阻断主线，降级查库。
            log.warn("[Redis-Starter] Key路由计算失败，触发降级直达DB。Method: {}, Error: {}", specificMethod.getName(), e.getMessage());
            return joinPoint.proceed();
        }

        // ==========================================
        // Phase 2: L1 探测 (第一重检查 - 极速无锁层)
        // ==========================================
        String json = null;
        try {
            json = redisService.get(key);
        } catch (Exception e) {
            // 【网络隔离护盾】：Redis 节点单点超时或闪断，吞咽异常，视同 Cache Miss。
            log.warn("[Redis-Starter] 第一重检查 Redis I/O 异常，降级排队查库。Key: {}, Error: {}", key, e.getMessage());
        }

        if (StrUtil.isNotBlank(json)) {
            try {
                return processCacheHit(json, specificMethod, key);
            } catch (Exception e) {
                // 【反序列化隔离】：脏数据引发崩溃，吞咽异常。
                // 此时 processCacheHit 已完成自清洗，自然流转至下层回源。
                log.warn("[Redis-Starter] 第一重检查反序列化失败，视同过期，降级查库。Key: {}", key);
            }
        }

        // ==========================================
        // Phase 3: 防击穿屏障 (DCL 第二重检查 - 互斥加锁层)
        // ==========================================
        // 【原子性保障】：为当前面临击穿风险的 Key 分配独立锁实例。
        ReentrantLock lock = lockMap.get(key, k -> new ReentrantLock());
        lock.lock();
        try {
            // 【核心防线：Double-Check】：排队醒来后，必须再次质询缓存区！
            // 防止前序夺锁线程已完成 DB 查询与 Redis 回写，避免“重复击穿”。
            String secondJson = null;
            try {
                secondJson = redisService.get(key);
            } catch (Exception e) {
                // 【极限网络隔离】：即使抢到了锁，Redis 恰好此刻抽风，依然保证不抛错，坚决查库兜底！
                log.warn("[Redis-Starter] 第二重检查 Redis I/O 异常，强制回源。Key: {}", key);
            }

            if (StrUtil.isNotBlank(secondJson)) {
                try {
                    Object cachedObj = processCacheHit(secondJson, specificMethod, key);
                    log.info("[Redis-Starter] DCL 命中：排队后发现缓存已被前序线程重建，免除查库。Key: {}", key);
                    return cachedObj;
                } catch (Exception e) {
                    log.warn("[Redis-Starter] 第二重检查反序列化失败，强制回源查库。Key: {}", key);
                }
            }

            // ==========================================
            // Phase 4: 源站回源 (Back-to-Origin)
            // ==========================================
            // 千万并发中，仅有一名“天选之子”能越过屏障，将压力施加给脆弱的数据库。
            log.info("[Redis-Starter] DCL 未命中：击穿屏障，触发源站安全查库。Key: {}", key);
            Object result = joinPoint.proceed();

            // ==========================================
            // Phase 5: 缓存闭环与策略落盘 (Write-Back)
            // ==========================================
            writeBackToRedis(key, result, redisCache);

            return result;

        } finally {
            // 【绝对红线】：必须释放临界区。
            // 锁的销毁由 Caffeine weakValues 及 GC 自动接管，彻底杜绝 remove 竞态引发的脑裂。
            lock.unlock();
        }
    }

    /**
     * 内部状态机：处理缓存命中的多态反序列化与自愈逻辑
     */
    private Object processCacheHit(String json, Method specificMethod, String key) throws Exception {
        // 1. 【反穿透识别】：命中空哨兵。委派 DefaultValueUtil 处理拆箱难题（如 int 返回 0，防御 NPE）
        if (CACHE_NULL_VALUE.equals(json)) {
            return DefaultValueUtil.getPrimitiveDefaultValue(specificMethod.getReturnType());
        }

        // 2. 【序列化短路】：若目标签名要求 String，直接放行，免除无意义的 JSON 反转义。
        if (specificMethod.getReturnType() == String.class) {
            return json;
        }

        try {
            // 3. 【泛型感知反序列化】：提取完整 AST 签名（如 List<UserDTO>），杜绝 Jackson 降级为 Map 的灾难。
            Type returnType = specificMethod.getGenericReturnType();
            JavaType javaType = objectMapper.getTypeFactory().constructType(returnType);
            return objectMapper.readValue(json, javaType);
        } catch (Exception e) {
            // 4. 【系统自愈】：业务实体类结构破坏性变更导致反序列化崩溃。
            log.error("[Redis-Starter] 反序列化异常，尝试主动清洗脏数据。Key: {}", key);
            try {
                // Best-Effort 清理：若清理时 Redis 宕机，不应掩盖真正的反序列化异常
                redisService.delete(key);
            } catch (Exception ex) {
                log.warn("[Redis-Starter] 脏数据自清洗失败 (网络异常)。Key: {}", key);
            }
            throw e; // 向上抛出，交由 Phase 2/3 的 catch 块转换为 Fail-Open 回源动作
        }
    }

    /**
     * 内部状态机：处理防穿透/防雪崩的柔性写回逻辑
     */
    private void writeBackToRedis(String key, Object result, RedisCache redisCache) {
        try {
            long finalTtl = redisCache.ttl();
            int jitter = redisCache.jitter();

            // 【防雪崩机制】：业务显式授权 (jitter > 0) 方可开启扰动
            if (jitter > 0) {
                // 采用无锁发生器 ThreadLocalRandom，消除 CAS 自旋瓶颈
                long randomJitter = ThreadLocalRandom.current().nextLong(-jitter, jitter + 1L);
                finalTtl = Math.max(1, finalTtl + randomJitter); // 极值防御：防止 TTL 为负
            }

            if (result != null) {
                // 标准缓存落盘
                redisService.set(key, result, finalTtl, redisCache.unit());
            } else {
                // 【防穿透机制】：空值降维打击。
                // 缓存生命周期暴减至 1/5 (最低保底 1 单位)，既封杀穿透攻击，又释放宝贵的 Redis 内存空间。
                long nullTtl = Math.max(1, finalTtl / 5);
                redisService.set(key, CACHE_NULL_VALUE, nullTtl, redisCache.unit());
            }
        } catch (Exception e) {
            // 【Fail-Open】：Redis 故障导致写回失败，仅记录审计日志，坚决不向调用方抛出异常。
            log.warn("[Redis-Starter] Redis 写入缓存失败，主业务依然正常返回。Key: {}, Error: {}", key, e.getMessage());
        }
    }

    /**
     * 内部路由：物理 Key 的 AST 解析
     */
    private String generateKey(ProceedingJoinPoint joinPoint, RedisCache redisCache, Method method) {
        String prefix = redisCache.keyPrefix();
        String spEL = redisCache.key();

        // 【契约路由】：未配置 SpEL 则退化为接口级全局静态缓存
        if (StrUtil.isBlank(spEL)) return prefix;

        // 委托底层的 AST 引擎进行强类型计算，遇到 null 或解析失败直接抛出异常，拒绝魔法猜测。
        String id = spelUtil.parse(spEL, method, joinPoint.getArgs(), joinPoint.getTarget());
        return prefix + id;
    }
}