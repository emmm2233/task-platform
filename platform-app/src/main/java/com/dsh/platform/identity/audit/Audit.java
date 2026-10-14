package com.dsh.platform.identity.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记需要写业务审计的方法。
 *
 * <p>用法：
 * <pre>
 *   &#64;Audit(action = "account:user:freeze", bizType = "USER", bizIdParam = "userId")
 *   public void freeze(Long userId, String reason) { ... }
 * </pre>
 *
 * <p>⭐ 用注解而不是在各处手写 {@code auditService.record(...)}：
 * 手写要记得传操作人、角色、IP、traceId、前后快照 —— 少一个就是一条残缺的审计。
 * 注解把"记什么"与"怎么记"分开，业务代码只声明意图。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Audit {

    /** 动作码，须与 {@code sys_permission.perm_code} 口径一致，如 task:audit。 */
    String action();

    /** 业务类型，如 USER / TASK / ORDER / DISPUTE。 */
    String bizType() default "";

    /**
     * 从方法参数里取业务主键的参数名（如 {@code "userId"}）。
     *
     * <p>⚠️ 依赖 {@code -parameters} 编译开关 —— 本工程已在父 pom 中开启，
     * 关掉它这里会在运行时抛 IllegalArgumentException（T6 踩过这个坑）。
     */
    String bizIdParam() default "";

    /** 是否记录前后快照。涉及敏感数据的动作应关掉（⛔ 快照里不要落 L3/L4 明文）。 */
    boolean snapshot() default false;
}