package com.dsh.platform.identity.audit;

/**
 * 哈希链校验结果。
 *
 * @param total          该小时段的总记录数
 * @param validCount     逐条重算哈希后一致、且与上一条正确衔接的条数
 * @param brokenCount    断裂条数（哈希对不上，或 prev 与上一条的 rowHash 不衔接）
 * @param firstBrokenSeq 第一条断裂记录的段内序号（-1 表示无断裂）—— 便于直接定位
 */
public record VerifyResult(int total, int validCount, int brokenCount, int firstBrokenSeq) {

    public static VerifyResult empty() {
        return new VerifyResult(0, 0, 0, -1);
    }

    public boolean isIntact() {
        return brokenCount == 0;
    }
}
