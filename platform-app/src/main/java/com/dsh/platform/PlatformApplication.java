package com.dsh.platform;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 任务撮合平台 · 应用入口。
 *
 * <p>形态：<b>模块化单体</b>（9 个限界上下文）+ 独立 WS 进程 {@code platform-ws}。
 * 抢单唯一性 / 竞拍多选中标 / 资金冻结分账三处都要强一致，
 * 单库本地事务是最便宜的正确解（设计 §5.1.3）。
 *
 * <p>⛔ 模块间只能通过 {@code api} 包下的应用服务接口与领域事件通信，
 * 禁止跨模块注入 Mapper 或直接 JOIN。
 */
@SpringBootApplication
@MapperScan("com.dsh.platform.**.mapper")
@EnableScheduling
public class PlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlatformApplication.class, args);
    }
}
