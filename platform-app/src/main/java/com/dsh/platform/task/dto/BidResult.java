package com.dsh.platform.task.dto;

import lombok.Builder;
import lombok.Data;

/** 报价结果。 */
@Data
@Builder
public class BidResult {

    private boolean accepted;

    private Long bidId;

    /** 异常标记：LOW_PRICE（明显低于预算下限）等。⭐ 只标记，不拒绝。 */
    private String abnormalFlag;

    /** 是否覆盖了原有报价（true=改价，false=首次报价）。 */
    private boolean updated;
}