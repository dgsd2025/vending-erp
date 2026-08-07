package top.aole.vend.regression;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import top.aole.vend.modules.prekit.domain.entity.PrekitTicket;
import top.aole.vend.modules.prekit.domain.entity.PrekitTicketItem;
import top.aole.vend.modules.prekit.dto.PrekitDtos;
import top.aole.vend.modules.prekit.mapper.PrekitTicketItemMapper;
import top.aole.vend.modules.prekit.mapper.PrekitTicketMapper;
import top.aole.vend.modules.prekit.service.PrekitService;
import top.aole.vend.modules.replenish.domain.entity.ReplenishPlan;
import top.aole.vend.modules.replenish.mapper.ReplenishPlanMapper;
import top.aole.vend.modules.replenish.service.ReplenishEngine;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 穿行场景1:正常补货日(审计结论:通,毛刺=配货单↔转移单核销匹配规则未定义 → P2-12 修正)。
 *
 * M1 已落地并全测:prekit_ticket 落表(表结构+核销字段通道)+ 预挂单冲抵闭环(P0-4):
 *   手工转移单=预挂单只锁仓库侧 → 导入按机器+SKU+当日窗口冲抵不双扣 → 超48h转正补机器侧。
 * M2-3 已接:配货单生成/核销匹配服务(次日 ±48h 窗口按机器×SKU 匹配导入转移单 →
 *   回填 qty_loaded/qty_takeback → takeback_rate;本类末例端到端验证)。
 */
class Scenario01ReplenishDayTest extends RegressionSupport {

    @Autowired
    private DocHeadMapper docHeadMapper;
    @Autowired
    private PrekitService prekitService;
    @Autowired
    private PrekitTicketMapper prekitTicketMapper;
    @Autowired
    private PrekitTicketItemMapper prekitItemMapper;
    @Autowired
    private ReplenishPlanMapper replenishPlanMapper;

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
    @DisplayName("配货单核销闭环(M2-3 已实装):建单(计划带出)→次日导入补货记录→机器×SKU 窗口匹配→回填 qty_loaded/qty_takeback→带回率正确")
    void prekitVerifyMatchAndTakebackRate() throws Exception {
        Machine m = machine("补货日3号机");
        Product p1 = product("农夫山泉", "RG6953001", null);
        Product p2 = product("阿萨姆奶茶", "RG6953002", null);
        alias("RG6953001", "农夫山泉550ml", p1.getId());
        alias("RG6953002", "阿萨姆奶茶500ml", p2.getId());
        stockWarehouse(p1.getId(), "50");
        stockWarehouse(p2.getId(), "50");

        // 机器侧建议 → 配货单(p1 带出 10,p2 带出 6)→ 仓库装箱出发
        ReplenishPlan pl1 = machinePlan(m.getId(), p1.getId(), "10");
        ReplenishPlan pl2 = machinePlan(m.getId(), p2.getId(), "6");
        PrekitDtos.GenerateReq genReq = new PrekitDtos.GenerateReq();
        genReq.setItems(new ArrayList<>());
        for (ReplenishPlan pl : new ReplenishPlan[]{pl1, pl2}) {
            PrekitDtos.GenerateItem gi = new PrekitDtos.GenerateItem();
            gi.setPlanId(pl.getId());
            genReq.getItems().add(gi);
        }
        Long ticketId = prekitService.generate(genReq, OPERATOR).getTicketIds().get(0);
        prekitService.execute(ticketId, OPERATOR);
        assertEquals(PrekitTicket.STATUS_EXECUTED, prekitTicketMapper.selectById(ticketId).getTicketStatus());

        // 次日导入后台补货记录:p1 全上 10,p2 只上 4(带回 2)
        ImportDtos.CommitResp resp = importFile(ImportBatch.TYPE_REPLENISH, repFile(new Object[][]{
                {machineDevice(m), 1, "农夫山泉550ml", "RG6953001", 0, 10, 10, "小邱",
                        LocalDate.now().plusDays(1) + " 16:30:00"},
                {machineDevice(m), 2, "阿萨姆奶茶500ml", "RG6953002", 0, 4, 4, "小邱",
                        LocalDate.now().plusDays(1) + " 16:30:00"},
        }));
        assertEquals(1, resp.getDocsCreated(), "同机器同时间戳聚合为一张转移单");
        assertEquals(1, resp.getPrekitVerified(), "afterImport 钩子核销本张配货单");

        // 核销结果:差量=带回 → 有差异;带回率 = 2/16 = 0.125
        PrekitTicket ticket = prekitTicketMapper.selectById(ticketId);
        assertEquals(PrekitTicket.STATUS_DIFF, ticket.getTicketStatus());
        assertEquals(0, ticket.getTakebackRate().compareTo(new BigDecimal("0.125")),
                "takeback_rate = Σ带回/Σ带出 = 2/16");
        assertNotNull(ticket.getVerifyDocId(), "verify_doc_id 指向匹配的导入转移单");
        assertNotNull(ticket.getVerifyAt());
        assertEquals(DocService.SOURCE_IMPORT,
                docHeadMapper.selectById(ticket.getVerifyDocId()).getDocSource());

        List<PrekitTicketItem> items = prekitItemMapper.selectList(
                new LambdaQueryWrapper<PrekitTicketItem>()
                        .eq(PrekitTicketItem::getTicketId, ticketId)
                        .orderByAsc(PrekitTicketItem::getId));
        assertEquals(0, items.get(0).getQtyLoaded().compareTo(new BigDecimal("10")), "p1 实上架 10");
        assertEquals(0, items.get(0).getQtyTakeback().compareTo(BigDecimal.ZERO), "p1 带回 0");
        assertEquals(0, items.get(1).getQtyLoaded().compareTo(new BigDecimal("4")), "p2 实上架 4");
        assertEquals(0, items.get(1).getQtyTakeback().compareTo(new BigDecimal("2")), "p2 带回 2 = 带出6-上架4");
    }

    /** 机器侧补货建议行(状态=建议,M2-3 配货单入口) */
    private ReplenishPlan machinePlan(Long machineId, Long productId, String qty) {
        ReplenishPlan p = new ReplenishPlan();
        p.setPlanDate(LocalDate.now());
        p.setPlanType(ReplenishEngine.TYPE_MACHINE);
        p.setMachineId(machineId);
        p.setProductId(productId);
        p.setCurrentQty(BigDecimal.ZERO);
        p.setSuggestQty(new BigDecimal(qty));
        p.setPlanStatus(ReplenishEngine.STATUS_PENDING);
        replenishPlanMapper.insert(p);
        return p;
    }

    /** 取机器的后台设备ID(导入通道锚点) */
    private String machineDevice(Machine m) {
        return machineMapper.selectById(m.getId()).getDeviceId();
    }
}
