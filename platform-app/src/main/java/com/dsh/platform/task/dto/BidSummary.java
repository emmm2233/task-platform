package com.dsh.platform.task.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 报价列表项。
 *
 * <p>⚠️ 暗标模式下，非发单方<b>只会拿到自己那条</b> —— 过滤在服务层做，
 * ⛔ 不能指望前端"拿到全部但不显示"，那样报价早就泄露了。
 */
@Data
public class BidSummary {

    private Long bidId;

    private Long bidderId;

    private String bidderNickname;

    /** 信誉等级代码（发单方据此判断可靠性）。 */
    private String bidderLevel;

    private BigDecimal quoteAmount;

    private Integer durationDays;

    private String planDesc;

    /** PENDING / WITHDRAWN / ACCEPTED / REJECTED。 */
    private String status;

    private String abnormalFlag;

    private LocalDateTime createdAt;
}