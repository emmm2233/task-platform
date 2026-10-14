package com.dsh.platform.common.page;

import com.dsh.platform.common.BizException;
import com.dsh.platform.common.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 游标（键集）分页的编解码。
 *
 * <p>⛔ <b>为什么不用 offset 分页</b>：大厅按发布时间倒序，而任务随时在被发布与中标。
 * 用 {@code LIMIT 10 OFFSET 20} 时，若在你翻到第 3 页之前有 2 条新任务插到前面，
 * 第 3 页就会<b>重复</b>显示第 2 页末尾的 2 条。游标分页没有这个问题 ——
 * 它记的是"上一页最后一条是谁"，而不是"跳过多少条"。
 *
 * <p>⭐ 游标里内嵌<b>筛选指纹</b>：换筛选条件后复用旧游标会被拒。
 * 没有这个校验的话，用户在"分类=开发"下翻到第 3 页、改成"分类=设计"再翻，
 * 会拿到一段与前面不连续的页 —— 且不报错、不重复，极难发现。
 *
 * <p>格式：{@code base64url(排序键值 \u0001 id \u0001 指纹)}。
 * 用 {@code \u0001} 分隔是因为它不可能出现在排序键（ISO 时间）或指纹（十六进制）里，
 * ⛔ 用 {@code :} 或 {@code |} 就有被内容污染的风险。
 *
 * <p>⚠️ 游标<b>不加密、不签名</b>：客户端能看到排序键与指纹，也能自己造一个。
 * 这是可接受的 —— 它只是"从哪继续"，伪造游标最多是越权翻页，而每一页的内容
 * 仍受数据范围（{@code DataScope}）约束。若将来游标里要放敏感信息，必须加签名。
 */
public final class CursorCodec {

    private static final char SEP = '\u0001';
    private static final int PARTS = 3;

    private CursorCodec() {
    }

    /**
     * 编码。
     *
     * @param sortKey     排序键值（如 created_at 的 ISO 字符串）
     * @param id          同键值时的去重键（主键），避免同毫秒数据反复出现
     * @param fingerprint 当前筛选条件的指纹
     */
    public static String encode(String sortKey, Long id, String fingerprint) {
        String raw = sortKey + SEP + id + SEP + fingerprint;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** 解码（不校验指纹）。 */
    public static Cursor decode(String token) {
        return decode(token, null);
    }

    /**
     * 解码并校验指纹。
     *
     * @param expectedFingerprint 非空时校验；不一致即拒
     */
    public static Cursor decode(String token, String expectedFingerprint) {
        if (token == null || token.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "游标不能为空");
        }
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // 客户端可能传了被截断/篡改的游标 —— 属于参数错误，不是服务端故障
            throw new BizException(ErrorCode.PARAM_INVALID, "游标格式不正确");
        }
        String[] parts = raw.split(String.valueOf(SEP), -1);
        if (parts.length != PARTS) {
            throw new BizException(ErrorCode.PARAM_INVALID, "游标格式不正确");
        }
        long id;
        try {
            id = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "游标格式不正确");
        }

        Cursor cursor = new Cursor(parts[0], id, parts[2]);
        if (expectedFingerprint != null && !expectedFingerprint.equals(cursor.fingerprint())) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "筛选条件已变更，请重新从第一页开始浏览");
        }
        return cursor;
    }

    /**
     * 根据筛选条件算指纹。
     *
     * <p>⛔ 顺序敏感：调用方必须固定拼接顺序（本类统一用 {@code |} 连接并按 {@code null→""}）。
     */
    public static String fingerprint(Object... parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append('|');
            }
            sb.append(parts[i] == null ? "" : parts[i]);
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (Exception e) {
            throw new IllegalStateException("游标指纹计算失败", e);
        }
    }

    /** 解码结果。 */
    public record Cursor(String sortKey, long id, String fingerprint) {
    }
}