package com.github.cyforkk.redis.util;

/**
 * 核心高可用基础工具：JVM 自动拆箱空指针防御盾 (Auto-Unboxing NPE Defense Shield)
 * <p>
 * 【架构语义】
 * 本工具类是解决 AOP 代理（切面）与 Java 强类型系统之间冲突的底层基建。
 * 在微服务柔性降级（Fail-Open）或防缓存穿透（Anti-Penetration）场景下，切面通常需要静默阻断真实方法的执行，并返回兜底值。
 * 此时，若目标方法声明返回基础数据类型（Primitive Types，如 int, boolean），而切面生硬地返回 {@code null}，
 * JVM 在运行时将触发隐式自动拆箱（Auto-Unboxing），瞬间导致极其隐蔽且致命的 {@link NullPointerException}。
 * <p>
 * 【核心机制】
 * 本工具通过反射与类型嗅探，为 Java 的 8 大基础数据类型提供绝对安全的“类型降级（Type Degradation）”策略。
 * 确保在任何极端熔断场景下，上层业务调用方都能拿到合法且安全的默认值（如 0, false），彻底终结 AOP 拆箱雪崩。
 *
 * @Author cyforkk
 * @Create 2026/3/26 上午11:17
 * @Version 1.0
 */
public class DefaultValueUtil {

    /**
     * 智能提取基础数据类型的安全默认值 (Type-Safe Default Value Extractor)
     * <p>
     * <b>处理边界：</b>
     * <ul>
     * <li>对于所有对象类型（包括 {@code String}, {@code Integer} 包装类、自定义 DTO 等），安全放行返回 {@code null}。</li>
     * <li>对于 8 大基础数据类型，严格映射至其底层的二进制零值（0, 0.0, false, \u0000）。</li>
     * <li>对于 {@code void.class} (在反射中亦属于 primitive)，安全返回 {@code null}。</li>
     * </ul>
     *
     * @param returnType 目标方法的真实返回值类型 {@link Class} 对象
     * @return 匹配该类型的安全默认值。若为非基础类型则统一返回 {@code null}
     */
    public static Object getPrimitiveDefaultValue(Class<?> returnType) {
        // 第一道防线：非基础数据类型 (即引用类型/对象) 或者为空，无需拆箱防御，直接返回 null
        if (returnType == null || !returnType.isPrimitive()) {
            return null;
        }

        // 第二道防线：精准路由 8 大基础数据类型的安全零值
        if (returnType == boolean.class) return false;
        if (returnType == int.class || returnType == short.class || returnType == byte.class) return 0;
        if (returnType == long.class) return 0L;
        if (returnType == double.class) return 0.0D;
        if (returnType == float.class) return 0.0F;
        if (returnType == char.class) return '\u0000';

        // 终极兜底：处理 void.class 的特殊场景，安全放行
        return null;
    }
}