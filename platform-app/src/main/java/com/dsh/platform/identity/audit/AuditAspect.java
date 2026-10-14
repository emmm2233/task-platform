package com.dsh.platform.identity.audit;

import com.dsh.platform.identity.security.DataScope;
import com.dsh.platform.identity.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

/**
 * 审计切面：把 {@link Audit} 注解变成一条真实的审计记录。
 *
 * <p>三条设计取舍：
 * <ol>
 *   <li><b>只在方法成功返回后记录</b>（{@code @AfterReturning}）。
 *       失败的操作不改变任何状态，记了反而制造噪声 ——
 *       失败本身有日志与监控，那是另一条线。</li>
 *   <li>⭐ <b>审计写入失败不得影响业务</b>：整体 try/catch 后只记 ERROR。
 *       反过来做（审计失败就回滚业务）会导致"审计库抖一下，全站不可用"。
 *       ⚠️ 代价是极端情况下可能丢审计 —— 所以另有每日链完整性校验兜底；
 *       若业务合规要求"审计必须成功"，应改为同事务写入。</li>
 *   <li>操作人、角色、IP、traceId 全部从当前请求上下文取，
 *       ⛔ 不让业务代码传 —— 传就有传错和漏传的可能。</li>
 * </ol>
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AuditAspect {

    private final HashChainService chain;

    @AfterReturning(pointcut = "@annotation(audit)", returning = "result")
    public void afterReturning(JoinPoint jp, Audit audit, Object result) {
        try {
            Method method = ((MethodSignature) jp.getSignature()).getMethod();
            Object[] args = jp.getArgs();

            AuditRecord.AuditRecordBuilder b = AuditRecord.builder()
                    .action(audit.action())
                    .bizType(audit.bizType())
                    .bizId(extractBizId(method, args, audit.bizIdParam()))
                    .traceId(MDC.get("traceId"));

            HttpServletRequest req = currentRequest();
            DataScope.Principal principal = null;
            if (req != null) {
                b.ip(clientIp(req)).userAgent(req.getHeader("User-Agent"));
                Object attr = req.getAttribute(JwtAuthenticationFilter.ATTR_PRINCIPAL);
                if (attr instanceof DataScope.Principal p) {
                    principal = p;
                }
            }
            if (principal != null) {
                b.operatorId(principal.userId()).identity(principal.identity());
            }

            chain.append(b.build());
        } catch (Exception e) {
            // ⭐ 审计失败不影响业务（见类注释第 2 条）
            log.error("写审计失败 action={} —— 业务已成功，需人工核查", audit.action(), e);
        }
    }

    /** 从方法参数里取业务主键。取不到就留空 —— ⛔ 不猜。 */
    private Long extractBizId(Method method, Object[] args, String paramName) {
        if (paramName == null || paramName.isBlank()) {
            return null;
        }
        Parameter[] params = method.getParameters();
        for (int i = 0; i < params.length && i < args.length; i++) {
            if (paramName.equals(params[i].getName()) && args[i] instanceof Long v) {
                return v;
            }
        }
        return null;
    }

    private static HttpServletRequest currentRequest() {
        var attrs = RequestContextHolder.getRequestAttributes();
        return attrs instanceof ServletRequestAttributes sra ? sra.getRequest() : null;
    }

    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        return (xff == null || xff.isBlank()) ? request.getRemoteAddr() : xff.split(",")[0].trim();
    }
}