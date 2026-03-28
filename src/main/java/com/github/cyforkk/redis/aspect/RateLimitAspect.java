package com.github.cyforkk.redis.aspect;

import cn.hutool.core.util.StrUtil;
import com.github.cyforkk.redis.annotation.RateLimit;
import com.github.cyforkk.redis.annotation.RateLimits;
import com.github.cyforkk.redis.exception.RateLimitException;
import com.github.cyforkk.redis.service.RedisService;
import com.github.cyforkk.redis.util.SpelUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.util.ClassUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;

/**
 * 核心高可用控制切面：分布式原子限流引擎 (Distributed Atomic Rate Limiting Engine)
 * <p>
 * 【架构语义】
 * 本切面是应对高并发洪峰与恶意刷量的“第一道物理防线”。
 * 通过 AOP 动态代理机制，在业务逻辑执行前构建流量漏斗，实现方法级别的泛用型与定制型流量整形（Traffic Shaping）。
 * <p>
 * 【四大核心架构机制】
 * 1. <b>Lua 原子屏障 (Lua Atomic Barrier)</b>：底层采用预编译的 Lua 脚本，将 Redis 的 INCR 与 EXPIRE 操作合并为单次网络请求，依靠 Redis 单线程模型，彻底杜绝极高并发下的“超流/漏拦”竞态漏洞。
 * 2. <b>多维漏斗防御 (Multi-Dimensional Funnel)</b>：基于 Spring 的 {@code AnnotatedElementUtils} 穿透提取 {@code @Repeatable} 注解，支持在同一接口上无缝叠加多维度（如防单 IP 爆破 + 防单用户高频）的限流网。
 * 3. <b>空间物理隔离 (Namespace Isolation)</b>：在 AOP 代理穿透的基础上，强制提取【类名.方法名】构建绝对隔离的限流桶坐标，根除了微服务体系下不同 Controller 同名方法（如 add/update）的交叉误伤灾难。
 * 4. <b>环境感知与安全边界 (Context-Aware Security)</b>：严格区分 Web/非 Web 执行上下文，拒绝手动解析非信源 HTTP Header，强制接管底层容器的真实 IP 投递，实现防伪造的 L4/L7 拦截。
 *
 * @Author cyforkk
 * @Create 2026/3/25 下午10:22
 * @Version 1.0
 */
@Aspect
@Slf4j
@Order(1) // 设置为最高优先级的业务切面，确保限流永远先于缓存和核心逻辑执行
public class RateLimitAspect {
    private final RedisService redisService;
    private final SpelUtil spelUtil;

    public RateLimitAspect(RedisService redisService, SpelUtil spelUtil) {
        this.redisService = redisService;
        this.spelUtil = spelUtil;
    }

    // ========================================================
    // 基础设施：Lua 预编译原子脚本 (保障 increment 和 expire 的绝对同步)
    // ========================================================
    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT;

    static {
        RATE_LIMIT_SCRIPT = new DefaultRedisScript<>();
        RATE_LIMIT_SCRIPT.setScriptText(
                "local count = redis.call('incr', KEYS[1]) \n" +
                        "if tonumber(count) == 1 then \n" +
                        "    redis.call('expire', KEYS[1], ARGV[1]) \n" +
                        "end \n" +
                        "return count"
        );
        RATE_LIMIT_SCRIPT.setResultType(Long.class);
    }

