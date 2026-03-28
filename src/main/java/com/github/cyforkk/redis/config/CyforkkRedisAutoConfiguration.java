package com.github.cyforkk.redis.config;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.cyforkk.redis.aspect.CacheAsideAspect;
import com.github.cyforkk.redis.aspect.RateLimitAspect;
import com.github.cyforkk.redis.aspect.RedisEvictAspect;
import com.github.cyforkk.redis.aspect.RedisFaultToleranceAspect;
import com.github.cyforkk.redis.service.RedisService;
import com.github.cyforkk.redis.util.SpelUtil;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 核心高可用控制中枢：分布式 Redis 基础基建自动装配引擎 (Infrastructure Auto-Configuration Engine)
 * <p>
 * 【架构语义】
 * 本类是整个 {@code cyforkk-redis-starter} 的 SPI (Service Provider Interface) 核心入口。
 * 遵循 Spring Boot 2.7+ / 3.x 的新一代自动装配规范，通过 {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports} 被容器主动发现与拉起。
 * 旨在为宿主业务系统一键注入高可用缓存、原子限流、物理熔断等微服务级别的防御基建。
 * <p>
 * 【四大架构契约】
 * 1. <b>环境安全嗅探 (Environment Sensing)</b>：依托 {@code @ConditionalOnClass}，仅在宿主环境包含 {@code StringRedisTemplate} 字节码时激活，绝不因为强行装配而导致无 Redis 依赖的纯净应用启动崩溃 (Fail-Safe)。
 * 2. <b>开闭原则契约 (Open-Closed Principle)</b>：所有组件强制声明 {@code @ConditionalOnMissingBean}。Starter 提供标准实现，但绝对尊重业务方的主权——允许业务系统通过同类型 Bean 进行无损替换与行为重载。
 * 3. <b>独立运行沙箱 (Standalone Sandbox)</b>：内置 {@link ObjectMapper} 兜底注册机制，确保在 MQ 消费节点、定时任务等非 Web 宿主环境中，依然具备独立、完整的反序列化自治能力。
 * 4. <b>命名空间隔离 (Namespace Isolation)</b>：强制采用 `cyforkk` 前缀注册 Bean ID，彻底杜绝与宿主系统（如业务线代码）发生 BeanDefinitionOverrideException 覆写碰撞。
 *
 * @Author cyforkk
 * @Create 2026/3/26
 * @Version 1.0
 */
@AutoConfiguration
@ConditionalOnClass(StringRedisTemplate.class)
// 【核心修复】：强制要求在 Spring 原生的 Redis 自动装配完成后，再加载本组件！
@AutoConfigureAfter(name = "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration")
public class CyforkkRedisAutoConfiguration {

    // ========================================================
    // Phase 1: 基础设施层 (Infrastructure Layer)
    // 强制隔离前缀：cyforkk
    // ========================================================

    /**
     * 【通用序列化引擎装配】
     * <p>
     * 架构考量：
     * 1. <b>防爆屏蔽</b>：配置 {@code FAIL_ON_UNKNOWN_PROPERTIES} 为 false，确保当宿主实体类字段变更（删除或更名）时，缓存中的旧数据依然能平滑反序列化，防止引发雪崩。
     * 2. <b>JSR310 兼容</b>：强制注册 {@link JavaTimeModule} 并关闭时间戳格式化，解决 Jackson 默认不认识 Java 8 {@code LocalDateTime} 的“全家桶级”大坑。
     * 3. <b>按需暴露</b>：利用 {@code @ConditionalOnMissingBean} 允许业务方提供更复杂的 ObjectMapper（如需要自定义序列化视图时）。
     */
    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper cyforkkObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        // 生产级宽容度设置
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        // 核心修复：LocalDateTime 序列化支持
        mapper.registerModule(new JavaTimeModule());
        // 体验优化：采用标准 ISO-8601 日期格式
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    /**
     * 【高性能 SpEL AST 解析引擎装配】
     * 架构考量：为 {@code @RateLimit} 和 {@code @RedisCache} 提供动态业务上下文感知能力。
     * 内置 L1 级编译缓存，确保高并发下表达式解析的极致吞吐量。
     */
    @Bean
    @ConditionalOnMissingBean
    public SpelUtil cyforkkSpelUtil(){
        return new SpelUtil();
    }

    // ========================================================
    // Phase 2: 核心门面层 (Facade Layer)
    // 强制隔离前缀：cyforkk
    // ========================================================

    /**
     * 【分布式 Redis 统一 I/O 网关装配】
     * <p>
     * 架构语义：
     * 1. <b>防腐层 (Anti-Corruption Layer)</b>：将宿主业务与原生 {@code StringRedisTemplate} 解耦，封装所有 I/O 异常至底层熔断切面可控范围内。
     * 2. <b>智能编解码</b>：注入 {@code cyforkkObjectMapper}，使门面具备自动处理 Object ⇔ JSON 转换的能力，降低业务代码编写复杂度。
     */
    @Bean
    @ConditionalOnMissingBean
    public RedisService cyforkkRedisService(StringRedisTemplate stringRedisTemplate, ObjectMapper cyforkkObjectMapper) {
        return new RedisService(stringRedisTemplate, cyforkkObjectMapper);
    }

    // ========================================================
    // Phase 3: AOP 护盾切面层 (Aspect Shield Layer)
    // 强制隔离前缀：cyforkk
    // ========================================================

    /**
     * 【底层物理故障熔断护盾装配】
     * <p>
     * 架构语义：
     * 1. <b>最高优先级防御</b>：标注为 {@code HIGHEST_PRECEDENCE}，作为直面 Redis 物理连接的最后一道防线，监控所有 {@code RedisService} 的网络调用。
     * 2. <b>爆炸半径控制</b>：精准识别网络异常与业务 Bug，通过自适应状态机实现 Fail-Open 柔性降级。
     */
    @Bean
    @ConditionalOnMissingBean
    public RedisFaultToleranceAspect cyforkkRedisFaultToleranceAspect(){
        return new RedisFaultToleranceAspect();
    }

    /**
     * 【业务级旁路缓存引擎装配】
     * 架构语义：实现了工业级的 Cache-Aside 状态机，内置缓存穿透标记、序列化自愈与智能 TTL 衰减逻辑。
     */
    @Bean
    @ConditionalOnMissingBean
    public CacheAsideAspect cyforkkCacheAsideAspect(RedisService cyforkkRedisService, SpelUtil cyforkkSpelUtil, ObjectMapper cyforkkObjectMapper) {
        return new CacheAsideAspect(cyforkkRedisService, cyforkkSpelUtil, cyforkkObjectMapper);
    }

    /**
     * 【分布式原子限流漏斗装配】
     * 架构语义：利用 Lua 脚本保障 INCR+EXPIRE 的绝对原子性，通过 SpEL 引擎实现多维度的精细化流量整形。
     */
    @Bean
    @ConditionalOnMissingBean
    public RateLimitAspect cyforkkRateLimitAspect(RedisService cyforkkRedisService, SpelUtil cyforkkSpelUtil) {
        return new RateLimitAspect(cyforkkRedisService, cyforkkSpelUtil);
    }

    /**
     * 【分布式缓存清理切面装配】
     * 架构语义：实现 Cache-Aside 模式的自动清理闭环
     */
    @Bean
    @ConditionalOnMissingBean
    public RedisEvictAspect cyforkkRedisEvictAspect(RedisService cyforkkRedisService, SpelUtil cyforkkSpelUtil) {
        return new RedisEvictAspect(cyforkkRedisService, cyforkkSpelUtil);
    }
}