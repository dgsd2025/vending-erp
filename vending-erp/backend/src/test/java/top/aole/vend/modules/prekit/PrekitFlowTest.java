package top.aole.vend.modules.prekit;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.domain.entity.Machine;
import top.aole.vend.modules.basedata.domain.entity.Product;
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
import top.aole.vend.regression.RegressionSupport;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M2-3 Pre-kit 配货单闭环集成测试(vend_test_prekit 独立库,Flyway 自迁,每例事务回滚):
 * 生成(plan 状态联动)/改量/执行/导入核销全配(带回率0)/部分核销(差量→带回率>0)/
 * 超窗黄灯/FEFO 最早入库日/幂等/手工转移单兜底。
 *
 * 核销规则(P2-12):次日(±48h 窗口)通道2导入的转移单按 机器×SKU 匹配,
 * 带出 vs 实上架,差量=带回,takeback_rate=Σ带回/Σ带出。
 */
@ActiveProfiles("test-prekit")
class PrekitFlowTest extends RegressionSupport {

    @Autowired
    private PrekitService prekitService;
    @Autowired
    private ReplenishPlanMapper planMapper;
    @Autowired
    private PrekitTicketMapper ticketMapper;
    @Autowired
    private PrekitTicketItemMapper itemMapper;
    @Autowired
    private DocHeadMapper docHeadMapper;

    // ============================== 造数/取数小工具 ==============================

    /** 机器侧补货建议行(状态=建议) */
    private ReplenishPlan plan(Long machineId, Long productId, String qty, LocalDate planDate) {
        ReplenishPlan p = new ReplenishPlan();
        p.setPlanDate(planDate);
        p.setPlanType(ReplenishEngine.TYPE_MACHINE);
        p.setMachineId(machineId);
        p.setProductId(productId);
        p.setCurrentQty(BigDecimal.ZERO);
        p.setTargetLevelS(new BigDecimal(qty));
        p.setSuggestQty(new BigDecimal(qty));
        p.setPlanStatus(ReplenishEngine.STATUS_PENDING);
        planMapper.insert(p);
        return p;
    }

    private ReplenishPlan plan(Long machineId, Long productId, String qty) {
        return plan(machineId, productId, qty, LocalDate.now());
    }

    private PrekitDtos.GenerateReq genReq(ReplenishPlan... plans) {
        PrekitDtos.GenerateReq req = new PrekitDtos.GenerateReq();
        List<PrekitDtos.GenerateItem> items = new ArrayList<>();
        for (ReplenishPlan p : plans) {
            PrekitDtos.GenerateItem item = new PrekitDtos.GenerateItem();
            item.setPlanId(p.getId());
            items.add(item);
        }
        req.setItems(items);
        return req;
    }

    private List<PrekitTicketItem> itemsOf(Long ticketId) {
        return itemMapper.selectList(new LambdaQueryWrapper<PrekitTicketItem>()
                .eq(PrekitTicketItem::getTicketId, ticketId).orderByAsc(PrekitTicketItem::getId));
    }

    private Map<String, Object> listedRow(Long ticketId) {
        return prekitService.listTickets(null).stream()
                .filter(r -> ticketId.equals(((Number) r.get("id")).longValue()))
                .findFirst().orElseThrow(() -> new AssertionError("列表里找不到配货单 " + ticketId));
    }

    // ============================== 用例 ==============================

