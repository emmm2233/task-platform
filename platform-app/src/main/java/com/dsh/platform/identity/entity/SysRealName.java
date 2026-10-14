package com.dsh.platform.identity.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 实名认证（L4 —— 全平台最敏感的数据）。
 *
 * <p>三类字段各司其职，⛔ 不要混用：
 * <ul>
 *   <li>{@code idCardEnc} —— 密文，只有实名审核员在受控场景下解密查看；</li>
 *   <li>{@code idCardHash} —— 盲索引，用于"同一身份证是否已注册过"的判定，<b>不可反查</b>；</li>
 *   <li>{@code idCardMasked} —— 掩码，可直接对外展示，不需要解密。</li>
 * </ul>
 * 有了掩码列，绝大多数展示场景（本人查看、列表展示）都<b>不必解密</b> ——
 * 每少一次解密，就少一次泄露风险。
 */
@Data
@TableName("sys_real_name")
public class SysRealName {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** PERSONAL / ENTERPRISE。 */
    private String realType;

    private String realName;

    /** ⚠️ L4 密文（信封加密，带 v1 版本前缀）。 */
    private String idCardEnc;

    /** ⭐ 盲索引：HMAC-SHA256("IDCARD:" + 证件号, 盐)。 */
    private String idCardHash;

    /** ⭐ 掩码：410***********1234。可直接展示，无需解密。 */
    private String idCardMasked;

    /** 证件照的对象存储 key（私有 Bucket，取用需签名 URL）。 */
    private String idCardFront;

    private String idCardBack;

    private String faceVerifyId;

    private String enterpriseName;

    private String licenseNo;

    /** PENDING / PASSED / REJECTED。 */
    private String status;

    private Long auditBy;

    private LocalDateTime auditAt;

    private String rejectReason;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}