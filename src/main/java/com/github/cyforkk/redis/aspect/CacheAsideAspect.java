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
 * 核心高可用控制切面：工业级分布式旁路缓存引擎 (Distributed Cache-Aside Engine)
 * <p>
 * 【架构语义】
 * 本切面是实现业务逻辑与缓存基础设施“逻辑解耦”的核心枢纽。
 * 它通过 AOP 拦截器构建了一个标准的状态机，将高频重复的“探测-回源-写回”逻辑封装为零侵入的注解驱动模式。
 * <p>
 * 【四大架构防御机制】
 * 1. <b>代理穿透与契约提取</b>：利用 {@code ClassUtils} 穿透 Spring 代理层，确保在多重切面嵌套下仍能精准捕获目标方法的参数名与注解声明。
 * 2. <b>空值哨兵与穿透阻断 (Anti-Penetration)</b>：引入 {@code @@NULL@@} 哨兵值标记数据库空资源。通过短生命周期的“缓存空标记”，构建保护后端数据库的第一道防刷屏障。
 * 3. <b>类型安全与自动拆箱防护</b>：联动 {@link DefaultValueUtil} 处理基础数据类型（int, boolean 等）。严禁在期望基础类型的方法中返回 null，彻底根除 AOP 代理引起的隐蔽 NPE。
 * 4. <b>异常自愈与数据清洗 (Self-Healing)</b>：具备感知脏数据的能力。一旦反序列化因实体类结构变更而失败，切面会自动执行“逻辑驱逐”——删除 Redis 死键并强制回源，实现业务系统自修复。
 *
 * @Author cyforkk
 * @Create 2026/3/25 下午9:47
 * @Version 1.0
 */
@Aspect
@Order(10) // 优先级设定：低于限流切面，高于业务逻辑，确保流量经过筛选后再进行缓存探测
@Slf4j
public class CacheAsideAspect {

    private final RedisService redisService;
    private final SpelUtil spelUtil;
    private final ObjectMapper objectMapper;

    /**
     * 分布式空标记哨兵值 (Cache Sentinel)
     * 架构考量：统一哨兵值可大幅提升 Redis 管理员排查“空值攻击”的效率，同时避免不同序列化器对 null 处理的不一致性。
     */
    private static final String CACHE_NULL_VALUE = "@@NULL@@";

    public CacheAsideAspect(RedisService redisService, SpelUtil spelUtil, ObjectMapper objectMapper) {
        this.redisService = redisService;
        this.spelUtil = spelUtil;
        this.objectMapper = objectMapper;
    }

