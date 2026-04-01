package com.github.cyforkk.redis.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.github.cyforkk.redis.aspect.*;
import com.github.cyforkk.redis.core.CyforkkIdGenerator;
import com.github.cyforkk.redis.core.CyforkkLockClient;
import com.github.cyforkk.redis.service.RedisService;
import com.github.cyforkk.redis.util.SpelUtil;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

@AutoConfiguration
@ConditionalOnClass(StringRedisTemplate.class)
@AutoConfigureAfter(name = "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration")
@ConditionalOnProperty(prefix = "cyforkk.redis", name = "enabled", havingValue = "true", matchIfMissing = true)
// 【核心修复】：告诉 Spring，把这个属性类实例化成 Bean 并注入到容器中！
@EnableConfigurationProperties(CyforkkRedisProperties.class)
public class CyforkkRedisAutoConfiguration {

    // ========================================================
    // Phase 1: 基础设施层 (Infrastructure Layer)
    // ========================================================

    /**
     * 【核心修复 1】：删除 @Bean 和 @ConditionalOnMissingBean 注解！
     * 将其降级为 private 方法。绝不让 Spring MVC 拿到这个带 @class 的污染版 Mapper。
     */
    private ObjectMapper cyforkkPrivateObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();

        mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        mapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        PolymorphicTypeValidator ptv = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("java.")
                .allowIfSubType("com.")
                .allowIfSubType("org.")
                .allowIfSubType("cn.")
                .allowIfSubTypeIsArray()
                .build();

        mapper.activateDefaultTyping(ptv, ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);

        return mapper;
    }

    @Bean
    @ConditionalOnMissingBean
    public SpelUtil cyforkkSpelUtil(){
        return new SpelUtil();
    }

    // ========================================================
    // Phase 2: 核心门面层 (Facade Layer)
    // ========================================================

    @Bean
    @ConditionalOnMissingBean
    // 【核心修复 2】：去掉参数列表里的 ObjectMapper 自动注入
    public RedisService cyforkkRedisService(StringRedisTemplate stringRedisTemplate) {
        // 手动调用 private 方法获取定制 Mapper，将其封装在组件内部
        return new RedisService(stringRedisTemplate, cyforkkPrivateObjectMapper());
    }

    // ========================================================
    // Phase 3: AOP 护盾切面层 (Aspect Shield Layer)
    // ========================================================

    @Bean
    @ConditionalOnMissingBean
    public RedisFaultToleranceAspect cyforkkRedisFaultToleranceAspect(){
        return new RedisFaultToleranceAspect();
    }

    @Bean
    @ConditionalOnMissingBean
    // 【核心修复 3】：同样去掉参数列表里的 ObjectMapper，改为手动调用注入
    public CacheAsideAspect cyforkkCacheAsideAspect(RedisService cyforkkRedisService, SpelUtil cyforkkSpelUtil) {
        return new CacheAsideAspect(cyforkkRedisService, cyforkkSpelUtil, cyforkkPrivateObjectMapper());
    }

    @Bean
    @ConditionalOnMissingBean
    public RateLimitAspect cyforkkRateLimitAspect(RedisService cyforkkRedisService, SpelUtil cyforkkSpelUtil) {
        return new RateLimitAspect(cyforkkRedisService, cyforkkSpelUtil);
    }

    @Bean
    @ConditionalOnMissingBean
    public RedisEvictAspect cyforkkRedisEvictAspect(RedisService cyforkkRedisService, SpelUtil cyforkkSpelUtil) {
        return new RedisEvictAspect(cyforkkRedisService, cyforkkSpelUtil);
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotentAspect cyforkkIdempotentAspect(RedisService cyforkkRedisService, SpelUtil cyforkkSpelUtil) {
        return new IdempotentAspect(cyforkkRedisService, cyforkkSpelUtil);
    }

    // 在你的 CyforkkRedisAutoConfiguration 类里，加上这个 Bean
    @Bean
    @ConditionalOnMissingBean
    public CyforkkFallbackExceptionHandler cyforkkFallbackExceptionHandler() {
        return new CyforkkFallbackExceptionHandler();
    }

    // ========================================================
    // Phase 5: 基础组件 (Infra Tools)
    // ========================================================
    @Bean
    @ConditionalOnMissingBean
    // 注入配置类 CyforkkRedisProperties
    public CyforkkIdGenerator cyforkkIdGenerator(StringRedisTemplate stringRedisTemplate,
                                                 CyforkkRedisProperties properties) {
        // 从配置中动态读取起点时间戳！
        long epochStart = properties.getIdGenerator().getEpochStart();
        return new CyforkkIdGenerator(stringRedisTemplate, epochStart);
    }

    @Bean
    @ConditionalOnMissingBean
    // 【新增】：将锁工厂注册为 Spring Bean
    public CyforkkLockClient cyforkkLockClient(StringRedisTemplate stringRedisTemplate) {
        return new CyforkkLockClient(stringRedisTemplate);
    }
}