    /**
     * 核心拦截与限流校验引擎 (Rate Limiting Lifecycle)
     *
     * @param joinPoint AOP 切入点对象
     * @return 目标方法的执行结果
     * @throws Throwable 业务异常或限流中断异常 (RateLimitException)
     */
    @Around("@annotation(com.github.cyforkk.redis.annotation.RateLimit) || @annotation(com.github.cyforkk.redis.annotation.RateLimits)")
    public Object checkLimit(ProceedingJoinPoint joinPoint) throws Throwable {
        // ==========================================
        // Phase 1: 代理穿透与空间隔离坐标提取
        // ==========================================
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        // 穿透 Spring 代理层，拿到最底层的真实方法，防止 SpEL 解析不到真实的参数名
        Method specificMethod = ClassUtils.getMostSpecificMethod(method, joinPoint.getTarget().getClass());

        // 提取【类名.方法名】构建物理隔离命名空间，防止不同类的同名方法发生限流桶碰撞
        String className = specificMethod.getDeclaringClass().getSimpleName();
        String methodName = specificMethod.getName();
        String methodPath = className + "." + methodName;

        String redisKey;
        String basePrefix = "rate_limit:";

        // 提取当前方法上的所有限流契约（兼容单一和复合 @Repeatable 注解）
        Set<RateLimit> rateLimitSet = AnnotatedElementUtils.getMergedRepeatableAnnotations(specificMethod, RateLimit.class, RateLimits.class);

        // ==========================================
        // Phase 2: 遍历多维规则，执行漏斗过滤
        // ==========================================
        for (RateLimit rateLimit : rateLimitSet) {

            // ----------------------------------------------------
            // Step 1: 智能路由构建限流 Key
            // ----------------------------------------------------
            if (rateLimit.type() == RateLimit.LimitType.IP) {
                // 【IP 防刷模式】：获取客户端绝对真实 IP
                String ip = getIpAddress();
                redisKey = basePrefix + "ip:" + methodPath + ":" + ip;
            } else {
                // 【业务动态模式】：委派 SpEL 引擎解析业务参数
                String dynamicSuffix = generateKey(joinPoint, rateLimit, specificMethod);
                redisKey = basePrefix + "custom:" + methodPath + ":" + dynamicSuffix;
            }

            // ----------------------------------------------------
            // Step 2: 发送 Lua 脚本进行原子查账与计步
            // ----------------------------------------------------
            long time = rateLimit.time();
            int maxCount = rateLimit.maxCount();

            // 执行原子脚本：传入 Key (KEYS[1]) 和 过期时间 (ARGV[1])
            Long currentCount = redisService.execute(
                    RATE_LIMIT_SCRIPT,
                    Collections.singletonList(redisKey),
                    String.valueOf(time)
            );

            // ----------------------------------------------------
            // Step 3: 判决执行与快速失败 (Fail-Fast)
            // ----------------------------------------------------
            if (currentCount != null && currentCount > rateLimit.maxCount()) {
                log.warn("触发限流警告！拦截键: {}, 规则阈值: {}次/{}秒", redisKey, rateLimit.maxCount(), rateLimit.time());

                // 获取注解上定义的提示语
                String msg = rateLimit.message();
                // 如果是默认值且是 IP 限流，可以自动切换更专业的术语
                if ("请求过于频繁，请稍后再试".equals(msg) && rateLimit.type() == RateLimit.LimitType.IP) {
                    msg = "您的网络环境异常，请稍后再试";
                }

                throw new RateLimitException(msg);
            }
        }

        // ==========================================
        // Phase 3: 漏斗全量通过，物理放行
        // ==========================================
        return joinPoint.proceed();
    }

    /**
     * 高级键名生成路由 (SpEL Expression Engine Router)
     * <p>
     * 架构红线：
     * 1. 若选择 CUSTOM 模式且配置了 SpEL，严格执行 AST 动态解析。
     * 2. 若选择 CUSTOM 模式但未配置 SpEL，则视为【接口全局限流】（Global Rate Limit），
     * 严禁通过猜测参数位置来生成 Key，彻底杜绝参数顺序变更引发的全局限流误伤灾难。
     */
    private String generateKey(ProceedingJoinPoint joinPoint, RateLimit rateLimit, Method method) {
        String spEL = rateLimit.key();

        // 1. 【静态全局限流模式】：如果没写表达式，说明开发者的意图是限制这个接口的“总吞吐量”
        if (StrUtil.isBlank(spEL)) {
            return "global";
            // 最终生成的 Redis Key 类似于: rate_limit:custom:UserController.sendSms:global
        }

        Object[] args = joinPoint.getArgs();

        // 2. 【动态精准限流模式】：委派底层的 SpEL 抽象语法树进行变量提取
        // 依赖注入：SpelUtil 内部已做强校验，若解析为空（比如 #user.id 但 user 为 null），
        // 会直接抛出 IllegalArgumentException，阻断请求，强制开发者修复空指针。
        return spelUtil.parse(spEL, method, args, joinPoint.getTarget());
    }

    /**
     * 生产级获取客户端真实 IP 方法
     * <p>
     * 【安全边界声明】：
     * 作为底层架构 Starter，本方法严禁手动解析 {@code X-Forwarded-For} 等极易被伪造的 Header。
     * 强烈要求使用本 Starter 的业务系统，在面对反向代理（如 Nginx/网关）时，
     * 在其自身的 {@code application.yaml} 中配置 {@code server.forward-headers-strategy=framework}。
     * 此时底层的 Tomcat/Spring 会自动进行安全鉴权与过滤，并将真实的源 IP 注入到 {@code getRemoteAddr()} 中。
     *
     * @return 客户端真实 IP 地址
     * @throws IllegalStateException 当前执行非 Web 上下文时快速暴露错误
     */
    private static String getIpAddress() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            // 【架构自检】：防止在 MQ 消费、定时任务等非 Web 环境中使用 IP 限流导致全局误伤
            throw new IllegalStateException("当前不在 Web 请求上下文中，无法获取 IP 地址，请检查 @RateLimit 的 type 配置！");
        }
        HttpServletRequest request = attributes.getRequest();

        // 直接调用底层 API，把防止 IP 伪造的信任边界移交给 Web 容器
        String ip = request.getRemoteAddr();

        // 转换 IPv6 本地回环地址为 IPv4
        return "0:0:0:0:0:0:0:1".equals(ip) ? "127.0.0.1" : ip;
    }
}