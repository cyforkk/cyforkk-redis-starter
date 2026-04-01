# 🚀 Cyforkk Redis Starter

`cyforkk-redis-starter` 是一个基于 Spring Boot 3.x 深度定制的 **工业级 Redis 高可用基建组件**。

它不仅封装了常用的缓存操作，还内置了**分布式锁、高并发发号器、防抖幂等、分布式限流、纯内存秒杀**等多种大厂高并发架构场景的开箱即用解决方案，致力于用极其精简的代码和优雅的注解，为业务层屏蔽复杂的并发底层细节。

## ✨ 核心特性 (Features)

- **🔒 优雅的分布式锁 (Factory Pattern)**：基于 `CyforkkLockClient` 工厂模式，彻底解决 Spring 单例注入的并发灾难；底层采用 `SETNX + Lua` 脚本，保证加锁与释放的绝对原子性。
- **🔢 工业级全局唯一 ID 生成器**：位运算极速拼接，采用绝对时间戳 (`Instant`) 规避时区跳跃与回拨问题；内置无锁探活机制，自动清理过期 Key，**彻底告别 Redis 内存泄漏**。
- **🛡️ 声明式高可用防御防护罩 (Annotations)**：
  - `@RateLimit`：支持基于 SpEL 表达式的细粒度分布式限流。
  - `@Idempotent`：表单防重提交、接口幂等性保证。
  - `@RedisCache` / `@RedisEvict`：旁路缓存模式（Cache Aside）的标准实现。
- **⚡ 极致并发秒杀支持**：内置高并发预扣减 Lua 脚本，支持十万级 QPS 纯内存预扣减。
- **🔧 柔性降级与容错**：集成 `@NoFallback` 等容错切面，当 Redis 宕机时保护主业务流程不中断。

------

## 📦 快速开始 (Quick Start)

### 1. 引入依赖

在你的 Spring Boot 项目 `pom.xml` 中引入该 Starter：

```XML
<dependency>
    <groupId>com.github.cyforkk</groupId>
    <artifactId>cyforkk-redis-starter</artifactId>
    <version>2.1.1</version>
</dependency>
```

### 2. 基础配置

在 `application.yaml` 中添加必要配置：

```YAML
cyforkk:
  redis:
    enabled: true  # 组件总开关 (默认 true)
    id-generator:
      epoch-start: 1704067200 # 自定义你的发号器纪元起点 (秒级时间戳，如 2024-01-01)
      
spring:
  data:
    redis:
      host: 127.0.0.1
      port: 6379
      # password: your_password
```

------

## 📖 核心使用指南 (Usage)

### 1. 分布式锁 (CyforkkLockClient)

完美隔离状态，业务方法执行完毕后（或异常时）安全释放锁。

```Java
@Autowired
private CyforkkLockClient cyforkkLockClient;

public void processBusiness(Long userId) {
    // 1. 通过工厂获取该用户的独立锁
    CyforkkRedisLock redisLock = cyforkkLockClient.getLock("order:" + userId);
    
    // 2. 尝试获取锁，设置 10 秒超时防死锁
    if (!redisLock.tryLock(10)) {
        throw new BizException("您操作太快了，请稍后再试！");
    }
    
    try {
        // 执行核心业务...
    } finally {
        // 3. 必须在 finally 中释放锁 (底层通过 Lua 脚本防误删)
        redisLock.unlock();
    }
}
```

### 2. 工业级全局发号器 (CyforkkIdGenerator)

自动拼接时间戳与自增序列号，自带过期策略（TTL），绝不占用多余内存。

```Java
@Autowired
private CyforkkIdGenerator cyforkkIdGenerator;

public void createOrder() {
    // 传入业务前缀，生成类似 "12839182312" 的 Long 类型 64位唯一 ID
    long orderId = cyforkkIdGenerator.nextId("order");
}
```

### 3. 分布式限流 (@RateLimit)

支持动态 SpEL 表达式解析，精准限制特定用户或 IP 的访问频率。

```Java
// 限制同一个用户（根据参数 userId），每 10 秒最多请求 5 次
@RateLimit(key = "'seckill:' + #userId", time = 10, count = 5)
public Result seckill(Long userId) {
    return Result.ok("抢购成功");
}
```

### 4. 接口幂等性拦截 (@Idempotent)

基于 Token 机制或唯一业务键，防止网络抖动导致的表单重复提交。

```Java
@Idempotent(key = "'submit:order:' + #orderDto.orderNo", expireTime = 60)
public void submitOrder(OrderDTO orderDto) {
    // 订单处理逻辑
}
```

### 5. 声明式缓存驱动 (@RedisCache & @RedisEvict)

自动实现标准的 Cache Aside 缓存一致性模式。

```Java
// 查询时自动走缓存，缓存未命中则查库并写入缓存
@RedisCache(keyPrefix = "shop:detail:", key = "#id", ttl = 30, unit = TimeUnit.MINUTES)
public Shop getShopById(Long id) {
    return shopMapper.selectById(id);
}

// 更新数据后，自动删除对应缓存
@RedisEvict(keyPrefix = "shop:detail:", key = "#shop.id")
public void updateShop(Shop shop) {
    shopMapper.updateById(shop);
}
```

------

## 🛠️ 架构演进日志 (Changelog)

### v2.1.1 (最新)

- 🚀 **架构升级**：引入 `CyforkkLockClient` 工厂模式，彻底解决 Spring 单例 Bean 导致的锁覆盖灾难。
- 🛡️ **底层修复**：重构 `CyforkkIdGenerator`，改用 `Instant` 规避 `LocalDateTime` 时区穿透 Bug。
- ♻️ **性能优化**：发号器新增无锁探活与自动 TTL 设置机制，彻底阻断 Redis 慢性内存泄漏。
- ⚡ **并发加强**：预置 `seckill.lua`，打通十万级 QPS 纯内存预扣减链路。

### v1.0.0

- 🎉 初始版本发布，集成基础 Redis 操作封装与声明式缓存切面。

------

## 📝 贡献与许可 (License)

本项目采用 [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0) 开源协议。

欢迎提出 Issue 或 Pull Request 共同完善！
