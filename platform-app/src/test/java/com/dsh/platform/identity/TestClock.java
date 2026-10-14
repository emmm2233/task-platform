package com.dsh.platform.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 测试用可快进时钟。
 *
 * <p>⛔ 不要在业务代码里用 {@code Thread.sleep} 验证"30 秒宽限期"这类边界 ——
 * 测试会变慢且不稳定。生产代码统一注入 {@link Clock}，测试里换成本类快进即可。
 */
public class TestClock extends Clock {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private Instant now = Instant.now();

    public void fastForward(Duration d) {
        now = now.plus(d);
    }

    public void reset() {
        now = Instant.now();
    }

    @Override
    public ZoneId getZone() {
        return ZONE;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
