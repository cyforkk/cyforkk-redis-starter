package com.github.cyforkk.redis.service;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.cyforkk.redis.annotation.NoFallback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 核心高可用控制门面：分布式 Redis 统一 I/O 网关 (Distributed Redis I/O Facade)
 * <p>
 * 【架构语义】
 * 本类是对 Spring 原生 {@link StringRedisTemplate} 的极致收敛与门面封装（Facade Pattern）。
 * 它是整个 {@code cyforkk-redis-starter} 与物理 Redis 集群通信的【唯一出入口】。
 * 所有的底层网络 I/O 均由此类发出，从而确保底层护盾 {@code RedisFaultToleranceAspect} 能够对其进行 100% 的 AOP 拦截与熔断保护。
 * <p>
 * 【四大核心契约】
 * 1. <b>防腐层设计 (Anti-Corruption Layer)</b>：隔离业务侧与底层驱动。未来若替换驱动（如 Lettuce 切换为 Redisson），业务代码可实现零修改。
 * 2. <b>语义一致性封装</b>：强制将 Java 内存态对象与 Redis 持久态字节流的转换收拢于此，解决 JDK 默认序列化导致的“乱码”与“空间膨胀”问题。
 * 3. <b>柔性可用与刚性阻断</b>：默认 Fail-Open（柔性降级），通过 {@link NoFallback} 显式声明核心链路的 Fail-Closed（快速失败）策略。
 * 4. <b>泛型擦除防御</b>：内置 Jackson {@link JavaType} 嗅探机制，解决分布式环境下复杂集合（如 {@code List<UserDTO>}）的反序列化精度丢失问题。
 *
 * @Author cyforkk
 * @Create 2026/3/24 下午9:55
 * @Version 1.0
 */
@Slf4j
public class RedisService {

    /** 核心通信组件：采用 String 序列化器以保障 Redis 端的极致可读性与跨语言兼容性 */
    private final StringRedisTemplate stringRedisTemplate;

    /** 序列化引擎：由 Starter 统一装配，确保全局时间格式与配置策略（如忽略未知属性）的百分之百对齐 */
    private final ObjectMapper objectMapper;

