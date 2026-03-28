package com.github.cyforkk.redis.aspect;

import com.github.cyforkk.redis.annotation.RedisEvict;
import com.github.cyforkk.redis.service.RedisService;
import com.github.cyforkk.redis.util.SpelUtil;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;

/**
 * 分布式缓存清理切面 (基于 Cache-Aside 模式)
 * <p>
 * 架构约束：
 * 1. 触发时机：严格绑定在目标方法成功返回后（@AfterReturning）。若主方法抛出异常（如 DB 更新失败），切面不执行，保障强一致性。
 * 2. 隔离原则：缓存层的任何网络、解析、执行异常，必须在此切面内被吞咽（Swallow），绝对不允许向上层抛出而污染主业务线的正常返回。
 *
 * @Author cyforkk
 * @Version 2.0 (Architectural Refactored)
 */
@Aspect
@Slf4j
public class RedisEvictAspect {

    private final RedisService redisService;
    private final SpelUtil spelUtil;

    public RedisEvictAspect(RedisService redisService, SpelUtil spelUtil) {
        this.redisService = redisService;
        this.spelUtil = spelUtil;
    }

    /**
     * 执行缓存清理后置动作
     * * @param joinPoint  AOP 连接点 (此处绝对不能使用 ProceedingJoinPoint)
     * @param redisEvict 拦截到的缓存清除注解
     */
    @AfterReturning(pointcut = "@annotation(redisEvict)")
    public void evictCache(JoinPoint joinPoint, RedisEvict redisEvict) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        // 获取实际执行类的具体方法（防御代理类或接口继承导致的反射失效）
        Method specificMethod = ClassUtils.getMostSpecificMethod(method, joinPoint.getTarget().getClass());

        String key;
        try {
            // 阶段一：AST 语法树解析与动态 Key 生成
            key = generateKey(joinPoint, redisEvict, specificMethod);
        } catch (Exception e) {
            // 【架构级容错】：SpEL 解析可能因参数上下文缺失而抛出异常。
            // 此时主业务(DB操作)已成功，必须记录告警并立即终止切面，绝不可抛出异常导致外层 HTTP 请求失败。
            log.error("[Redis-Starter] 缓存清理失败：Key 动态解析异常. Method: {}, Error: {}",
                    specificMethod.getName(), e.getMessage());
            return;
        }

        // 阶段二：执行物理删除 (高危 I/O 操作隔离)
        try {
            Boolean deleted = redisService.delete(key);
            log.info("[Redis-Starter] 缓存清理完成. Key: {}, 命中状态: {}", key, deleted);
        } catch (Exception e) {
            // 【架构级容错】：网络抖动或 Redis 宕机会导致此步骤崩溃。
            // 必须隔离异常！此处记录 ERROR 日志。在更高等级的金融架构中，此处应将 Key 压入 MQ 或本地延迟队列进行补偿重试。
            log.error("[Redis-Starter] 缓存清理失败：Redis I/O 异常. 警告：可能产生脏数据！Key: {}, Error: {}",
                    key, e.getMessage());
        }
    }

    /**
     * 物理键名生成路由
     * 架构约束：拒绝不确定的魔法猜测，解析失败应当暴露给上层 catch，而不是错误地删除其他 Key。
     */
    private String generateKey(JoinPoint joinPoint, RedisEvict redisEvict, Method method) {
        String prefix = redisEvict.keyPrefix();
        String spEL = redisEvict.key();
        Object[] args = joinPoint.getArgs();

        // 1. 如果未配置 SpEL 表达式，直接抛出异常（或者视业务规则只返回 Prefix 作为全局共享 Key）
        if (spEL == null || spEL.trim().isEmpty()) {
            log.warn("[Redis-Starter] @RedisEvict 缺少 key 表达式，将退化为静态全局 Key: {}", prefix);
            return prefix;
        }

        // 2. 严格调用 SpEL 引擎解析
        String id = spelUtil.parse(spEL, method, args, joinPoint.getTarget());

        if (id == null || id.trim().isEmpty()) {
            // 拒绝执行危险的“默认取第一个参数”的猜测逻辑，直接抛出异常交由外层容错处理
            throw new IllegalArgumentException("SpEL 解析结果为空，拒绝生成危险的 Cache Key");
        }

        return prefix + id;
    }
}