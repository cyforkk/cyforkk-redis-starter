package com.github.cyforkk.redis.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 核心高可用控制异常：分布式限流阻断专属异常 (Distributed Rate Limit Blocking Exception)
 * <p>
 * 【架构语义】
 * 本异常是底层的 {@code RateLimitAspect} 限流引擎检测到流量超标时，发出的标准中断信号。
 * 继承自非受检异常 {@link RuntimeException}，旨在利用 Java 的异常冒泡机制，瞬间切断当前线程的 Controller 执行链路，
 * 拒绝执行后续的耗时业务与数据库 I/O，实现流量洪峰下的快速失败 (Fail-Fast)。
 * <p>
 * 【业务集成契约 (Integration Contract)】
 * 遵循“底层只负责阻断，上层负责表现”的架构解耦原则，本 Starter 不会强制修改 HTTP 响应体。
 * <b>最佳实践：</b>强烈要求宿主系统（如你的 CityPulse 社区平台）在全局异常处理器（{@code @RestControllerAdvice}）中显式拦截本异常，
 * 将其转化为友好的 JSON 响应或标准的 HTTP 429 (Too Many Requests) 状态码：
 * <pre>
 * {@code @ExceptionHandler(RateLimitException.class)}
 * {@code public Result<String> handleRateLimitException(RateLimitException e) \{}
 * {@code     log.warn("触发业务限流: {}", e.getMessage());}
 * {@code     return Result.error(429, e.getMessage()); // e.getMessage() 通常为："操作太快啦，请稍微休息一下！"}
 * {@code \}}
 * </pre>
 *
 * @Author cyforkk
 * @Create 2026/3/25 下午9:22
 * @Version 1.0
 */
@ResponseStatus(code = HttpStatus.TOO_MANY_REQUESTS)
public class RateLimitException extends RuntimeException {

    /**
     * 构造限流中断异常
     *
     * @param message 传递给全局异常处理器的告警文案（将在前端直接展示给用户）
     */
    public RateLimitException(String message) {
        super(message);
    }
}