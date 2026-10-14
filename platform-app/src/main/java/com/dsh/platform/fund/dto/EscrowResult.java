package com.dsh.platform.fund.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/** 托管结果。 */
@Data
@Builder
public class EscrowResult {

    private Long orderId;

    private Long escrowId;

    /** 托管状态：CREATED / FROZEN / PARTIAL_RELEASED / RELEASED / FROZEN_DISPUTE / REFUNDED / CANCELLED。 */
    private String status;

    private BigDecimal amount;

    /** 是否命中幂等（true = 渠道重复回调，未重复记账）。 */
    private boolean idempotent;
}