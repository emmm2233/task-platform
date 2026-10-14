package com.dsh.platform.identity.audit;

import lombok.Builder;
import lombok.Data;

/**
 * 一条业务审计记录。
 *
 * <p>⛔ 链字段（chainHour / chainSeq / prevHash / rowHash）由 {@link HashChainService}
 * 统一填写，调用方不要自己设 —— 否则链会算错，而链算错是<b>静默</b>的：
 * 写入照样成功，只有下次校验时才发现断裂。
 *
 * <p>⭐ {@code operatorRole} 记录的是<b>操作当时</b>的角色，而不是现在的 ——
 * 角色随时可能被改，审计要回答的是"当时他是以什么身份做的"。
 */
@Data
@Builder
public class AuditRecord {

    private Long operatorId;

    /** 操作时的角色，如 ROLE_ARBITER。 */
    private String operatorRole;

    /** 平台身份 USER / OPERATOR / ADMIN。 */
    private String identity;

    /** 动作码，如 task:audit / account:user:freeze。 */
    private String action;

    private String bizType;

    private Long bizId;

    /** 变更前快照（JSON）。 */
    private String beforeValue;

    /** 变更后快照（JSON）。 */
    private String afterValue;

    private String reason;

    private String ip;

    private String userAgent;

    private String traceId;
}
