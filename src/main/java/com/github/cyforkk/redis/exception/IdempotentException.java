package com.github.cyforkk.redis.exception;

/**
 * 核心高可用控制异常：分布式接口幂等性/防抖拦截异常
 * <p>
 * 【架构语义】
 * 当底层的 IdempotentAspect 探测到用户在极短时间内对同一接口发起重复请求时，
 * 将抛出此异常，实现快速失败 (Fail-Fast)，阻断核心业务的重复执行。
 */
public class IdempotentException extends RuntimeException {
    public IdempotentException(String message) {
        super(message);
    }
}