    @Test
    @DisplayName("生成:按机分组一机一单;行=SKU×建议量(可改);建议行状态 建议→已生成配货单")
    void generateGroupsByMachineAndLinksPlanStatus() {
        Machine m1 = machine("PK1号机");
        Machine m2 = machine("PK2号机");
        Product pa = product("东鹏特饮", null, null);
        Product pb = product("阿萨姆奶茶", null, null);
        ReplenishPlan pl1 = plan(m1.getId(), pa.getId(), "10");
        ReplenishPlan pl2 = plan(m1.getId(), pb.getId(), "6");
        ReplenishPlan pl3 = plan(m2.getId(), pa.getId(), "8");

        PrekitDtos.GenerateReq req = genReq(pl1, pl2, pl3);
        req.getItems().get(1).setQty(new BigDecimal("5")); // pl2 改量 6→5
        PrekitDtos.GenerateResp resp = prekitService.generate(req, OPERATOR);

        assertEquals(2, resp.getTicketCount(), "两台机器 → 两张配货单(一机一箱)");
        assertEquals(3, resp.getItemCount());
        // m1 的单:2 行,pl2 用改后的量
        PrekitTicket t1 = ticketMapper.selectOne(new LambdaQueryWrapper<PrekitTicket>()
                .eq(PrekitTicket::getMachineId, m1.getId()));
        assertEquals(PrekitTicket.STATUS_CREATED, t1.getTicketStatus());
        assertTrue(t1.getTicketNo().startsWith("PH-"), "配货单号 PH- 前缀");
        List<PrekitTicketItem> items = itemsOf(t1.getId());
        assertEquals(2, items.size());
        assertEquals(0, items.get(0).getQtyPlanned().compareTo(new BigDecimal("10")));
        assertEquals(0, items.get(1).getQtyPlanned().compareTo(new BigDecimal("5")), "带出量可改:6→5");
        // 建议行状态联动
        assertEquals("已生成配货单", planMapper.selectById(pl1.getId()).getPlanStatus());
        assertEquals("已生成配货单", planMapper.selectById(pl2.getId()).getPlanStatus());
        assertEquals("已生成配货单", planMapper.selectById(pl3.getId()).getPlanStatus());
    }

    @Test
    @DisplayName("生成幂等:同建议行二次生成被拒(状态已不是「建议」);同机同日补勾的新行追加进同一张单")
    void generateIdempotentAndAppendsToExistingTicket() {
        Machine m = machine("PK追加机");
        Product pa = product("百事可乐", null, null);
        Product pb = product("景田水", null, null);
        ReplenishPlan pl1 = plan(m.getId(), pa.getId(), "12");
        prekitService.generate(genReq(pl1), OPERATOR);

        // 同一行再生成 → 拒绝(不会一行进两张单)
        BizException ex = assertThrows(BizException.class,
                () -> prekitService.generate(genReq(pl1), OPERATOR));
        assertTrue(ex.getMessage().contains("已生成配货单"), "报错点名状态:" + ex.getMessage());

        // 新建议行(同机同日)→ 追加进已有「已生成」单,不重复开单
        ReplenishPlan pl2 = plan(m.getId(), pb.getId(), "4");
        PrekitDtos.GenerateResp resp2 = prekitService.generate(genReq(pl2), OPERATOR);
        assertEquals(0, resp2.getTicketCount(), "不开新单");
        assertEquals(1, resp2.getItemCount());
        List<PrekitTicket> tickets = ticketMapper.selectList(new LambdaQueryWrapper<PrekitTicket>()
                .eq(PrekitTicket::getMachineId, m.getId()));
        assertEquals(1, tickets.size(), "同机同日只有一张配货单");
        assertEquals(2, itemsOf(tickets.get(0).getId()).size());
    }

    @Test
    @DisplayName("改量:仅「已生成」可改带出量;执行后冻结报错")
    void updateItemQtyOnlyWhenCreated() {
        Machine m = machine("PK改量机");
        Product p = product("冰红茶", null, null);
        ReplenishPlan pl = plan(m.getId(), p.getId(), "10");
        PrekitDtos.GenerateResp resp = prekitService.generate(genReq(pl), OPERATOR);
        Long ticketId = resp.getTicketIds().get(0);
        PrekitTicketItem item = itemsOf(ticketId).get(0);

        prekitService.updateItemQty(item.getId(), new BigDecimal("8"), OPERATOR);
        assertEquals(0, itemMapper.selectById(item.getId()).getQtyPlanned()
                .compareTo(new BigDecimal("8")));

        prekitService.execute(ticketId, OPERATOR);
        BizException ex = assertThrows(BizException.class,
                () -> prekitService.updateItemQty(item.getId(), new BigDecimal("9"), OPERATOR));
        assertTrue(ex.getMessage().contains("已执行"), "执行后数字冻结:" + ex.getMessage());
    }

