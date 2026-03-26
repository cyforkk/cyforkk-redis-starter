package com.github.cyforkk.redis.aspect;

import cn.hutool.core.util.StrUtil;
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

/**
 * 核心高可用控制切面：分布式旁路缓存引擎 (Distributed Cache-Aside Engine)
 * <p>
 * 【架构语义】
 * 本切面是 {@link RedisCache} 的底层执行引擎，实现了工业级的 Cache-Aside（旁路缓存）状态机。
 * 旨在通过 AOP 动态代理，将高度重复的“查缓存 -> 查库 -> 写缓存”逻辑从业务代码中彻底剥离，实现业务零侵入。
 * <p>
 * 【四大核心架构机制】
 * 1. <b>代理穿透技术</b>：底层采用 {@code ClassUtils.getMostSpecificMethod} 穿透 CGLIB/JDK 动态代理，确保多重 AOP 嵌套下仍能精准提取参数名与注解契约。
 * 2. <b>缓存穿透防御 (Anti-Penetration)</b>：针对 DB 查询结果为空的非法请求，智能写入 {@code @@NULL@@} 魔法值，并按比例衰减其 TTL，构建第一道防刷屏障。
 * 3. <b>类型安全与异常自愈 (Self-Healing)</b>：内置多态反序列化路由。当遭遇脏数据引发 JSON 转换异常时，触发自愈机制（主动删除死键并放行回源）。
 * 4. <b>柔性可用 (Fail-Open)</b>：所有 Redis 写入动作均被 {@code try-catch} 隔离，查询动作受控于底层熔断器。当物理层宕机时，全站缓存静默失效，平滑降级至 DB。
 *
 * @Author cyforkk
 * @Create 2026/3/25 下午9:47
 * @Version 1.0
 */
@Aspect
@Order(10)
@Slf4j
public class CacheAsideAspect {
    private final RedisService redisService;
    private final SpelUtil spelUtil;
    private final ObjectMapper objectMapper;

    /**
     * 缓存穿透防御的全局魔法值 (Sentinel Value)
     */
    private static final String CACHE_NULL_VALUE = "@@NULL@@";

    public CacheAsideAspect(RedisService redisService, SpelUtil spelUtil, ObjectMapper objectMapper) {
        this.redisService = redisService;
        this.spelUtil = spelUtil;
        this.objectMapper = objectMapper;
    }

