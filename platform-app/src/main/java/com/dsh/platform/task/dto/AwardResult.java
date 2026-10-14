package com.dsh.platform.task.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** 中标结果。 */
@Data
@Builder
public class AwardResult {

    private List<Long> awardIds;

    /** 幂等键：同样的任务 + 中标方式 + 中标方集合，重复提交返回同一个键。 */
    private String idempotentKey;

    /** 是否命中幂等（true = 返回的是首次结果，未重复写库）。 */
    private boolean idempotent;

    /** 中标总额。 */
    private java.math.BigDecimal totalAwarded;
}