package com.github.cyforkk.redis.config;

import com.github.cyforkk.redis.exception.IdempotentException;
import com.github.cyforkk.redis.exception.RateLimitException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
// 【核心修改】：比最低优先级高 100。
// 这样可以确保它在宿主系统通用的 Exception.class 拦截前执行，
// 但又不会抢占宿主系统自己写的专门的 IdempotentException 拦截！
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class CyforkkFallbackExceptionHandler {

    @ExceptionHandler(RateLimitException.class)
    public ResponseEntity<Map<String, Object>> handleRateLimitException(RateLimitException e) {
        // 【核心魔法】：在这里打印开发提示！
        log.warn("⛔ [Cyforkk-Starter] 触发限流防御！\n" +
                "👉 【开发指南】：当前日志由 Starter 兜底打印。若需自定义返回格式及消除此警告，请在您的 @RestControllerAdvice 全局异常处理器中捕获 [com.github.cyforkk.redis.exception.RateLimitException]！");
        return buildResponse(e.getMessage());
    }

    @ExceptionHandler(IdempotentException.class)
    public ResponseEntity<Map<String, Object>> handleIdempotentException(IdempotentException e) {
        // 【核心魔法】：在这里打印开发提示！
        log.warn("⛔ [Cyforkk-Starter] 触发防抖防御！\n" +
                "👉 【开发指南】：当前日志由 Starter 兜底打印。若需自定义返回格式及消除此警告，请在您的 @RestControllerAdvice 全局异常处理器中捕获 [com.github.cyforkk.redis.exception.IdempotentException]！");
        return buildResponse(e.getMessage());
    }

    private ResponseEntity<Map<String, Object>> buildResponse(String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", HttpStatus.TOO_MANY_REQUESTS.value());
        result.put("message", message);
        result.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(result);
    }
}