    @Test
    @DisplayName("执行:已生成→已执行记执行时间;建议行→已执行;重复执行被状态机拒绝")
    void executeRecordsTimeAndLinksPlanStatus() {
        Machine m = machine("PK执行机");
        Product p = product("乡巴佬鸭腿", null, null);
        ReplenishPlan pl = plan(m.getId(), p.getId(), "6");
        Long ticketId = prekitService.generate(genReq(pl), OPERATOR).getTicketIds().get(0);

        prekitService.execute(ticketId, OPERATOR);
        PrekitTicket ticket = ticketMapper.selectById(ticketId);
        assertEquals(PrekitTicket.STATUS_EXECUTED, ticket.getTicketStatus());
        assertNotNull(ticket.getExecAt(), "执行时间落库");
        assertNotNull(ticket.getExecBy(), "执行人落库");
        assertEquals("已执行", planMapper.selectById(pl.getId()).getPlanStatus(), "建议行联动 → 已执行");

        assertThrows(BizException.class, () -> prekitService.execute(ticketId, OPERATOR), "重复执行拒绝");
    }

    @Test
    @DisplayName("导入核销全配:次日导入量=带出量 → 已核销,带回率 0,qty_loaded 回填,verify_doc_id 指向导入单")
    void importVerifyFullMatchTakebackZero() throws Exception {
        Machine m = machine("PK核销机A");
        Product p = product("东方树叶茉莉", "RG7001001", null);
        alias("RG7001001", "东方树叶茉莉花茶", p.getId());
        stockWarehouse(p.getId(), "40");
        ReplenishPlan pl = plan(m.getId(), p.getId(), "10");
        Long ticketId = prekitService.generate(genReq(pl), OPERATOR).getTicketIds().get(0);
        prekitService.execute(ticketId, OPERATOR);

        // 次日导入后台补货记录:同机器×SKU 上架 10(全配)
        ImportDtos.CommitResp resp = importFile(ImportBatch.TYPE_REPLENISH, repFile(new Object[][]{
                {machineDevice(m), 3, "东方树叶茉莉花茶", "RG7001001", 0, 10, 10, "小邱",
                        LocalDate.now().plusDays(1) + " 09:30:00"},
        }));
        assertEquals(1, resp.getDocsCreated());
        assertEquals(1, resp.getPrekitVerified(), "afterImport 钩子核销 1 张配货单");

        PrekitTicket ticket = ticketMapper.selectById(ticketId);
        assertEquals(PrekitTicket.STATUS_VERIFIED, ticket.getTicketStatus());
        assertEquals(0, ticket.getTakebackRate().compareTo(BigDecimal.ZERO), "全配带回率=0");
        assertNotNull(ticket.getVerifyAt());
        assertNotNull(ticket.getVerifyDocId(), "verify_doc_id 指向匹配转移单");
        assertEquals(DocType.TRANSFER_OUT, docHeadMapper.selectById(ticket.getVerifyDocId()).getDocType());

        PrekitTicketItem item = itemsOf(ticketId).get(0);
        assertEquals(0, item.getQtyLoaded().compareTo(new BigDecimal("10")));
        assertEquals(0, item.getQtyTakeback().compareTo(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("部分核销:带出10 实上架8 → 有差异,带回2,带回率 0.2(PDCA 指标数据源)")
    void importVerifyPartialTakebackRate() throws Exception {
        Machine m = machine("PK核销机B");
        Product p = product("康师傅冰红茶", "RG7002001", null);
        alias("RG7002001", "康师傅冰红茶1L", p.getId());
        stockWarehouse(p.getId(), "40");
        ReplenishPlan pl = plan(m.getId(), p.getId(), "10");
        Long ticketId = prekitService.generate(genReq(pl), OPERATOR).getTicketIds().get(0);
        prekitService.execute(ticketId, OPERATOR);

        ImportDtos.CommitResp resp = importFile(ImportBatch.TYPE_REPLENISH, repFile(new Object[][]{
                {machineDevice(m), 3, "康师傅冰红茶1L", "RG7002001", 0, 8, 8, "小邱",
                        LocalDate.now().plusDays(1) + " 09:35:00"},
        }));
        assertEquals(1, resp.getPrekitVerified());

        PrekitTicket ticket = ticketMapper.selectById(ticketId);
        assertEquals(PrekitTicket.STATUS_DIFF, ticket.getTicketStatus(), "有差量 → 有差异");
        assertEquals(0, ticket.getTakebackRate().compareTo(new BigDecimal("0.2000")),
                "带回率 = 2/10 = 0.2");
        PrekitTicketItem item = itemsOf(ticketId).get(0);
        assertEquals(0, item.getQtyLoaded().compareTo(new BigDecimal("8")));
        assertEquals(0, item.getQtyTakeback().compareTo(new BigDecimal("2")));
    }

    @Test
    @DisplayName("超窗黄灯:配货日 3 天前的未核销单,今日导入不匹配(±48h 窗口外),列表 overdue=true")
    void outOfWindowNotMatchedAndOverdueFlag() throws Exception {
        Machine m = machine("PK超窗机");
        Product p = product("泰奇八宝粥", "RG7003001", null);
        alias("RG7003001", "泰奇八宝粥370g", p.getId());
        stockWarehouse(p.getId(), "40");
        ReplenishPlan pl = plan(m.getId(), p.getId(), "5", LocalDate.now().minusDays(3));
        Long ticketId = prekitService.generate(genReq(pl), OPERATOR).getTicketIds().get(0);

        // 今日导入:配货日与业务日差 3 天 > 48h 窗口 → 不匹配
        ImportDtos.CommitResp resp = importFile(ImportBatch.TYPE_REPLENISH, repFile(new Object[][]{
                {machineDevice(m), 2, "泰奇八宝粥370g", "RG7003001", 0, 5, 5, "小邱",
                        LocalDate.now() + " 10:00:00"},
        }));
        assertEquals(1, resp.getDocsCreated());
        assertEquals(0, resp.getPrekitVerified(), "窗口外不核销");

        PrekitTicket ticket = ticketMapper.selectById(ticketId);
        assertEquals(PrekitTicket.STATUS_CREATED, ticket.getTicketStatus(), "保持未核销");
        assertEquals(Boolean.TRUE, listedRow(ticketId).get("overdue"), "超窗未核销亮黄灯");
    }

    @Test
    @DisplayName("FEFO 提示级:明细行带仓库最早入库日 = 最早正向流水业务日(先出旧货)")
    void fefoEarliestInboundDateOnDetail() {
        Machine m = machine("PK-FEFO机");
        Product p = product("爱乡亲唱片面包", null, null);
        // 两批入库:10 天前 + 2 天前 → FEFO 提示应取 10 天前
        LocalDate oldBatch = LocalDate.now().minusDays(10);
        LocalDate newBatch = LocalDate.now().minusDays(2);
        confirmedDoc(DocType.PURCHASE_IN, null, DocService.SOURCE_MANUAL, oldBatch, false, null,
                new Object[]{p.getId(), "20", "2.0"});
        confirmedDoc(DocType.PURCHASE_IN, null, DocService.SOURCE_MANUAL, newBatch, false, null,
                new Object[]{p.getId(), "20", "2.1"});

        ReplenishPlan pl = plan(m.getId(), p.getId(), "6");
        Long ticketId = prekitService.generate(genReq(pl), OPERATOR).getTicketIds().get(0);

        Map<String, Object> detail = prekitService.ticketDetail(ticketId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) detail.get("items");
        assertEquals(1, items.size());
        assertEquals(oldBatch.toString(), String.valueOf(items.get(0).get("fefoEarliestInDate")),
                "FEFO 提示 = 最早入库日(先出旧货)");
    }

    @Test
    @DisplayName("幂等:同文件重复导入零副作用——不再生成转移单,已核销单数值不变")
    void reimportSameFileKeepsVerificationUnchanged() throws Exception {
        Machine m = machine("PK幂等机");
        Product p = product("海之言", "RG7004001", null);
        alias("RG7004001", "海之言柠檬", p.getId());
        stockWarehouse(p.getId(), "40");
        ReplenishPlan pl = plan(m.getId(), p.getId(), "10");
        Long ticketId = prekitService.generate(genReq(pl), OPERATOR).getTicketIds().get(0);
        prekitService.execute(ticketId, OPERATOR);

        byte[] file = repFile(new Object[][]{
                {machineDevice(m), 1, "海之言柠檬", "RG7004001", 0, 7, 7, "小邱",
                        LocalDate.now().plusDays(1) + " 11:00:00"},
        });
        ImportDtos.CommitResp first = importFile(ImportBatch.TYPE_REPLENISH, file);
        assertEquals(1, first.getDocsCreated());
        assertEquals(1, first.getPrekitVerified());
        PrekitTicket after1 = ticketMapper.selectById(ticketId);
        Long verifyDoc1 = after1.getVerifyDocId();
        BigDecimal rate1 = after1.getTakebackRate();

        ImportDtos.CommitResp second = importFile(ImportBatch.TYPE_REPLENISH, file);
        assertEquals(0, second.getDocsCreated(), "重复行全部去重,不再生成转移单");
        assertEquals(1, second.getRowDup());
        assertEquals(0, second.getPrekitVerified(), "已核销单不重复核销");

        PrekitTicket after2 = ticketMapper.selectById(ticketId);
        assertEquals(after1.getTicketStatus(), after2.getTicketStatus());
        assertEquals(verifyDoc1, after2.getVerifyDocId());
        assertEquals(0, rate1.compareTo(after2.getTakebackRate()), "带回率不变");
        assertEquals(0, itemsOf(ticketId).get(0).getQtyLoaded().compareTo(new BigDecimal("7")));
    }

    @Test
    @DisplayName("P0-1 回归:同机连续两日两张配货单+两批导入 → 1:1 占用各自核销到自己那批,带回率各自正确")
    void dailyShiftTwoTicketsVerifyEachAgainstOwnBatch() throws Exception {
        Machine m = machine("PK连班机");
        Product p = product("班次苏打水", "RG7005001", null);
        alias("RG7005001", "班次苏打水500ml", p.getId());
        stockWarehouse(p.getId(), "60");

        LocalDate d1 = LocalDate.now().minusDays(1); // 第一天班次
        LocalDate d2 = LocalDate.now();              // 第二天班次

        // 第一天:配货单带出 10,次日(d2)导入上架 10 → 全配,带回率 0
        ReplenishPlan pl1 = plan(m.getId(), p.getId(), "10", d1);
        Long ticket1 = prekitService.generate(genReq(pl1), OPERATOR).getTicketIds().get(0);
        prekitService.execute(ticket1, OPERATOR);
        ImportDtos.CommitResp r1 = importFile(ImportBatch.TYPE_REPLENISH, repFile(new Object[][]{
                {machineDevice(m), 1, "班次苏打水500ml", "RG7005001", 0, 10, 10, "小邱",
                        d2 + " 09:00:00"},
        }));
        assertEquals(1, r1.getDocsCreated());
        assertEquals(1, r1.getPrekitVerified(), "第一批导入只核销第一张配货单");

        // 第二天:配货单带出 10,次日(d2+1)导入上架 8 → 有差异,带回 2
        ReplenishPlan pl2 = plan(m.getId(), p.getId(), "10", d2);
        Long ticket2 = prekitService.generate(genReq(pl2), OPERATOR).getTicketIds().get(0);
        prekitService.execute(ticket2, OPERATOR);
        ImportDtos.CommitResp r2 = importFile(ImportBatch.TYPE_REPLENISH, repFile(new Object[][]{
                {machineDevice(m), 1, "班次苏打水500ml", "RG7005001", 0, 8, 8, "小邱",
                        d2.plusDays(1) + " 09:00:00"},
        }));
        assertEquals(1, r2.getDocsCreated());
        assertEquals(1, r2.getPrekitVerified(), "第二批导入只核销第二张配货单(第一张已核销不重算)");

        // 第一张:自己那批(上架10)→ 已核销,带回率 0——不被第二批污染
        PrekitTicket t1 = ticketMapper.selectById(ticket1);
        assertEquals(PrekitTicket.STATUS_VERIFIED, t1.getTicketStatus());
        assertEquals(0, t1.getTakebackRate().compareTo(BigDecimal.ZERO), "第一天全配带回率=0");
        assertEquals(0, itemsOf(ticket1).get(0).getQtyLoaded().compareTo(new BigDecimal("10")),
                "第一张上架量=自己那批的 10,不是两批总和 18");

        // 第二张:自己那批(上架8)→ 有差异,带回 2,带回率 0.2——修复前会拿两批总和 18 算成 0
        PrekitTicket t2 = ticketMapper.selectById(ticket2);
        assertEquals(PrekitTicket.STATUS_DIFF, t2.getTicketStatus(), "第二天少上 2 件必须亮「有差异」");
        assertEquals(0, t2.getTakebackRate().compareTo(new BigDecimal("0.2000")),
                "带回率 = 2/10 = 0.2(窗口重复计数会把它算成 0)");
        assertEquals(0, itemsOf(ticket2).get(0).getQtyLoaded().compareTo(new BigDecimal("8")));
        assertEquals(0, itemsOf(ticket2).get(0).getQtyTakeback().compareTo(new BigDecimal("2")));

        // 1:1 占用:两张配货单各占一张转移单,绝不共享
        assertNotNull(t1.getVerifyDocId());
        assertNotNull(t2.getVerifyDocId());
        assertNotEquals(t1.getVerifyDocId(), t2.getVerifyDocId(),
                "一张转移单只授信一张配货单(verify_doc_id 唯一占用)");
    }

    @Test
    @DisplayName("手工转移单兜底:出库上架确认后=预挂单只锁仓库侧(P0-4 不破),成本=全期加权")
    void manualTransferEntryBecomesPrePending() {
        Machine m = machine("PK手工机");
        Product p = product("红牛", null, null);
        stockWarehouse(p.getId(), "20");

        PrekitDtos.ManualTransferReq req = new PrekitDtos.ManualTransferReq();
        req.setDocType("出库上架");
        req.setMachineId(m.getId());
        req.setBizDate(LocalDate.now());
        PrekitDtos.ManualTransferItem item = new PrekitDtos.ManualTransferItem();
        item.setProductId(p.getId());
        item.setQty(new BigDecimal("6"));
        req.setItems(java.util.Collections.singletonList(item));

        Map<String, Object> result = prekitService.createManualTransfer(req, OPERATOR);
        assertEquals(DocStatus.PRE_PENDING.getLabel(), result.get("docStatus"), "手工出库上架 → 预挂单");
        assertEquals(0, stockService.getWarehouseStock(p.getId()).compareTo(new BigDecimal("14")),
                "预挂单锁仓库侧 20-6=14");
        assertEquals(0, stockService.getMachineStock(m.getId(), p.getId()).compareTo(BigDecimal.ZERO),
                "预挂单不写机器账");
    }

    /** 取机器的后台设备ID(导入通道锚点) */
    private String machineDevice(Machine m) {
        return machineMapper.selectById(m.getId()).getDeviceId();
    }
}
