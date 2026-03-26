# 🛡️ cyforkk-redis-starter

> **面向微服务架构的工业级 Redis 高可用与防御性基础组件**

[![Version](https://img.shields.io/badge/version-1.0.0-blue.svg)]()
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.7%2B%20%7C%203.x-brightgreen.svg)]()
[![Redis](https://img.shields.io/badge/Redis-Lettuce%20%7C%20Jedis-red.svg)]()
[![Author](https://img.shields.io/badge/Author-Cyforkk-orange.svg)]()

本组件是一套基于 Spring Boot SPI 自动装配机制打造的底层中间件。它为宿主业务系统（如社区平台、电商系统等）一键注入**多维原子限流、物理熔断降级、防穿透旁路缓存**三大高可用护盾，实现了对业务代码的“零侵入”防御。

---

## 🌟 核心架构与特性 (Core Features)

### 1. 🚦 分布式原子限流 (Atomic Rate Limiting)
- **Lua 原子屏障**：底层采用预编译 `Lua` 脚本，将 `INCR` 与 `EXPIRE` 强绑定，彻底杜绝极高并发下的竞态漏洞。
- **多维防线叠加**：通过 `@RateLimits` 复合注解，支持在同一接口上无缝叠加多维度限流网（如：防单 IP 爆破 + 防单用户高频）。
- **动态路由与空间隔离**：
  - 支持 `LimitType.IP`（L4/L7 网络层限流）与 `LimitType.CUSTOM`（业务层限流）。
  - 集成 `SpelUtil` 高性能 AST 解析引擎，支持直接提取方法入参（如 `#phone`）。
  - 自动提取 `[类名.方法名]` 构建物理隔离命名空间，杜绝全站同名方法（如 `add`）的限流桶交叉碰撞。

### 2. 🛡️ 柔性物理熔断器 (Fail-Open Circuit Breaker)
- **精准故障识别**：`RedisFaultToleranceAspect` 拦截器精准区分物理宕机（如 `RedisConnectionFailureException`）与业务代码 Bug，控制爆炸半径。
- **自适应降级状态机**：
  - **Fail-Open（柔性放行）**：Redis 宕机时，缓存与限流操作静默失效，通过 `DefaultValueUtil` 自动处理基础类型（`int`, `boolean`）拆箱，防止 NPE，保障核心业务继续裸跑。
  - **Fail-Closed（严格阻断）**：提供 `@NoFallback` 注解。对于极度敏感的资产接口（如发券、支付），在 Redis 故障时强制抛出异常并阻断执行，防范资金损失。

### 3. 📦 防穿透旁路缓存引擎 (Cache-Aside Engine)
- **智能 TTL 降级策略**：通过 `@RedisCache` 开启旁路缓存。面对缓存穿透攻击时，系统会自动缓存空值，并**智能将其 TTL 缩短为原配置的 1/5**，在防压垮数据库的同时最大限度节约 Redis 内存资源。

### 4. 🧩 极致的装配隔离 (Zero-Intrusion SPI)
- 遵循 Spring Boot 新一代 `imports` 装配规范。所有底层基础设施 Bean（如 `cyforkkSpelUtil`, `cyforkkRedisService`）强制注入 `cyforkk` 前缀，严格保障命名空间安全，彻底杜绝与宿主业务系统的 Bean 覆盖冲突。

---

## 🚀 快速开始 (Quick Start)

### 1. 引入依赖
在你的 Spring Boot 项目 `pom.xml` 中引入本组件：

```xml
<dependency>
    <groupId>com.github.cyforkk</groupId>
    <artifactId>cyforkk-redis-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 2. 环境要求

宿主项目必须已引入 `spring-boot-starter-data-redis` 并注册了 `StringRedisTemplate`。 Starter 内置环境安全嗅探（`@ConditionalOnClass`），无 Redis 环境时自动休眠，绝不导致应用启动崩溃。

------

## 📖 实战指南 (Usage Examples)

### 场景一：高频接口防刷（多维组合限流）

以下示例展示了如何同时防范 **代理 IP 爆破** 与 **单账号恶意轰炸**：

```Java
@RestController
@RequestMapping("/user")
public class UserController {

    // 1. 业务维度：限制同一个手机号 60秒内只能发 1 次，触发时提示默认常量文案
    @RateLimit(key = "#phone", time = 60, maxCount = 1, type = LimitType.CUSTOM)
    // 2. 网络维度：限制同一个 IP 60秒内最多访问 3 次，触发时由切面智能切换为网络异常提示
    @RateLimit(time = 60, maxCount = 3, type = LimitType.IP)
    @PostMapping("/code")
    public Result sendPhoneCode(@RequestParam("phone") String phone) {
        return userService.sendCode(phone);
    }
}
```

*注：触发限流将抛出 `RateLimitException`，宿主系统需在全局异常处理器中进行捕获映射（如返回 HTTP 429）。*

### 场景二：核心资产极速阻断（Fail-Closed 模式）

对于涉及资金或短信的底层执行操作，若希望在 Redis 挂掉时“宁可报错，绝不放行”，请叠加 `@NoFallback`：

```Java
@Service
public class OrderService {

    @NoFallback // Redis 物理故障时直接抛出异常，不再执行柔性降级返回 null
    public void deductInventory(Long skuId, int count) {
        // 核心扣减逻辑...
    }
}
```

### 场景三：旁路缓存与防穿透保护

一键开启旁路缓存，自动处理 JSON 序列化与空值防穿透：

```Java
@Service
public class ShopService {

    // 缓存 30 分钟，若查不到数据，将短时缓存空对象 (TTL = 30/5 = 6分钟)
    @RedisCache(keyPrefix = "shop:info:", key = "#id", ttl = 30, unit = TimeUnit.MINUTES)
    public ShopDTO getShopInfo(Long id) {
        return shopMapper.selectById(id);
    }
}
```

------

## 📄 许可证 (License)

本项目采用 [Apache License 2.0](https://www.google.com/search?q=LICENSE) 开源协议。

------

**Author:** Cyforkk | **Version:** 1.0.0

