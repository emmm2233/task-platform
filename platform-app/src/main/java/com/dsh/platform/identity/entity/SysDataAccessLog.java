package com.dsh.platform.identity.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * L3/L4 数据访问审计。
 *
 * <p>⭐ 与业务审计（{@code sys_audit_log}）<b>同规格</b>防篡改：带按小时分段的哈希链。
 * 谁看了谁的身份证，与谁改了哪条数据一样敏感 —— 甚至更敏感，
 * 因为它不改变任何状态，只有审计日志能证明发生过。
 */
@Data
@TableName("sys_data_access_log")
public class SysDataAccessLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 谁看的。 */
    private Long operatorId;

    /** REAL_NAME / PHONE / BANK_CARD / ID_CARD_IMAGE … */
    private String targetType;

    private Long targetId;

    /** 数据属于谁（被查看者）—— 用于"我的信息被谁看过"的反查。 */
    private Long targetUser;

    /** 数据级别 L3 / L4。 */
    private String dataLevel;

    private String scene;

    private Integer rowsCount;

    private String ip;

    private String traceId;

    /** ⭐ 链分段 YYYY-MM-DD-HH —— 按小时分段建链，避免全局串行。 */
    private String chainHour;

    private Integer chainSeq;

    private String prevHash;

    private String rowHash;

    private LocalDateTime createdAt;
}