以下是基于您最新代码重写的架构级 `README.md`，旨在体现该 Starter 的工业级高可用设计与防御性基建特性：

------

# 🛡️ cyforkk-redis-starter

> **面向微服务架构的工业级 Redis 高可用与防御性基础基建**

本组件是基于 Spring Boot SPI 自动装配机制打造的分布式防御中枢。它不仅提供了一键注入的限流、熔断与缓存能力，更通过智能 I/O 门面（Facade）构建了一层防腐网，将业务逻辑与复杂的序列化、物理连接异常彻底解耦。

------

## 🌟 核心防御矩阵 (High Availability Matrix)

### 1. 🚦 分布式原子限流 (Rate Limiting Shield)

- **Lua 原子屏障**：底层采用预编译 Lua 脚本实现 `INCR` 与 `EXPIRE` 的原子绑定，杜绝高并发下的“计数漏洞”。
- **多维漏斗防御**：支持在同一方法上叠加 IP 防刷与基于 SpEL 表达式的业务字段限流（如按用户 ID、手机号），构建多级流量整形网。
- **AST 编译优化**：内置 `SpelUtil` 高性能表达式引擎，具备 L1 级热点解析缓存，确保高频访问下 SpEL 处理的极致吞吐量。

### 2. 🛡️ 柔性物理熔断护盾 (Circuit Breaker Shield)

- **物理故障探测**：`RedisFaultToleranceAspect` 精准区分物理宕机（如 `RedisConnectionFailureException`）与代码 Bug，防止熔断器误判。
- **自适应降级模式**：
  - **Fail-Open（默认）**：Redis 宕机时，全站缓存静默失效，保障主链路业务畅通。
  - **Fail-Closed**：通过 `@NoFallback` 注解强行打穿熔断器，为验证码核验、分布式锁等核心链路提供“快速失败”保障。
- **JVM 拆箱防御**：联动 `DefaultValueUtil` 为 `int`、`boolean` 等基础类型提供安全零值，彻底根除 AOP 降级引发的隐蔽 NPE。

### 3. 📦 防穿透旁路缓存引擎 (Cache-Aside Engine)

- **空值哨兵机制**：自动将数据库空结果标记为 `@@NULL@@` 哨兵。通过短周期的“空标记”拦截针对无效 ID 的恶意砸库行为。
- **智能 TTL 衰减**：针对防穿透空值，系统会自动将其存活时间缩短为业务配置的 1/5（最低 1 单位），在保护后端的同时节约 Redis 内存。

### 4. 🧠 智能 I/O 网关 (Intelligent Gateway)

- **泛型擦除防御**：`RedisService` 全面支持 `JavaType` 与 `TypeReference`，完美还原 `List<DTO>` 等复杂集合，解决 `LinkedHashMap` 强转异常。
- **JSR310 零配置集成**：内置 Jackson `JavaTimeModule`，原生支持 Java 8 `LocalDateTime` 序列化，时间格式符合 ISO 标准。

------

## 🚀 快速开始

### 1. 引入依赖

在项目的 `pom.xml` 中引入本 Starter：

```XML
<dependency>
    <groupId>com.github.cyforkk</groupId>
    <artifactId>cyforkk-redis-starter</artifactId>
    <version>1.0.4</version>
</dependency>
```

### 2. 实战应用示例

#### 多维组合限流

```Java
// 同时限制：IP 维度 60s 内最多 10 次，手机号维度 60s 内最多 1 次
@RateLimit(type = LimitType.IP, time = 60, maxCount = 10)
@RateLimit(type = LimitType.CUSTOM, key = "#phone", time = 60, maxCount = 1)
public void sendSms(String phone) {
    // 业务逻辑...
}
```

#### 旁路缓存与防穿透

```Java
// 自动序列化缓存 30 分钟。若数据库无数据，则自动短效缓存空值（TTL = 6分钟）
@RedisCache(keyPrefix = "user:profile:", key = "#id", ttl = 30)
public UserProfile getUserById(Long id) {
    return userMapper.selectById(id);
}
```

#### 强一致性刚性调用

```Java
// 涉及分布式锁或安全校验，Redis 挂掉时“宁可报错，绝不假装成功”
@NoFallback
public boolean verifyToken(String token) {
    return redisService.get(token) != null;
}
```

------

## 🛠️ 架构设计哲学

- **环境安全嗅探**：依托 `@ConditionalOnClass`，仅在宿主环境包含 Redis 驱动时激活，绝不导致无 Redis 环境的应用启动崩溃。
- **开闭原则契约**：所有组件强制声明 `@ConditionalOnMissingBean`，Starter 提供标准实现的同时，绝对尊重业务方对 Bean 的无损替换主权。
- **命名空间隔离**：强制采用 `cyforkk` 前缀注册基础设施 Bean，彻底杜绝与宿主系统（如黑马点评项目）发生 Bean 定义覆盖冲突。

------

## 📄 许可证

本项目采用 [Apache License 2.0](https://www.google.com/search?q=LICENSE) 开源协议。

------

**Author:** cyforkk | **Updated:** 2026-03-27
