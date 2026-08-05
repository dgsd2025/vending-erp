package top.aole.vend.regression;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import top.aole.vend.modules.basedata.domain.entity.Machine;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.doc.domain.entity.DocHead;
import top.aole.vend.modules.doc.domain.enums.DocStatus;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.mapper.DocHeadMapper;
import top.aole.vend.modules.doc.service.DocService;
import top.aole.vend.modules.imports.domain.entity.ImportBatch;
import top.aole.vend.modules.imports.dto.ImportDtos;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 穿行场景1:正常补货日(审计结论:通,毛刺=配货单↔转移单核销匹配规则未定义 → P2-12 修正)。
 *
 * M1 已落地并全测:prekit_ticket 落表(表结构+核销字段通道)+ 预挂单冲抵闭环(P0-4):
 *   手工转移单=预挂单只锁仓库侧 → 导入按机器+SKU+当日窗口冲抵不双扣 → 超48h转正补机器侧。
 * M2 待接:配货单生成/核销匹配服务(次日按机器+日期窗口回填 qty_loaded/qty_takeback → 带回率)。
 */
class Scenario01ReplenishDayTest extends RegressionSupport {

    @Autowired
    private DocHeadMapper docHeadMapper;

    @Test
    @DisplayName("prekit_ticket 落表(P2-12):单据/明细两表在,核销字段通道齐(ticket_status/verify_doc_id/takeback_rate)")
    void prekitTicketTablesAndVerifyChannelExist() {
        assertTableExists("yc_vend_prekit_ticket");
        assertTableExists("yc_vend_prekit_ticket_item");
        // 核销通道字段:状态机 已生成/已执行/已核销/有差异 + 匹配转移单 + 带回率
        Map<String, Object> status = assertColumn("yc_vend_prekit_ticket", "ticket_status");
        assertEquals("已生成", String.valueOf(status.get("COLUMN_DEFAULT")), "默认态=已生成");
        assertColumn("yc_vend_prekit_ticket", "verify_doc_id");
        assertColumn("yc_vend_prekit_ticket", "takeback_rate");
        // 明细三列:计划带出/实上架/带回(核销差量=带回率数据源)
        assertColumn("yc_vend_prekit_ticket_item", "qty_planned");
        assertColumn("yc_vend_prekit_ticket_item", "qty_loaded");
        assertColumn("yc_vend_prekit_ticket_item", "qty_takeback");
    }

    @Test
    @DisplayName("补货日主链(P0-4 端到端):手工单→预挂单只锁仓库;导入当日窗口冲抵→不双扣;库存对得上")
    void manualPrePendingOffsetByImportNoDoubleDeduction() throws Exception {
        Machine m = machine("补货日1号机");
        Product p = product("东鹏特饮", "RG6951001", null);
        alias("RG6951001", "东鹏特饮500ml", p.getId());
        stockWarehouse(p.getId(), "40");

        // 补货员手工录出库上架 10 → 确认后进"预挂单":只锁仓库侧,机器账不动
        Long manualId = docService.createDoc(req(DocType.TRANSFER_OUT, m.getId(),
                DocService.SOURCE_MANUAL, LocalDate.now(), new Object[]{p.getId(), "10", "2.5"}), OP);
        docService.submit(manualId, OP);
        docService.confirm(manualId, OP, false, null);
        assertEquals(DocStatus.PRE_PENDING, docHeadMapper.selectById(manualId).getDocStatus());
        assertEquals(0, stockService.getWarehouseStock(p.getId()).compareTo(new BigDecimal("30")),
                "预挂单锁仓库侧 40-10=30");
        assertEquals(0, stockService.getMachineStock(m.getId(), p.getId()).compareTo(BigDecimal.ZERO),
                "预挂单不写机器账");

        // 次日导入后台补货记录(同机器+SKU+当日窗口)→ 唯一生产者生成转移单并冲抵预挂单
        ImportDtos.CommitResp resp = importFile(ImportBatch.TYPE_REPLENISH, repFile(new Object[][]{
                {machineDevice(m), 8, "东鹏特饮500ml", "RG6951001", 0, 10, 10, "小邱",
                        LocalDate.now() + " 16:30:00"},
        }));
        assertEquals(1, resp.getDocsCreated(), "导入生成一张转移单");
        assertEquals(1, resp.getMatchedPrePending(), "冲抵一张手工预挂单");

        DocHead manualAfter = docHeadMapper.selectById(manualId);
        assertEquals(DocStatus.VOID, manualAfter.getDocStatus(), "预挂单被冲抵→已作废");
        assertNotNull(manualAfter.getMatchedDocId(), "matched_doc_id 指向导入单");
        // 不双扣:预挂单锁的10已释放,只剩导入单扣的10 → 仓库 30
        assertEquals(0, stockService.getWarehouseStock(p.getId()).compareTo(new BigDecimal("30")));
        // 机器账以导入单+快照为准 = 10
        assertEquals(0, stockService.getMachineStock(m.getId(), p.getId()).compareTo(new BigDecimal("10")));

        // 导入生成的转移单是唯一真账
        List<DocHead> importDocs = docHeadMapper.selectList(new LambdaQueryWrapper<DocHead>()
                .eq(DocHead::getImportBatchId, resp.getBatchId()));
        assertEquals(1, importDocs.size());
        assertEquals(DocService.SOURCE_IMPORT, importDocs.get(0).getDocSource());
        assertEquals(DocStatus.CONFIRMED, importDocs.get(0).getDocStatus());
    }

    @Test
    @DisplayName("超48h无后台记录→预挂单转正:补写机器侧库存,仓库不重复扣")
    void prePendingPromotedAfter48hPostsMachineSideOnly() {
        Machine m = machine("补货日2号机");
        Product p = product("红牛", null, null);
        stockWarehouse(p.getId(), "20");

        Long manualId = docService.createDoc(req(DocType.TRANSFER_OUT, m.getId(),
                DocService.SOURCE_MANUAL, LocalDate.now(), new Object[]{p.getId(), "6", "5"}), OP);
        docService.submit(manualId, OP);
        docService.confirm(manualId, OP, false, null);
        assertEquals(0, stockService.getWarehouseStock(p.getId()).compareTo(new BigDecimal("14")));

        // 模拟定时任务:超48h仍无后台记录 → 转正,只补机器侧(MACHINE_ONLY)
        docService.promotePendingTransfer(manualId, OP, LocalDate.now().atTime(17, 0));
        assertEquals(DocStatus.CONFIRMED, docHeadMapper.selectById(manualId).getDocStatus());
        assertEquals(0, stockService.getWarehouseStock(p.getId()).compareTo(new BigDecimal("14")),
                "转正不重复扣仓库");
        assertEquals(0, stockService.getMachineStock(m.getId(), p.getId()).compareTo(new BigDecimal("6")),
                "转正补写机器侧 +6");
    }

    @Test
    @Disabled("M2-pending:配货单(prekit_ticket)生成/核销匹配服务未实现——缺\"次日按机器+日期窗口匹配导入转移单→回填 qty_loaded/qty_takeback→算带回率\"的业务断言;表结构与字段通道已在本类第一例验过")
    void prekitVerifyMatchAndTakebackRate() {
        // M2 实装后:建配货单(计划带出)→ 导入补货记录 → 核销匹配 → 差量=带回 → takeback_rate 正确
    }

    /** 取机器的后台设备ID(导入通道锚点) */
    private String machineDevice(Machine m) {
        return machineMapper.selectById(m.getId()).getDeviceId();
    }
}