    /**
     * 核心旁路缓存拦截链路 (Cache-Aside Lifecycle)
     *
     * @param joinPoint  AOP 切入点（承载目标对象与运行时参数）
     * @param redisCache 方法上的缓存契约注解
     * @return 最终返回给业务调用方的反序列化对象或基础类型默认值
     */
    @SneakyThrows
    @Around("@annotation(redisCache)")
    public Object around(ProceedingJoinPoint joinPoint, RedisCache redisCache) {
        // ==========================================
        // Phase 1: 代理穿透与元数据提取
        // ==========================================
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        // 【P0 级防护】：穿透 Spring 代理层，直接拿到最底层的真实方法，防止 SpEL 参数名丢失！
        Method specificMethod = ClassUtils.getMostSpecificMethod(method, joinPoint.getTarget().getClass());

        // 目标方法返回值为 Void ，无缓存意义，直接放行执行原方法 (Fast-Return)
        if (specificMethod.getReturnType() == void.class) {
            return joinPoint.proceed();
        }

        // 1. 解析 Key： 算出这次请求对应的物理键名
        String key;
        try {
            // 传给 SpEL 引擎的，必须是穿透代理后的 specificMethod
            key = generateKey(joinPoint, redisCache, specificMethod);
        } catch (Exception e) {
            // 容错机制：如果 AST 树解析失败（如表达式语法错误），记录告警并退化为直连 DB，拒绝抛错引发雪崩
            log.warn("Key generation failed, skipping cache: {}", e.getMessage());
            return joinPoint.proceed();
        }

        // ==========================================
        // Phase 2: 缓存探针探测 (Cache Probe)
        // ==========================================
        String json = redisService.get(key);

        // ==========================================
        // Phase 3: 缓存命中与反序列化状态机
        // ==========================================
        // StrUtil.isNotBlank 排除了 null 和 "" 的干扰
        if (StrUtil.isNotBlank(json)) {

            // 【防穿透拦截】：识别到魔法值 "@@NULL@@"，说明已被标记为空资源
            if (CACHE_NULL_VALUE.equals(json)) {
                // 安全阻断：调用基础类型工具类，防止返回 null 触发 JVM 自动拆箱导致的 NullPointerException
                return DefaultValueUtil.getPrimitiveDefaultValue(specificMethod.getReturnType());
            }

            // 【序列化直通车】：如果目标方法本身就要求返回 String，跳过 Jackson 引擎，防止重复转义报错
            if (specificMethod.getReturnType() == String.class) {
                return json;
            }

            // 泛型安全反序列化
            try {
                // 利用反射获取带有泛型签名的真实返回值类型（如 List<UserDTO>），交由 Jackson 精准还原
                Type returnType = specificMethod.getGenericReturnType();
                JavaType javaType = objectMapper.getTypeFactory().constructType(returnType);
                return objectMapper.readValue(json, javaType);
            } catch (Exception e) {
                // 【架构自愈机制】：若发生实体类字段变更导致的 JsonParseException，
                // 主动剔除该“脏数据/死键”，将本次请求视作 Cache Miss 处理。
                log.error("Deserialize failed, deleting key: {}", key);
                try {
                    redisService.delete(key);
                } catch (Exception ex) { /* ignore */ }
            }
        }

        // ==========================================
        // Phase 4: 回源查询 (Back-to-Origin)
        // ==========================================
        // 缓存未命中、或反序列化自愈触发，物理放行至目标方法（通常为 SQL 查询）
        Object result = joinPoint.proceed();

        // ==========================================
        // Phase 5: 缓存回写与防穿透兜底 (Write-back)
        // ==========================================
        try {
            if (result != null) {
                // 【序列化直通车】：若是 String，拒绝 Jackson 的双引号包装，维持原样存储
                String jsonValue = (specificMethod.getReturnType() == String.class) ?
                        (String) result :
                        objectMapper.writeValueAsString(result);
                redisService.set(key, jsonValue, redisCache.ttl(), redisCache.unit());
            } else {
                // 【防穿透落盘】：DB 查无此数据。生成极短生命周期的 @@NULL@@ 魔法值
                // 智能缩短 TTL：采用业务配置时间的 1/5（确保不过度占用内存，且最低保障 1 个时间单位）
                long nullTtl = Math.max(1, redisCache.ttl() / 5);
                redisService.set(key, CACHE_NULL_VALUE, nullTtl, redisCache.unit());
            }
        } catch (Exception e) {
            // 柔性降级：Redis 写入失败不应影响此次读请求的结果返回
            log.warn("Redis set failed: {}", e.getMessage());
        }

        // 将最终的源站结果投递给上游调用方
        return result;
    }

    /**
     * 高级键名生成路由 (Key Generation Router)
     *
     * @param joinPoint  切点上下文
     * @param redisCache 缓存元数据注解
     * @param method     穿透代理后的真实方法
     * @return 最终落盘 Redis 的物理键名
     */
    private String generateKey(ProceedingJoinPoint joinPoint, RedisCache redisCache, Method method) {
        // 1. 提取元数据
        String prefix = redisCache.keyPrefix(); // 物理隔离前缀，如 "user:info:"
        String spEL = redisCache.key();         // AST 表达式，如 "#id"
        Object[] args = joinPoint.getArgs();    // 真实运行入参

        // 2. 委派 SpEL 引擎构建动态后缀
        String id = spelUtil.parse(spEL, method, args, joinPoint.getTarget());

        // 3. 路由判决：有动态 ID 则拼接，无则进入安全兜底策略
        if (id != null) {
            return prefix + id;
        }

        // 4. 【安全兜底】：未填 SpEL 表达式时，尝试将首个“简单值类型”参数作为 ID 后缀
        if (args != null && args.length > 0 && args[0] != null && spelUtil.isSimpleType(args[0].getClass())) {
            return prefix + args[0];
        }

        // 极端兜底：无参或无合法参数，退化为全局静态 Key
        return prefix;
    }
}