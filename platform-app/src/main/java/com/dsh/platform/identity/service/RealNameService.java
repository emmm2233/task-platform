package com.dsh.platform.identity.service;

import com.dsh.platform.common.BizException;
import com.dsh.platform.common.ErrorCode;
import com.dsh.platform.common.config.CryptoConfig;
import com.dsh.platform.common.crypto.EnvelopeCipher;
import com.dsh.platform.identity.entity.SysRealName;
import com.dsh.platform.identity.entity.SysUser;
import com.dsh.platform.identity.mapper.SysRealNameMapper;
import com.dsh.platform.identity.mapper.SysUserMapper;
import com.dsh.platform.identity.security.DataScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 实名认证服务。
 *
 * <p>L4 数据的处理纪律：
 * <ol>
 *   <li>提交时立即加密，明文<b>不落库、不进日志、不进异常信息</b>；</li>
 *   <li>展示时优先用掩码列，<b>不需要解密</b>；</li>
 *   <li>只有持 {@code realname:audit} 权限的人能看证件照，且<b>看一次记一笔</b>。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RealNameService {

    private final SysRealNameMapper realNameMapper;
    private final SysUserMapper userMapper;
    private final EnvelopeCipher cipher;
    private final CryptoConfig.PhoneHasher hasher;
    private final DataAccessAuditService accessAudit;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    /**
     * 提交实名认证。
     *
     * <p>⛔ 同一身份证号不能绑定到多个账号（防一人多号刷单/刷信誉）。
     * 判定走盲索引，不需要解密任何已有记录。
     */
    @Transactional
    public Long submit(Long userId, String realType, String realName, String idCardNo,
                       String frontKey, String backKey) {
        // ① 一人一号：同一证件号已绑定过别的账号 → 拒
        String idHash = hasher.hash("IDCARD:" + idCardNo);
        SysRealName existing = realNameMapper.selectByIdCardHash(idHash);
        if (existing != null && !existing.getUserId().equals(userId)) {
            // ⚠️ 这里<b>可以</b>明确告知"该证件已绑定其他账号"：用户需要知道去申诉，
            //    而攻击者本来就无法枚举 18 位身份证号（空间太大）。
            throw new BizException(ErrorCode.PARAM_INVALID, "该证件已绑定其他账号，如有疑问请联系客服");
        }
        if (existing != null && existing.getUserId().equals(userId)
                && "PASSED".equals(existing.getStatus())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "你已完成实名认证");
        }

        SysRealName r = new SysRealName();
        r.setUserId(userId);
        r.setRealType(realType);
        r.setRealName(realName);
        // ⭐ 加密存储；明文在下面这行之后就不该再出现在任何地方
        r.setIdCardEnc(cipher.encrypt(idCardNo));
        r.setIdCardHash(idHash);
        r.setIdCardMasked(maskIdCard(idCardNo));
        r.setIdCardFront(frontKey);
        r.setIdCardBack(backKey);
        r.setStatus("PENDING");
        r.setCreatedAt(LocalDateTime.now(clock));
        realNameMapper.insert(r);

        jdbc.update("UPDATE sys_user SET real_name_status = 'PENDING', updated_at = NOW() WHERE id = ?", userId);

        // ⛔ 日志只留掩码
        log.info("提交实名认证 userId={} realType={} 证件={}", userId, realType, r.getIdCardMasked());
        return r.getId();
    }

    /**
     * 本人的实名状态（不需要任何特权权限）。
     *
     * <p>⛔ 只返回掩码与状态，<b>不解密、不返回证件照 key</b>。
     */
    public Map<String, Object> myRealName(Long userId) {
        SysUser u = userMapper.selectById(userId);
        SysRealName r = realNameMapper.selectLatestByUser(userId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", u == null ? "UNSUBMITTED" : u.getRealNameStatus());
        if (r != null) {
            body.put("realType", r.getRealType());
            body.put("realName", r.getRealName());
            body.put("idCardMasked", r.getIdCardMasked());
            body.put("rejectReason", r.getRejectReason());
        }
        return body;
    }

    /**
     * 运营侧查看实名详情（需 {@code realname:audit} 权限，由 Controller 注解保证）。
     *
     * <p>⭐ 返回体里主动带上<b>水印</b>：操作员工号 + 时间 + traceId。
     * 前端把它斜向叠加在证件照上 —— 万一截图外流，能追到是哪一次查看。
     */
    @Transactional
    public Map<String, Object> viewForAudit(Long realNameId, DataScope.Principal principal, String ip) {
        SysRealName r = realNameMapper.selectById(realNameId);
        if (r == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "实名记录不存在");
        }

        // ⭐ 看一眼就记一笔（L4）
        accessAudit.recordAs(principal, "REAL_NAME", r.getId(), r.getUserId(),
                DataAccessAuditService.LEVEL_L4, "实名审核", ip);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("realNameId", r.getId());
        body.put("userId", r.getUserId());
        body.put("realType", r.getRealType());
        body.put("realName", r.getRealName());
        // ⛔ 只给掩码，绝不给 idCardEnc 或明文
        body.put("idCardMasked", r.getIdCardMasked());
        body.put("enterpriseName", r.getEnterpriseName());
        body.put("licenseNo", r.getLicenseNo());
        body.put("status", r.getStatus());

        Map<String, Object> watermark = new LinkedHashMap<>();
        watermark.put("operatorId", principal.userId());
        watermark.put("viewedAt", LocalDateTime.now(clock).toString());
        watermark.put("traceId", org.slf4j.MDC.get("traceId"));
        watermark.put("text", "OP" + principal.userId() + " · " + LocalDateTime.now(clock)
                + " · " + org.slf4j.MDC.get("traceId"));
        body.put("watermark", watermark);

        // 证件照给"签名 URL"而不是裸 key —— 私有 Bucket + 5 分钟有效期
        body.put("idCardFrontUrl", signedUrl(r.getIdCardFront()));
        body.put("idCardBackUrl", signedUrl(r.getIdCardBack()));
        return body;
    }

    /**
     * 审核结论。
     *
     * <p>通过后同步更新 {@code sys_user.real_name_status} 与信誉等级触发点。
     */
    @Transactional
    public void audit(Long realNameId, boolean pass, String reason, Long operatorId) {
        SysRealName r = realNameMapper.selectById(realNameId);
        if (r == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "实名记录不存在");
        }
        if (!"PENDING".equals(r.getStatus())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "该记录已审核过");
        }
        if (!pass && (reason == null || reason.isBlank())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "驳回必须说明原因");
        }

        r.setStatus(pass ? "PASSED" : "REJECTED");
        r.setAuditBy(operatorId);
        r.setAuditAt(LocalDateTime.now(clock));
        r.setRejectReason(pass ? null : reason);
        realNameMapper.updateById(r);

        jdbc.update("UPDATE sys_user SET real_name_status = ?, updated_at = NOW() WHERE id = ?",
                pass ? "PASSED" : "REJECTED", r.getUserId());

        log.info("实名审核 realNameId={} 结果={} by={}", realNameId, pass ? "PASSED" : "REJECTED", operatorId);
    }

    // ------------------------------------------------------------------

    /**
     * 身份证掩码：保留前 3 后 4。
     *
     * <p>示例：{@code 410101199001011234} → {@code 410***********1234}
     */
    static String maskIdCard(String idCardNo) {
        if (idCardNo == null || idCardNo.length() <= 7) {
            return "***";
        }
        int keepHead = 3;
        int keepTail = 4;
        int stars = idCardNo.length() - keepHead - keepTail;
        return idCardNo.substring(0, keepHead) + "*".repeat(stars)
                + idCardNo.substring(idCardNo.length() - keepTail);
    }

    /** 私有 Bucket 的签名 URL。Phase 1 先返回对象 key 的受控路径，T27 接入 MinIO 后替换。 */
    private String signedUrl(String objectKey) {
        if (objectKey == null) {
            return null;
        }
        return "/api/v1/op/real-name-audits/files/" + objectKey;
    }
}
