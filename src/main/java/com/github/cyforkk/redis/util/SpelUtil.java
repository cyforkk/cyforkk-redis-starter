package com.github.cyforkk.redis.util;

import cn.hutool.core.util.StrUtil;
import org.springframework.beans.BeanUtils;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 核心高可用基础基建：高性能 AST 动态语法树解析引擎 (High-Performance AST Parsing Engine)
 * <p>
 * 【架构语义】
 * 本类是支撑 {@code @RateLimit} 和 {@code @RedisCache} 实现“动态业务上下文感知”的核心引擎。
 * 它负责将开发者填写的 SpEL 表达式（如 {@code "#user.id"}）与 AOP 拦截到的真实运行时参数进行绑定与求值。
 * <p>
 * 【核心架构机制】
 * 1. <b>AST 编译缓存 (AST Compilation Cache)</b>：SpEL 字符串解析为抽象语法树（AST）是极度消耗 CPU 的计算密集型操作。本引擎内置基于 {@link ConcurrentHashMap} 的 L1 级热点缓存池。确保全局任意表达式仅在首次被拦截时编译 1 次，随后直接从内存获取编译态对象，保障高并发下的极致吞吐量。
 * 2. <b>字节码嗅探 (Bytecode Sniffing)</b>：集成 Spring 的 {@link DefaultParameterNameDiscoverer}，通过读取 Class 文件的 LocalVariableTable (局部变量表)，精准还原被 AOP 擦除的真实参数名，实现表达式与参数名的无缝映射。
 * 3. <b>安全防御与降级 (Safe Evaluation)</b>：内置严苛的判空阻断机制与基本类型探针。当 AST 求值失败或表达式为空时，提供确定性的降级标识，拒绝生成会导致全站混淆的“脏 Key”。
 *
 * @Author cyforkk
 * @Create 2026/3/24 下午9:15
 * @Version 1.0
 */
public class SpelUtil {

    /** 语法树编译器：负责将字符串翻译为可执行的 Expression 抽象语法树 */
    private final ExpressionParser parser = new SpelExpressionParser();

    /** 字节码参数侦探：负责在运行时探查真实的方法参数名（如 userId, request 等） */
    private final ParameterNameDiscoverer discoverer = new DefaultParameterNameDiscoverer();

    /** * 线程安全的 AST 编译缓存池 (L1 Cache)
     * 架构考量：防止高并发流量下，反复执行 parser.parseExpression 导致 CPU 飙升 (CPU Spike)
     */
    private final Map<String, Expression> expressionCache = new ConcurrentHashMap<>();

    /**
     * 核心求值引擎：将 SpEL 表达式翻译为实际的运行时字符串
     *
     * @param spEL   切面注解上配置的表达式（如 "#user.phone"）
     * @param method 穿透代理层后的真实目标方法
     * @param args   方法运行时实际拦截到的实参矩阵
     * @param target 被拦截的目标对象实例
     * @return 解析并降级后的最终字符串。如果开发者未配置表达式，则安全返回 null。
     * @throws IllegalArgumentException 当表达式合法但求值为 null 时（如 #user.id 但 user 对象里 id 是空的），触发安全阻断 (Fail-Fast)
     */
    public String parse(String spEL, Method method, Object[] args, Object target) {

        // 阶段一：防呆拦截。若注解未配置 SpEL，直接返回 null，交由切面层的 fallback 逻辑接管
        if (StrUtil.isBlank(spEL)) {
            return null;
        }

        // 阶段二：获取 AST 语法树。利用 computeIfAbsent 保证高并发下的单次原子编译
        Expression expression = expressionCache.computeIfAbsent(spEL, parser::parseExpression);

        // 阶段三：构建运行时上下文环境 (Evaluation Context)。
        // 将目标对象、方法句柄、真实参数值以及字节码侦探融合，构建出一个可供 AST 引擎提取数据的“变量沙箱”。
        MethodBasedEvaluationContext context = new MethodBasedEvaluationContext(target, method, args, discoverer);

        // 阶段四：执行编译态语法树，在上下文中提取出 String 类型的具体结果
        String result = expression.getValue(context, String.class);

        // 阶段五：强一致性安全校验。
        // 绝对禁止返回 null，否则会导致 Redis Key 变成类似 "rate_limit:null" 的灾难性共享桶！
        if (result == null) {
            throw new IllegalArgumentException("SpEL表达式求值灾难！表达式 [" + spEL + "] 提取结果为空，请检查入参对象是否存在空指针！");
        }

        return result;
    }

    /**
     * 类型安全探针：判断目标 Class 是否为简单的基础/包装值类型
     * <p>
     * 核心用途：在开发者未编写 SpEL 表达式的极端情况下，本探针用于识别方法的第一个参数。
     * 若为基础类型（如 Long, String），则安全地将其提取为 Redis Key 的兜底后缀；若为复杂对象，则放弃提取。
     *
     * @param clazz 需要探测的 Class 元数据
     * @return true=基础值类型；false=复杂对象或空值
     */
    public boolean isSimpleType(Class<?> clazz) {
        if (clazz == null) {
            return false;
        }
        // 调用 Spring 官方底层工具，实现对 8大基本类型及其包装类、String、Enum 的精准判定
        return BeanUtils.isSimpleValueType(clazz);
    }
}