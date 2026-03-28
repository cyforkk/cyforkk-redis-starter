package com.github.cyforkk.redis.annotation;

import java.lang.annotation.*;

/**
 * 缓存清除注解
 * 配合 AOP 使用，当目标方法执行成功后，自动删除 Redis 中的对应 Key
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RedisEvict {

    /**
     * 缓存 Key 的前缀
     */
    String keyPrefix() default "";

    /**
     * 缓存 Key 的动态部分 (支持 SpEL 表达式，如 "#shop.id")
     */
    String key() default "";
}