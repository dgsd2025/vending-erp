package top.aole.vend.modules.pdca.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.aole.vend.modules.pdca.dto.PdcaDtos;

/**
 * PDCA 到期回查调度器(M4-8 七律 P1-2 修复:兑现"到期自动回查",不再全靠人点)。
 *
 * 设计(参照 §4.14 cron 双出口精神,Java 版):
 * ① {@link #dailyRecheckDue()} 带 {@code @Scheduled},每日 02:00 触发;
 * ② 执行体 {@link #runRecheckDue()} 与 cron 解耦——手动/测试可直接调,不必等到点。
 *
 * 回查逻辑完全复用 {@link ActionItemService#recheckDue}(与手动"一键回查"同一把尺子:达标关闭/未达升级/取不到数人工)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PdcaRecheckScheduler {

    /** 定时回查的操作人标识(op_log 留痕) */
    public static final String OPERATOR = "系统·定时回查";

    private final ActionItemService actionItemService;

    /** 每日 02:00 自动回查到期的进行中改进任务 */
    @Scheduled(cron = "0 0 2 * * ?")
    public void dailyRecheckDue() {
        PdcaDtos.RecheckBatchResp resp = runRecheckDue();
        if (resp.getTotal() > 0) {
            log.info("[PDCA] 到期自动回查完成:共 {} 条,通过 {} / 升级 {} / 需人工 {}",
                    resp.getTotal(), resp.getPassed(), resp.getFailed(), resp.getManual());
        }
    }

    /** 执行体:批量到期回查(与 cron 解耦,便于手动触发与单元测试) */
    public PdcaDtos.RecheckBatchResp runRecheckDue() {
        return actionItemService.recheckDue(OPERATOR);
    }
}
