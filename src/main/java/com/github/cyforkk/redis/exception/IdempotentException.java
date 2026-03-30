package com.github.cyforkk.redis.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 核心高可用控制异常：分布式接口幂等性/防抖拦截异常
 * <p>
 * 【架构语义】
 * 当底层的 IdempotentAspect 探测到用户在极短时间内对同一接口发起重复请求时，
 * 将抛出此异常，实现快速失败 (Fail-Fast)，阻断核心业务的重复执行。
 */
// 当这个异常抛出且没有被拦截时，Spring 自动返回 429 (Too Many Requests) 或 409 (Conflict)
@ResponseStatus(code = HttpStatus.TOO_MANY_REQUESTS)
public class IdempotentException extends RuntimeException {
    public IdempotentException(String message) {
        super(message);
    }
}