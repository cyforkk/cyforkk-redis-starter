package com.github.cyforkk.redis.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * ClassName: CyforkkRedisProperties
 * Package: com.github.cyforkk.redis.config
 *
 * @Author cyforkk
 * @Create 2026/3/30 下午9:48
 * @Version 1.0
 */
@Data
@ConfigurationProperties(prefix = "cyforkk.redis")
public class CyforkkRedisProperties {
    /**
     * 全局开关，默认开启
     */
    private boolean enabled = true;

    /**
     * 分配器配置
     */
    private IdGenerator idGenerator = new IdGenerator();

    /**
     * 静态内部类
     */
    @Data
    public static class IdGenerator{
        /**
         * 全局唯一 ID 的自定义纪元时间（秒级时间戳）
         * 默认值：1704067200L (北京时间 2024-01-01 00:00:00)
         * 提示：设置得越接近系统上线时间，31位时间戳能使用的时间越长（最长约 69 年）
         */
        private long epochStart = 1767225600L;

        /**
         * 辅助工具：根据字符串日期获取秒级时间戳 (用于配置 epoch-start)
         * 开发者可以调用此方法计算出时间戳，然后填入 application.yml
         *
         * @param dateTimeStr 日期时间字符串，格式：yyyy-MM-dd HH:mm:ss
         * @return 秒级时间戳
         */
        public static long getEpochSecondByString(String dateTimeStr) {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            LocalDateTime localDateTime = LocalDateTime.parse(dateTimeStr, formatter);
            // 注意：底层生成发号器时用的是 UTC，这里也保持一致
            return localDateTime.toEpochSecond(ZoneOffset.UTC);
        }

        // 提供一个 main 方法，别人即使不写测试类，直接在源码里点一下运行就能算出值
        public static void main(String[] args) {
            // 比如想把起点设为 2026 年 1 月 1 日
            long epoch = getEpochSecondByString("2026-01-01 00:00:00");
            System.out.println("请将此值填入 yml 配置中: " + epoch);
            // 预期输出: 1767225600
        }
    }
}
