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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.lang.reflect.Method;

/**
 * 分布式缓存清理切面 (基于 Cache-Aside 模式)
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
     * 【核心修复 1】：绑定 returning 属性，拦截目标方法的返回值，赋值给 result 参数
     */
    @AfterReturning(pointcut = "@annotation(redisEvict)", returning = "result")
    public void evictCache(JoinPoint joinPoint, RedisEvict redisEvict, Object result) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Method specificMethod = ClassUtils.getMostSpecificMethod(method, joinPoint.getTarget().getClass());

        String key;
        try {
            // 【核心修复 2】：将抓取到的 result 透传给底层 Key 生成器
            key = generateKey(joinPoint, redisEvict, specificMethod, result);
        } catch (Exception e) {
            log.error("[Redis-Starter] 缓存清理失败：Key 动态解析异常. Method: {}, Error: {}",
                    specificMethod.getName(), e.getMessage());
            return;
        }

        // 阶段二：执行物理删除 (高危 I/O 操作隔离)
        try {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            Boolean deleted = redisService.delete(key);
                            log.info("[Redis-Starter] (事务提交后) 缓存清理完成. Key: {}, 命中状态: {}", key, deleted);
                        } catch (Exception e) {
                            log.error("[Redis-Starter] (事务提交后) 缓存清理发生网络异常，可能产生脏数据！Key: {}", key, e);
                        }
                    }
                });
                log.info("[Redis-Starter] 探测到事务环境，已将缓存清理任务挂载至事务 Commit 之后执行. Key: {}", key);
            } else {
                Boolean deleted = redisService.delete(key);
                log.info("[Redis-Starter] 缓存清理完成. Key: {}, 命中状态: {}", key, deleted);
            }
        } catch (Exception e) {
            log.error("[Redis-Starter] 缓存清理注册失败：可能产生脏数据！Key: {}, Error: {}", key, e.getMessage());
        }
    }

    /**
     * 物理键名生成路由
     * 【核心修复 3】：方法签名增加 Object result 参数
     */
    private String generateKey(JoinPoint joinPoint, RedisEvict redisEvict, Method method, Object result) {
        String prefix = redisEvict.keyPrefix();
        String spEL = redisEvict.key();
        Object[] args = joinPoint.getArgs();

        if (spEL == null || spEL.trim().isEmpty()) {
            log.warn("[Redis-Starter] @RedisEvict 缺少 key 表达式，将退化为静态全局 Key: {}", prefix);
            return prefix;
        }

        // 【核心修复 4】：将 result 最终递交给 SpEL 解析引擎
        String id = spelUtil.parse(spEL, method, args, joinPoint.getTarget(), result);

        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("SpEL 解析结果为空，拒绝生成危险的 Cache Key");
        }

        return prefix + id;
    }
}