package com.github.cyforkk.redis.core;

import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 分布式锁客户端 (工厂类)
 * 供业务方 @Autowired 注入使用
 */
public class CyforkkLockClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CyforkkLockClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 获取一把分布式锁
     * @param name 锁的业务名称 (例如 "order:10086")
     * @return CyforkkRedisLock 锁实例
     */
    public CyforkkRedisLock getLock(String name) {
        // 工厂方法内部帮我们去 new 有状态的锁对象，并把底层的 template 传进去
        return new CyforkkRedisLock(name, stringRedisTemplate);
    }
}