    /**
     * 核心旁路缓存生命周期拦截 (Cache-Aside Lifecycle)
     *
     * @param joinPoint  AOP 切入点（承载运行时参数矩阵与目标对象句柄）
     * @param redisCache 开发者在方法上定义的缓存策略契约
     * @return 最终投递给调用方的反序列化对象、源站结果或类型安全默认值
     */
    @SneakyThrows
    @Around("@annotation(redisCache)")
    public Object around(ProceedingJoinPoint joinPoint, RedisCache redisCache) {
        // ==========================================
        // Phase 1: 元数据提取与环境预检
        // ==========================================
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        // 【P0 级安全穿透】：锁定真实目标方法。
        // 防止在 CGLIB 增强场景下，因为方法参数名被擦除导致 SpEL 引擎（#id）解析出“变量未定义”异常。
        Method specificMethod = ClassUtils.getMostSpecificMethod(method, joinPoint.getTarget().getClass());

        // 架构边界确认：void 方法无数据产出，不具备缓存价值，直接快速放行 (Fast-Return)。
        if (specificMethod.getReturnType() == void.class) {
            return joinPoint.proceed();
        }

        // 1. 物理键名生成：将注解中的业务语义翻译为物理层的 Redis Key
        String key;
        try {
            key = generateKey(joinPoint, redisCache, specificMethod);
        } catch (Exception e) {
            // 容错降级：若 SpEL 语法错误，则记录审计日志并静默放行至数据库，拒绝因缓存插件异常导致主业务崩溃。
            log.warn("Key generation failed, skipping cache: {}", e.getMessage());
            return joinPoint.proceed();
        }

        // ==========================================
        // Phase 2: 分布式缓存探针探测 (Cache Probe)
        // ==========================================
        // 委托 RedisService 发起网络 I/O，此时受底层 RedisFaultToleranceAspect 物理熔断护盾监控。
        String json = redisService.get(key);

        // ==========================================
        // Phase 3: 缓存命中判定与多态反序列化状态机
        // ==========================================
        if (StrUtil.isNotBlank(json)) {

            // 1. 【核心防御】空标记拦截：识别到哨兵值，说明此资源已被标记为“确定性不存在”。
            if (CACHE_NULL_VALUE.equals(json)) {
                // 安全阻断：委派工具类基于返回类型生成安全零值，解决“基础类型自动拆箱 null”导致的 JVM 崩溃。
                return DefaultValueUtil.getPrimitiveDefaultValue(specificMethod.getReturnType());
            }

            // 2. 【性能直通车】：若返回声明本就是 String，跳过 Jackson 引擎解析，防止对 JSON 字符串进行二次多余转义。
            if (specificMethod.getReturnType() == String.class) {
                return json;
            }

            // 3. 【泛型感知反序列化】：处理 List<UserDTO> 等复杂嵌套类型。
            try {
                // 利用反射提取带有泛型签名的 Type，确保 Jackson 能精准还原集合内部的对象类型，而非退化为 Map。
                Type returnType = specificMethod.getGenericReturnType();
                JavaType javaType = objectMapper.getTypeFactory().constructType(returnType);

                // 委派重构后的 RedisService 进行智能化解析，保持 Starter 内部 I/O 标准统一。
                return redisService.get(key, javaType);
            } catch (Exception e) {
                // 【架构自愈机制】：检测到脏数据（由于实体类变更引发的解析异常）。
                // 此时主动执行“行政指令”——删除该 Key，并在本次请求中将其视作 Cache Miss，从而让系统自动刷新正确数据。
                log.error("Deserialize failed, self-cleaning key: {}", key);
                try {
                    redisService.delete(key);
                } catch (Exception ex) { /* ignore cleanup error */ }
            }
        }

        // ==========================================
        // Phase 4: 源站回源查询 (Back-to-Origin)
        // ==========================================
        // 缓存未命中、或自愈逻辑触发。此操作通常涉及昂贵的数据库 I/O。
        Object result = joinPoint.proceed();

        // ==========================================
        // Phase 5: 缓存回写与防穿透保护策略落盘
        // ==========================================
        try {
            if (result != null) {
                // 正常写回：将业务结果移交给 RedisService。
                // RedisService 内部会智能判断对象类型，并基于 ObjectMapper 自动执行序列化。
                redisService.set(key, result, redisCache.ttl(), redisCache.unit());
            } else {
                // 【防穿透落盘】：数据库确实无此数据。
                // 架构考量：采用“智能 TTL 衰减”策略。
                // 将防穿透空值的 TTL 缩短为业务配置的 1/5（最低 1 单位时间），在抵御攻击的同时，减少对 Redis 内存的无效占用。
                long nullTtl = Math.max(1, redisCache.ttl() / 5);
                redisService.set(key, CACHE_NULL_VALUE, nullTtl, redisCache.unit());
            }
        } catch (Exception e) {
            // 柔性降级：Redis 写入操作被视为“附加价值”。若回写失败，不应拦截业务正常返回的结果。
            log.warn("Redis write-back failed, continuing without cache: {}", e.getMessage());
        }

        return result;
    }

    /**
     * 高级逻辑键名解析引擎 (Key Computation Engine)
     * <p>
     * 架构契约：
     * 1. 强制前缀隔离：必须配置 {@code keyPrefix} 以防止微服务环境下的 Namespace 碰撞。
     * 2. 动态求值：支持 SpEL 表达式，允许按用户维度、参数属性生成细粒度缓存键。
     * 3. 兜底策略：若表达式缺失，则退化为基于方法首个参数的简单提取。
     */
    private String generateKey(ProceedingJoinPoint joinPoint, RedisCache redisCache, Method method) {
        String prefix = redisCache.keyPrefix();
        String spEL = redisCache.key();
        Object[] args = joinPoint.getArgs();

        // 1. 调用 AST 编译器进行动态上下文解析
        String id = spelUtil.parse(spEL, method, args, joinPoint.getTarget());

        // 2. 解析判决：如果有动态计算结果，则完成拼接
        if (id != null) {
            return prefix + id;
        }

        // 3. 【防御性兜底】：未配置表达式时，尝试将首个“简单值类型”参数（如 Long id）作为 Key 的后缀。
        if (args != null && args.length > 0 && args[0] != null && spelUtil.isSimpleType(args[0].getClass())) {
            return prefix + args[0];
        }

        // 极端场景（无参数且无表达式）：退化为静态前缀 Key（类级别共享缓存场景）。
        return prefix;
    }
}