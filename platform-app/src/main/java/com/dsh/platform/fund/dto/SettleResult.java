package com.dsh.platform.fund.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/** 分账结果。 */
@Data
@Builder
public class SettleResult {

    /** 本次实际释放的份额数（重跑时为 0）。 */
    private int releasedCount;

    /** 本次实际释放的金额。 */
    private BigDecimal releasedAmount;

    /** 托管单释放后的状态：RELEASED / PARTIAL_RELEASED。 */
    private String status;
}