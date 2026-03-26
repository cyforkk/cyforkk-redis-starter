package com.github.cyforkk.redis.config;

import com.github.cyforkk.redis.aspect.CacheAsideAspect;
import com.github.cyforkk.redis.aspect.RateLimitAspect;
import com.github.cyforkk.redis.aspect.RedisFaultToleranceAspect;
import com.github.cyforkk.redis.service.RedisService;
import com.github.cyforkk.redis.util.SpelUtil;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 核心高可用控制中枢：分布式 Redis 基础基建自动装配引擎 (Infrastructure Auto-Configuration Engine)
 * <p>
 * 【架构语义】
 * 本类是整个 {@code cyforkk-redis-starter} 的 SPI (Service Provider Interface) 核心入口。
 * 遵循 Spring Boot 2.7+ 的新一代自动装配规范，通过 {@code META-INF/spring/...imports} 被容器主动发现与拉起。
 * 旨在为宿主业务系统一键注入高可用缓存、原子限流、物理熔断等微服务级别的防御基建。
 * <p>
 * 【四大架构契约】
 * 1. <b>环境安全嗅探 (Environment Sensing)</b>：依托 {@code @ConditionalOnClass}，仅在宿主环境包含 {@code StringRedisTemplate} 字节码时激活，绝不因为强行装配而导致无 Redis 依赖的纯净应用启动崩溃 (Fail-Safe)。
 * 2. <b>开闭原则契约 (Open-Closed Principle)</b>：所有组件强制声明 {@code @ConditionalOnMissingBean}。Starter 提供标准实现，但绝对尊重业务方的主权——允许业务系统通过同类型 Bean 进行无损替换与行为重载。
 * 3. <b>独立运行沙箱 (Standalone Sandbox)</b>：内置 {@link ObjectMapper} 兜底注册机制，确保在 MQ 消费节点、定时任务等非 Web 宿主环境中，依然具备独立、完整的反序列化自治能力。
 * 4. <b>命名空间隔离 (Namespace Isolation)</b>：强制采用 `cyforkk` 前缀注册 Bean ID，彻底杜绝与宿主系统（如黑马点评业务线）发生 BeanDefinitionOverrideException 覆写碰撞。
 *
 * @Author cyforkk
 * @Create 2026/3/26
 * @Version 1.0
 */
@AutoConfiguration
@ConditionalOnClass(StringRedisTemplate.class)
public class CyforkkRedisAutoConfiguration {

    // ========================================================
    // Phase 1: 基础设施层 (Infrastructure Layer)
    // 强制隔离前缀：cyforkk
    // ========================================================

    /**
     * 【序列化引擎装配】
     * 架构考量：为防止宿主系统缺失 Web 环境 (无默认 ObjectMapper)，或宿主序列化策略过于激进，
     * 此处提供组件专属的宽容型 JSON 解析引擎兜底，确保向下兼容与脏数据容忍。
     */
    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper cyforkkObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        // 生产级防爆垒：实体类字段被删除或变更时，反序列化主动忽略未知属性，防止引发 JsonMappingException 雪崩
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

    /**
     * 【AST 语法树解析引擎装配】
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
     * 【Redis 底层通信门面装配】
     * 架构考量：将 Spring 官方的 StringRedisTemplate 封装为受控门面。
     * 若业务线有定制化的多数据源 Redis 路由需求，只需在业务侧 @Bean 注入一个子类，即可无缝接管本组件的所有底层 I/O。
     */
    @Bean
    @ConditionalOnMissingBean
    public RedisService cyforkkRedisService(StringRedisTemplate stringRedisTemplate){
        return new RedisService(stringRedisTemplate);
    }

    // ========================================================
    // Phase 3: AOP 护盾切面层 (Aspect Shield Layer)
    // 强制隔离前缀：cyforkk
    // ========================================================

    /**
     * 【熔断降级物理护盾装配】
     * 注入时机：作为最高优先级的环绕切面，监控一切经由 RedisService 发出的网络 I/O。
     */
    @Bean
    @ConditionalOnMissingBean
    public RedisFaultToleranceAspect cyforkkRedisFaultToleranceAspect(){
        return new RedisFaultToleranceAspect();
    }

    /**
     * 【旁路缓存状态机装配】
     */
    @Bean
    @ConditionalOnMissingBean
    public CacheAsideAspect cyforkkCacheAsideAspect(RedisService cyforkkRedisService, SpelUtil cyforkkSpelUtil, ObjectMapper cyforkkObjectMapper) {
        return new CacheAsideAspect(cyforkkRedisService, cyforkkSpelUtil, cyforkkObjectMapper);
    }

    /**
     * 【原子限流漏斗装配】
     */
    @Bean
    @ConditionalOnMissingBean
    public RateLimitAspect cyforkkRateLimitAspect(RedisService cyforkkRedisService, SpelUtil cyforkkSpelUtil) {
        return new RateLimitAspect(cyforkkRedisService, cyforkkSpelUtil);
    }
}