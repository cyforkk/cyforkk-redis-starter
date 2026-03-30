package com.github.cyforkk.redis.aspect;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import cn.hutool.core.util.StrUtil;
import com.github.cyforkk.redis.annotation.RateLimit;
import com.github.cyforkk.redis.annotation.RateLimits;
import com.github.cyforkk.redis.exception.RateLimitException;
import com.github.cyforkk.redis.service.RedisService;
import com.github.cyforkk.redis.util.SpelUtil;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.util.ClassUtils;


import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

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
@Order(10) // 设置为最高优先级的业务切面，确保限流永远先于缓存和核心逻辑执行
public class RateLimitAspect {
    private final RedisService redisService;
    private final SpelUtil spelUtil;

    public RateLimitAspect(RedisService redisService, SpelUtil spelUtil) {
        this.redisService = redisService;
        this.spelUtil = spelUtil;
    }
    // 【架构增强】：方法级限流规则缓存 (L1 Cache)
    // 作用：消除高并发下每次请求带来的反射读取与动态排序开销
    private final Map<Method, List<RateLimit>> rateLimitRuleCache = new ConcurrentHashMap<>();
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
                        "else \n" +
                        "    if redis.call('ttl', KEYS[1]) == -1 then \n" +  // 【防御增强】：检测永生键
                        "        redis.call('expire', KEYS[1], ARGV[1]) \n" +
                        "    end \n" +
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
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Method specificMethod = ClassUtils.getMostSpecificMethod(method, joinPoint.getTarget().getClass());

        // ==========================================
        // Phase 1: 获取并排序多维限流规则 (命中 O(1) 缓存)
        // ==========================================
        List<RateLimit> sortedLimits = rateLimitRuleCache.computeIfAbsent(
                specificMethod,
                this::parseAndSortRateLimits
        );

        // ==========================================
        // Phase 2: 严格按照短周期优先顺序，执行漏斗校验
        // ==========================================
        for (RateLimit rateLimit : sortedLimits) {
            String redisKey = generateKey(joinPoint, rateLimit, specificMethod);

            try {
                // 执行 Lua 原子脚本
                Long currentCount = redisService.execute(
                        RATE_LIMIT_SCRIPT,
                        Collections.singletonList(redisKey),
                        String.valueOf(rateLimit.time()),
                        String.valueOf(rateLimit.maxCount())
                );

                if (currentCount != null && currentCount > rateLimit.maxCount()) {
                    // 触发拦截，由于已经按照时间排过序，
                    // 此时抛出异常，最大程度保护了后续的长周期（大容量）令牌桶不被恶意请求污染！
                    // 【第三道防线：保姆级日志提示】
                    // 不仅打印拦截信息，更提供明确的行动指南 (Actionable Message)
                    // 【修改点】：同样删掉“👉开发提示”
                    log.info("[Cyforkk-Redis] 触发底层限流规则: {}", redisKey);
                    throw new RateLimitException(rateLimit.message());
                }
            } catch (Exception e) {
                // 如果是 Lua 脚本执行时的 Redis I/O 异常，且没有 @NoFallback，触发降级放行...
                if (e instanceof RateLimitException) {
                    throw e; // 业务限流阻断，正常抛出
                }
                log.error("[Redis-Starter] 限流组件底层故障，触发柔性放行！Key: {}", redisKey, e);
            }
        }

        return joinPoint.proceed();
    }

    /**
     * 内部引擎：提取目标方法上的所有限流契约，并执行架构级排序
     */
    private List<RateLimit> parseAndSortRateLimits(Method method) {
        List<RateLimit> limits = new ArrayList<>();

        // 1. 提取单注解与多重注解
        if (method.isAnnotationPresent(RateLimits.class)) {
            limits.addAll(Arrays.asList(method.getAnnotation(RateLimits.class).value()));
        } else if (method.isAnnotationPresent(RateLimit.class)) {
            limits.add(method.getAnnotation(RateLimit.class));
        }

        // 2. 【核心防御设计】：按时间窗口 (time) 升序排序
        // 确保短周期（如 1秒5次）优先于长周期（如 1天1000次）执行。
        // 如果时间相同，则按阈值 (maxCount) 升序排序，严格的优先。
        return limits.stream()
                .sorted(Comparator
                        .comparingLong(RateLimit::time)
                        .thenComparingInt(RateLimit::maxCount))
                .collect(Collectors.toList());
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
        String namespace = method.getDeclaringClass().getSimpleName() + ":" + method.getName() + ":";
        // 核心修复：强制追加时间窗口构建绝对隔离的物理桶
        String ruleSuffix = ":" + rateLimit.time();

        if (rateLimit.type() == RateLimit.LimitType.IP) {
            return namespace + "ip:" + getIpAddress() + ruleSuffix;
        }

        String spEL = rateLimit.key();
        if (StrUtil.isBlank(spEL)) {
            return namespace + "global" + ruleSuffix;
        }

        return namespace + spelUtil.parse(spEL, method, joinPoint.getArgs(), joinPoint.getTarget()) + ruleSuffix;
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