package com.github.cyforkk.redis.service; // 【P0级修复】：包名必须是 service！

import com.github.cyforkk.redis.annotation.NoFallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 核心高可用控制门面：分布式 Redis 统一 I/O 网关 (Distributed Redis I/O Facade)
 * <p>
 * 【架构语义】
 * 本类是对 Spring 原生 {@link StringRedisTemplate} 的极致收敛与门面封装（Facade Pattern）。
 * 它是整个 cyforkk-redis-starter 与物理 Redis 集群通信的【唯一出入口】。
 * 所有的底层网络 I/O 均由此类发出，从而确保底层护盾 {@code RedisFaultToleranceAspect} 能够对其进行 100% 的 AOP 拦截与熔断保护。
 * <p>
 * 【核心契约】
 * 1. <b>防腐层设计 (Anti-Corruption Layer)</b>：隔离了业务侧与 Spring Data Redis 的直接耦合。未来若替换底层驱动（如 Lettuce 切换为 Redisson），宿主业务代码可实现零修改。
 * 2. <b>柔性降级感知 (Fallback-Aware)</b>：默认情况下，所有读写操作均受底层熔断器保护（Fail-Open）。在 Redis 宕机时，方法将静默返回 null/false，调用方需具备空值容错能力。
 * 3. <b>强一致性逃生舱 (Strict Escape Hatch)</b>：提供 {@link #getStrict(String)} 方法，通过 {@link NoFallback} 注解强行打穿熔断器，为核心资金/安全链路提供“快速失败（Fail-Closed）”保障。
 *
 * @Author cyforkk
 * @Create 2026/3/24 下午9:55
 * @Version 1.0
 */
public class RedisService {

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 架构级构造器注入
     * 由外部的 {@code CyforkkRedisAutoConfiguration} 在 SPI 装配时动态推入实例，
     * 允许业务方在极端场景下覆盖或代理此底层 Template。
     *
     * @param stringRedisTemplate Spring Data Redis 核心通信组件
     */
    public RedisService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    // ========================================================
    // 1. 基础 KV 存储管道 (KV Storage Pipeline)
    // ========================================================

    /**
     * 设置基础 String 值，永不过期。
     * <p>
     * <b>【架构警告】</b>：极其危险的操作 (OOM 隐患)。仅建议用于全局配置字典等静态元数据。
     * 业务数据请严格遵循 TTL 规范，使用 {@link #set(String, String, long)}。
     *
     * @param key   物理键
     * @param value 序列化后的 JSON 字符串或文本
     */
    public void set(String key, String value){
        stringRedisTemplate.opsForValue().set(key, value);
    }

    /**
     * 设置基础 String 值，并绑定固定过期时间 (秒级精度)。
     *
     * @param key   物理键
     * @param value 序列化数据
     * @param time  存活时间（单位：秒）。若 <= 0 则退化为永不过期。
     */
    public void set(String key, String value, long time){
        if(time > 0){
            stringRedisTemplate.opsForValue().set(key, value, time, TimeUnit.SECONDS);
        } else {
            set(key, value); // Fallback to non-expiring
        }
    }

    /**
     * 设置基础 String 值，支持高精度时间单位。
     *
     * @param key      物理键
     * @param value    序列化数据
     * @param time     存活时长
     * @param timeUnit 时间单位（如 TimeUnit.MILLISECONDS）
     */
    public void set(String key, String value, long time, TimeUnit timeUnit){
        if(time > 0){
            stringRedisTemplate.opsForValue().set(key, value, time, timeUnit);
        } else {
            set(key, value); // Fallback to non-expiring
        }
    }

    /**
     * 【柔性读接口】常规提取 String 值。
     * <p>
     * <b>熔断契约：</b> 遇到 Redis 集群宕机或网络超时时，将被底层 {@code RedisFaultToleranceAspect} 拦截，
     * 并静默返回 {@code null} (Fail-Open 策略)，保障主业务链路畅通。
     *
     * @param key 物理键
     * @return 查得的字符串，宕机或缓存穿透时返回 null
     */
    public String get(String key){
        return stringRedisTemplate.opsForValue().get(key);
    }

    /**
     * 【刚性读接口】强一致性提取 String 值（核心链路专用）。
     * <p>
     * <b>阻断契约：</b> 方法签名已标注 {@link NoFallback}。
     * 遇到 Redis 集群宕机时，底层切面将拒绝返回 null 进行兜底降级，而是直接向上抛出 {@code RuntimeException}
     * (Fail-Closed 策略)，强制中断当前请求。
     * <p>
     * 适用场景：图形验证码核验、支付防重 Token 校验等绝不允许“假装验证通过”的业务。
     *
     * @param key 物理键
     * @return 查得的字符串
     */
    @NoFallback
    public String getStrict(String key){
        return stringRedisTemplate.opsForValue().get(key);
    }

    /**
     * 主动驱逐/失效指定的缓存数据。
     *
     * @param key 物理键
     * @return true=删除成功，false=键不存在
     */
    public Boolean delete(String key){
        return stringRedisTemplate.delete(key);
    }

    // ========================================================
    // 2. 内存生命周期管控 (Memory Lifecycle Management)
    // ========================================================

    /**
     * 探针：获取指定 Key 的剩余存活时间。
     *
     * @param key 物理键
     * @return 剩余时间 (单位：秒)。若 Key 不存在或未设置 TTL，视 Redis 版本返回 -1 或 -2。
     */
    public Long getExpire(String key){
        return stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
    }

    /**
     * 续期：为已存在的活动 Key 动态延长生命周期。
     * <p>
     * 适用场景：Session 触碰续期（Touch）、分布式锁的 WatchDog (看门狗) 延期机制。
     *
     * @param key      物理键
     * @param time     续期时长
     * @param timeUnit 时间单位
     * @return true=续期成功，false=键已失效或不存在
     */
    public Boolean expire(String key, long time, TimeUnit timeUnit){
        return stringRedisTemplate.expire(key, time, timeUnit);
    }

    // ========================================================
    // 3. 高级并发与分布式原语 (Advanced Concurrency Primitives)
    // ========================================================

    /**
     * 【分布式锁原语】：SETNX (Set If Not Exists) 增强版。
     * <p>
     * 核心用途：保障微服务架构下的绝对互斥操作，防止高并发导致的重复写、重复提交。
     * 此操作利用 Redis 底层机制，将“判空”与“写入+TTL”合并为单条网络命令，具备绝对的原子性。
     *
     * @param key      物理锁标志
     * @param value    锁的持有人标志（如 UUID 或 ThreadID），用于安全释放锁
     * @param time     锁的最大持有时间 (防死锁超时保护)
     * @param timeUnit 时间单位
     * @return true=加锁成功 (夺得互斥量), false=加锁失败 (资源已被抢占)
     */
    public Boolean setIfAbsent(String key, String value, long time, TimeUnit timeUnit){
        return stringRedisTemplate.opsForValue().setIfAbsent(key, value, time, timeUnit);
    }

    /**
     * 【分布式事务原语】：委派执行 Lua 原子脚本。
     * <p>
     * 核心用途：当业务需要连续执行多条指令且绝不允许被打断（如：限流器中的查账 + INCR + EXPIRE，或秒杀扣库存），
     * 将脚本推至 Redis 节点服务端执行。这不仅保障了并发安全性（利用 Redis 单线程执行机制），
     * 更大幅节约了多次指令交互造成的网络 RTT (Round Trip Time) 损耗。
     *
     * @param script 预编译的 Lua 脚本对象
     * @param keys   动态推入脚本的 KEYS 集合
     * @param args   动态推入脚本的 ARGV 参数集
     * @param <T>    脚本预期的返回类型泛型
     * @return Lua 脚本的最终计算结果
     */
    @NoFallback
    public <T> T execute(RedisScript<T> script, List<String> keys, Object... args){
        return stringRedisTemplate.execute(script, keys, args);
    }
}