    /**
     * 架构级构造器注入
     * 由外部的 {@code CyforkkRedisAutoConfiguration} 在 SPI 装配时动态推入实例。
     */
    public RedisService(StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    // ========================================================
    // 1. 智能 KV 存储管道 (Intelligent KV Pipeline)
    // ========================================================

    /**
     * 【高级反序列化读】支持复杂泛型集合的自动还原 (Complex Type Reconstitution)
     * <p>
     * <b>架构考量：</b> 解决 Java 泛型擦除特性。通过传入预编译的 {@link JavaType}，
     * 确保 Jackson 能精准识别集合内部的 POJO 类型，而非退化为 {@link java.util.LinkedHashMap}。
     *
     * @param key      物理键
     * @param javaType Jackson 构造的复杂类型描述符（如 List<Shop>）
     * @return 还原后的泛型对象，反序列化失败或 Key 不存在时返回 null (自愈机制)
     */
    public <T> T get(String key, JavaType javaType) {
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)) {
            return null;
        }
        try {
            // 委派内部统一的 ObjectMapper 进行精准解析
            return objectMapper.readValue(json, javaType);
        } catch (Exception e) {
            // 【自愈机制】：若发生实体类变更导致的解析异常，返回 null 触发业务回源查询，防止系统崩溃
            log.error("Redis 反序列化失败 [JavaType]，Key: {}，原因: {}", key, e.getMessage());
            return null;
        }
    }

    /**
     * 【自动序列化写】分布式对象存储入口 (Distributed Object Storage)
     * <p>
     * <b>序列化策略：</b>
     * 1. 若入参为 String，则直接透传，防止 JSON 的二次转义（双引号包裹）。
     * 2. 若入参为 POJO，自动利用全局 ObjectMapper 转换为标准的 JSON 文本。
     *
     * @param key      物理键
     * @param value    Java 内存对象（支持 String, DTO, List 等）
     * @param time     存活时长。若 <= 0 则视作永不过期。
     * @param timeUnit 时间精度
     */
    public void set(String key, Object value, long time, TimeUnit timeUnit) {
        try {
            // 类型感知：自动分流原始字符串与复杂对象
            String jsonValue = (value instanceof String) ? (String) value : objectMapper.writeValueAsString(value);
            if (time > 0) {
                stringRedisTemplate.opsForValue().set(key, jsonValue, time, timeUnit);
            } else {
                stringRedisTemplate.opsForValue().set(key, jsonValue);
            }
        } catch (JsonProcessingException e) {
            log.error("Redis 序列化灾难！Key: {}，对象类型: {}", key, value.getClass().getName(), e);
            throw new RuntimeException("中间件数据序列化异常", e);
        }
    }

    /**
     * 【自动反序列化读】基础类型对象提取
     * <p>
     * <b>优化细节：</b> 若请求类型本身为 String，则跳过 JSON 引擎直接返回，实现“零开销”读取。
     *
     * @param key   物理键
     * @param clazz 目标类型的 Class 句柄
     * @return 还原后的对象实例
     */
    public <T> T get(String key, Class<T> clazz) {
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)) {
            return null;
        }
        try {
            if (clazz == String.class) {
                return (T) json;
            }
            return objectMapper.readValue(json, clazz);
        } catch (Exception e) {
            log.error("Redis 反序列化失败 [Class]，Key: {}，错误: {}", key, e.getMessage());
            return null;
        }
    }

    /**
     * 【高级反序列化】支持 TypeReference 的全量泛型提取 (Full Generic Extraction)
     * <p>
     * <b>架构语义：</b>
     * 针对 Java 的泛型擦除问题，通过捕获 {@link TypeReference} 来保留运行时的完整类型签名。
     * 适用于手动获取 {@code List<Shop>}、{@code Map<String, User>} 等无法通过简单 {@code Class} 描述的集合对象。
     *
     * @param key           物理键
     * @param typeReference Jackson 提供的类型引用匿名类（需使用匿名内部类捕获泛型）
     * @return 还原后的复杂泛型对象，反序列化失败或键不存在时返回 null
     */
    public <T> T get(String key, TypeReference<T> typeReference) {
        // 调用底层的原生字符串提取
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)) {
            return null;
        }
        try {
            // 委派内部统一配置的 objectMapper 进行精准解析
            return objectMapper.readValue(json, typeReference);
        } catch (Exception e) {
            // 触发架构层面的异常自愈，记录日志并返回 null 让业务回源
            log.error("Redis 反序列化失败 [TypeReference]，Key: {}，原因: {}", key, e.getMessage());
            return null;
        }
    }


    /**
     * 【柔性读接口】常规 String 提取。
     * 熔断契约：宕机时返回 null (Fail-Open)。
     */
    public String get(String key){
        return stringRedisTemplate.opsForValue().get(key);
    }

    /**
     * 【刚性读接口】强一致性 String 提取。
     * 阻断契约：宕机时直接上抛异常 (Fail-Closed)，拒绝进行降级返回。
     */
    @NoFallback
    public String getStrict(String key){
        return stringRedisTemplate.opsForValue().get(key);
    }

    /**
     * 缓存驱逐接口
     * @return true=清除成功
     */
    public Boolean delete(String key){
        return stringRedisTemplate.delete(key);
    }

    // ========================================================
    // 2. 内存生命周期管控 (Memory Lifecycle Management)
    // ========================================================

    /**
     * 探针：获取指定键的剩余生存时长 (TTL)。
     */
    public Long getExpire(String key){
        return stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
    }

    /**
     * 续期：为热点数据动态延长生命周期。
     * 场景：WatchDog 看门狗机制或 Session 活跃触碰。
     */
    public Boolean expire(String key, long time, TimeUnit timeUnit){
        return stringRedisTemplate.expire(key, time, timeUnit);
    }

    // ========================================================
    // 3. 分布式并发原语 (Concurrency & Atomic Primitives)
    // ========================================================

    /**
     * 【分布式互斥量】：SETNX 增强版。
     * 核心用途：保障微服务环境下的操作唯一性，防止并发写冲突。
     * 具备原子性的“判定+写入+过期时间”三合一能力。
     */
    public Boolean setIfAbsent(String key, String value, long time, TimeUnit timeUnit){
        return stringRedisTemplate.opsForValue().setIfAbsent(key, value, time, timeUnit);
    }

    /**
     * 【原子脚本执行】：Lua 委派执行网关。
     * <p>
     * <b>架构考量：</b> 在 Redis 服务端执行脚本，利用其单线程特性保障多步操作的绝对原子性（事务语义）。
     * 同时大幅度降低复杂逻辑下多次网络交互造成的 RTT (往返时间) 性能损耗。
     */
    @NoFallback
    public <T> T execute(RedisScript<T> script, List<String> keys, Object... args){
        return stringRedisTemplate.execute(script, keys, args);
    }
}