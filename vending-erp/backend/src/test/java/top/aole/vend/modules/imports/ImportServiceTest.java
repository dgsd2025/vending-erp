package top.aole.vend.modules.imports;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.application.AliasService;
import top.aole.vend.modules.basedata.domain.entity.AliasPending;
import top.aole.vend.modules.basedata.domain.entity.Machine;
import top.aole.vend.modules.basedata.domain.entity.PriceLog;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.basedata.domain.entity.SkuAlias;
import top.aole.vend.modules.basedata.infrastructure.mapper.AliasPendingMapper;
import top.aole.vend.modules.basedata.infrastructure.mapper.MachineMapper;
import top.aole.vend.modules.basedata.infrastructure.mapper.PriceLogMapper;
import top.aole.vend.modules.basedata.infrastructure.mapper.ProductMapper;
import top.aole.vend.modules.basedata.infrastructure.mapper.SkuAliasMapper;
import top.aole.vend.modules.doc.domain.entity.DocHead;
import top.aole.vend.modules.doc.domain.enums.DocStatus;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.dto.DocCreateReq;
import top.aole.vend.modules.doc.dto.DocItemReq;
import top.aole.vend.modules.doc.mapper.DocHeadMapper;
import top.aole.vend.modules.doc.service.DocService;
import top.aole.vend.modules.imports.domain.entity.ImportBatch;
import top.aole.vend.modules.imports.dto.ImportDtos;
import top.aole.vend.modules.imports.mapper.ImportBatchMapper;
import top.aole.vend.modules.imports.service.ImportService;
import top.aole.vend.modules.stock.domain.entity.SaleRecord;
import top.aole.vend.modules.stock.domain.entity.StockLedger;
import top.aole.vend.modules.stock.mapper.SaleRecordMapper;
import top.aole.vend.modules.stock.mapper.StockLedgerMapper;
import top.aole.vend.modules.stock.service.StockService;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-3 导入中心集成测试(vend_test_imports 独立库,Flyway 自迁,每例事务回滚)。
 * 覆盖验收清单:去重幂等 / 别名兜底进待绑定 / 绑定后重处理回补 / 负数补货逆向 /
 * 通道2防重 / 预挂单冲抵 / 负库存豁免红灯 / 整批回滚(两通道)/ 通道3绑别名 / 改价侦测。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test-imports")
@Transactional
class ImportServiceTest {

    private static final String OP = "测试员";

    @Autowired
    ImportService importService;
    @Autowired
    AliasService aliasService;
    @Autowired
    DocService docService;
    @Autowired
    StockService stockService;
    @Autowired
    ProductMapper productMapper;
    @Autowired
    MachineMapper machineMapper;
    @Autowired
    SkuAliasMapper skuAliasMapper;
    @Autowired
    AliasPendingMapper aliasPendingMapper;
    @Autowired
    SaleRecordMapper saleRecordMapper;
    @Autowired
    DocHeadMapper docHeadMapper;
    @Autowired
    StockLedgerMapper stockLedgerMapper;
    @Autowired
    ImportBatchMapper importBatchMapper;
    @Autowired
    PriceLogMapper priceLogMapper;
    @Autowired
    top.aole.vend.modules.basedata.infrastructure.mapper.SupplierMapper supplierMapper;
    @Autowired
    top.aole.vend.modules.settle.mapper.SettleBillMapper settleBillMapper;
    @Autowired
    top.aole.vend.modules.settle.service.SettleBillService settleBillService;

    // ============================== 造数工具 ==============================

    private Product product(String name, String barcode, String refPrice) {
        Product p = new Product();
        p.setSkuCode("T" + IdUtil.fastSimpleUUID().substring(0, 8).toUpperCase());
        p.setProductName(name);
        p.setBarcode(barcode);
        p.setUnit("瓶");
        if (refPrice != null) {
            p.setRefPrice(new BigDecimal(refPrice));
        }
        productMapper.insert(p);
        return p;
    }

    private Machine machine(String deviceId, String name) {
        Machine m = new Machine();
        m.setMachineCode("VM-" + IdUtil.fastSimpleUUID().substring(0, 6).toUpperCase());
        m.setMachineName(name);
        m.setDeviceId(deviceId);
        m.setMachineStatus("在线");
        machineMapper.insert(m);
        return m;
    }

    private void alias(String code, String barcode, String name, Long productId) {
        SkuAlias a = new SkuAlias();
        a.setAliasCode(code == null ? "" : code);
        a.setAliasBarcode(barcode == null ? "" : barcode);
        a.setAliasName(name);
        a.setProductId(productId);
        a.setBindSource("人工");
        skuAliasMapper.insert(a);
    }

    /** 仓库垫底:确认一张采购入库单 */
    private void stockWarehouse(Long productId, String qty, String unitPrice) {
        DocCreateReq req = new DocCreateReq();
        req.setDocType(DocType.PURCHASE_IN);
        req.setBizDate(LocalDate.of(2026, 6, 1));
        DocItemReq item = new DocItemReq();
        item.setProductId(productId);
        item.setQty(new BigDecimal(qty));
        item.setUnitPrice(new BigDecimal(unitPrice));
        List<DocItemReq> items = new ArrayList<>();
        items.add(item);
        req.setItems(items);
        Long id = docService.createDoc(req, 9L);
        docService.submit(id, 9L);
        docService.confirm(id, 9L, false, null);
    }

    /** 内存里造一个 xlsx(第一行表头) */
    private byte[] xlsx(Object[][] rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("data");
            for (int i = 0; i < rows.length; i++) {
                Row row = sheet.createRow(i);
                for (int j = 0; j < rows[i].length; j++) {
                    Object v = rows[i][j];
                    if (v == null) {
                        continue;
                    }
                    if (v instanceof Number) {
                        row.createCell(j).setCellValue(((Number) v).doubleValue());
                    } else {
                        row.createCell(j).setCellValue(v.toString());
                    }
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    private ImportDtos.CommitResp importFile(String fileType, byte[] content) {
        ImportDtos.PreviewResp preview = importService.upload(fileType, "test-" + fileType + ".xlsx", content);
        assertTrue(preview.isColumnsOk(), "列映射校验应通过:" + preview.getWarnings());
        return importService.confirm(preview.getToken(), OP);
    }

    private static final String[] SALE_HEADER = {
            "订单号", "商品名称", "商品条形码", "出货数量", "货道号", "设备ID", "商品金额(元)", "订单类型", "支付方式", "出货时间"};

    private byte[] saleFile(Object[][] dataRows) throws Exception {
        Object[][] all = new Object[dataRows.length + 1][];
        all[0] = SALE_HEADER;
        System.arraycopy(dataRows, 0, all, 1, dataRows.length);
        return xlsx(all);
    }

    private static final String[] REP_HEADER = {
            "设备ID", "货道号", "商品名称", "商品条形码", "补货前库存", "本次补货数", "补货后库存", "补货人", "补货时间"};

    private byte[] repFile(Object[][] dataRows) throws Exception {
        Object[][] all = new Object[dataRows.length + 1][];
        all[0] = REP_HEADER;
        System.arraycopy(dataRows, 0, all, 1, dataRows.length);
        return xlsx(all);
    }

    // ============================== 通道1:出货明细 ==============================

    @Test
    void sale_import_then_reimport_isIdempotent() throws Exception {
        Machine m = machine("DEV-A1", "1楼售卖机");
        Product p = product("东鹏特饮", "6951234567890", null);
        alias(null, "6951234567890", "东鹏特饮500ml", p.getId());

        // 一单多行:DD002 两行(同订单号) → 确定性后缀,不撞唯一键
        byte[] file = saleFile(new Object[][]{
                {"DD001", "东鹏特饮500ml", "6951234567890", 1, 10, "DEV-A1", 5.0, "正常订单", "微信", "2026-07-01 09:00:00"},
                {"DD002", "东鹏特饮500ml", "6951234567890", 1, 10, "DEV-A1", 5.0, "正常订单", "微信", "2026-07-01 10:00:00"},
                {"DD002", "东鹏特饮500ml", "6951234567890", 2, 11, "DEV-A1", 10.0, "正常订单", "微信", "2026-07-01 10:00:00"},
        });
        ImportDtos.CommitResp first = importFile(ImportBatch.TYPE_SALE, file);
        assertEquals(3, first.getRowOk());
        assertEquals(0, first.getRowDup());
        assertEquals(0, first.getRowFail());

        List<SaleRecord> records = saleRecordMapper.selectList(new LambdaQueryWrapper<SaleRecord>()
                .eq(SaleRecord::getImportBatchId, first.getBatchId()));
        assertEquals(3, records.size());
        BigDecimal sum = records.stream().map(SaleRecord::getAmountReceived)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, new BigDecimal("20.00").compareTo(sum));
        assertTrue(records.stream().allMatch(r -> p.getId().equals(r.getProductId())), "别名条码应全部命中");
        assertTrue(records.stream().anyMatch(r -> "DD002#2".equals(r.getOrderNo())), "一单多行第2行应带确定性后缀");

        // 重复导入零副作用
        ImportDtos.CommitResp second = importFile(ImportBatch.TYPE_SALE, file);
        assertEquals(0, second.getRowOk());
        assertEquals(3, second.getRowDup());
        // 只数本机器的记录:库里可能有真实全量导入留下的数据(vend_test_imports 与真测共库)
        long total = saleRecordMapper.selectCount(new LambdaQueryWrapper<SaleRecord>()
                .eq(SaleRecord::getMachineId, m.getId()));
        assertEquals(3, total);
    }

    @Test
    void sale_unknownAlias_goesPending_thenReprocessRebinds() throws Exception {
        machine("DEV-A2", "4楼售卖机");
        Product p = product("茶派蜜桃乌龙", null, null);

        byte[] file = saleFile(new Object[][]{
                {"DD101", "茶派蜜桃乌龙茶500ml", null, 1, 5, "DEV-A2", 4.0, "正常订单", "微信", "2026-07-02 12:00:00"},
        });
        ImportDtos.CommitResp resp = importFile(ImportBatch.TYPE_SALE, file);
        assertEquals(1, resp.getRowOk());
        assertEquals(1, resp.getPendingBind());

        SaleRecord record = saleRecordMapper.selectList(new LambdaQueryWrapper<SaleRecord>()
                .eq(SaleRecord::getImportBatchId, resp.getBatchId())).get(0);
        assertNull(record.getProductId(), "未绑定行照常入库但 product_id 为空");

        AliasPending pending = aliasPendingMapper.selectOne(new LambdaQueryWrapper<AliasPending>()
                .eq(AliasPending::getAliasName, "茶派蜜桃乌龙茶500ml"));
        assertNotNull(pending, "应进 alias_pending 队列");
        assertEquals("待绑定", pending.getPendingStatus());

        // 人工确认绑定 → 重处理该批 → 回补 product_id
        aliasService.confirmPending(pending.getId(), p.getId(), OP);
        ImportDtos.ReprocessResp re = importService.reprocessPending(resp.getBatchId(), OP);
        assertEquals(1, re.getScanned());
        assertEquals(1, re.getRebound());
        SaleRecord after = saleRecordMapper.selectById(record.getId());
        assertEquals(p.getId(), after.getProductId());
    }

    @Test
    void sale_orderTypeNormalized() throws Exception {
        Machine m = machine("DEV-A3", "3楼售卖机");
        Product p = product("矿泉水", "690001", null);
        alias(null, "690001", "景田纯净水", p.getId());
        byte[] file = saleFile(new Object[][]{
                {"DD201", "景田纯净水", "690001", 1, 1, "DEV-A3", 2.0, "正常订单", "微信", "2026-07-03 08:00:00"},
                {"DD201", "景田纯净水", "690001", 1, 1, "DEV-A3", -2.0, "退款订单", "微信", "2026-07-03 08:30:00"},
                {"DD202", "景田纯净水", "690001", 1, 1, "DEV-A3", 0, "测试订单", "微信", "2026-07-03 09:00:00"},
        });
        ImportDtos.CommitResp resp = importFile(ImportBatch.TYPE_SALE, file);
        assertEquals(3, resp.getRowOk());
        // 退款与原单同订单号:uk(order_no, order_type) 不撞(主窗口裁决)
        List<SaleRecord> records = saleRecordMapper.selectList(new LambdaQueryWrapper<SaleRecord>()
                .eq(SaleRecord::getImportBatchId, resp.getBatchId()));
        assertEquals(3, records.size());
        assertTrue(records.stream().anyMatch(r -> "退款".equals(r.getOrderType())));
        assertTrue(records.stream().anyMatch(r -> "测试".equals(r.getOrderType())));
        // 机器库存出货口径 = 正常+兑换,不含退款/测试
        assertEquals(0, new BigDecimal("-1").compareTo(stockService.getMachineStock(m.getId(), p.getId())));
    }

    // ============================== 通道2:系统补货记录 ==============================

    @Test
    void replenish_forward_createsConfirmedTransferDoc_withRealBizTime() throws Exception {
        Machine m = machine("DEV-B1", "1楼售卖机");
        Product p = product("可乐", "690100", null);
        alias(null, "690100", "可口可乐500ml", p.getId());
        stockWarehouse(p.getId(), "50", "2.0");

        byte[] file = repFile(new Object[][]{
                {"DEV-B1", 10, "可口可乐500ml", "690100", 2, 8, 10, "小邱", "2026-07-05 16:57:23"},
        });
        ImportDtos.CommitResp resp = importFile(ImportBatch.TYPE_REPLENISH, file);
        assertEquals(1, resp.getRowOk());
        assertEquals(1, resp.getDocsCreated());
        assertEquals(1, resp.getSnapshots());

        DocHead doc = docHeadMapper.selectList(new LambdaQueryWrapper<DocHead>()
                .eq(DocHead::getImportBatchId, resp.getBatchId())).get(0);
        assertEquals(DocType.TRANSFER_OUT, doc.getDocType());
        assertEquals(DocStatus.CONFIRMED, doc.getDocStatus());
        assertEquals(DocService.SOURCE_IMPORT, doc.getDocSource());
        assertTrue(Boolean.TRUE.equals(doc.getNegStockExempt()), "导入转移单应带负库存豁免");

        // 库存流水业务时间戳 = 补货记录真实时间(千万别是 00:00)
        List<StockLedger> ledgers = stockLedgerMapper.selectList(new LambdaQueryWrapper<StockLedger>()
                .eq(StockLedger::getDocId, doc.getId()));
        assertEquals(2, ledgers.size(), "仓库-/机器+ 各一条");
        for (StockLedger l : ledgers) {
            assertEquals(LocalDateTime.of(2026, 7, 5, 16, 57, 23), l.getBizTime());
        }
        // 仓库 50-8=42,机器锚点快照(10)为准
        assertEquals(0, new BigDecimal("42").compareTo(stockService.getWarehouseStock(p.getId())));
        assertEquals(0, new BigDecimal("10").compareTo(stockService.getMachineStock(m.getId(), p.getId())));
    }

    @Test
    void replenish_negativeQty_createsReverseDoc() throws Exception {
        Machine m = machine("DEV-B2", "4楼售卖机");
        Product p = product("临期面包", "690200", null);
        alias(null, "690200", "爱乡亲面包", p.getId());
        stockWarehouse(p.getId(), "20", "3.0");
        // 先上架 6
        importFile(ImportBatch.TYPE_REPLENISH, repFile(new Object[][]{
                {"DEV-B2", 3, "爱乡亲面包", "690200", 0, 6, 6, "小邱", "2026-07-06 09:00:00"},
        }));
        // 负数补货 = 取出调整 → 逆向转移(机器→仓库)
        ImportDtos.CommitResp resp = importFile(ImportBatch.TYPE_REPLENISH, repFile(new Object[][]{
                {"DEV-B2", 3, "爱乡亲面包", "690200", 6, -4, 2, "小邱", "2026-07-07 09:30:00"},
        }));
        assertEquals(1, resp.getDocsCreated());
        DocHead reverse = docHeadMapper.selectList(new LambdaQueryWrapper<DocHead>()
                        .eq(DocHead::getImportBatchId, resp.getBatchId())).get(0);
        assertEquals(DocType.RETURN_BACK, reverse.getDocType());
        assertEquals(DocStatus.CONFIRMED, reverse.getDocStatus());
        // 仓库 20-6+4=18;机器快照锚点 2
        assertEquals(0, new BigDecimal("18").compareTo(stockService.getWarehouseStock(p.getId())));
        assertEquals(0, new BigDecimal("2").compareTo(stockService.getMachineStock(m.getId(), p.getId())));
    }

    @Test
    void replenish_reimport_isIdempotent() throws Exception {
        machine("DEV-B3", "7楼售卖机");
        Product p = product("八宝粥", "690300", null);
        alias(null, "690300", "泰奇八宝粥", p.getId());
        stockWarehouse(p.getId(), "30", "4.0");
        byte[] file = repFile(new Object[][]{
                {"DEV-B3", 5, "泰奇八宝粥", "690300", 1, 5, 6, "小邱", "2026-07-08 15:00:00"},
        });
        ImportDtos.CommitResp first = importFile(ImportBatch.TYPE_REPLENISH, file);
        assertEquals(1, first.getDocsCreated());
        ImportDtos.CommitResp second = importFile(ImportBatch.TYPE_REPLENISH, file);
        assertEquals(0, second.getDocsCreated());
        assertEquals(1, second.getRowDup());
        assertEquals(0, new BigDecimal("25").compareTo(stockService.getWarehouseStock(p.getId())),
                "重复导入不能重复扣仓库");
    }

    @Test
    void replenish_offsetsManualPrePendingTransfer() throws Exception {
        Machine m = machine("DEV-B4", "2楼售卖机");
        Product p = product("雪碧", "690400", null);
        alias(null, "690400", "雪碧500ml", p.getId());
        stockWarehouse(p.getId(), "40", "2.5");

        // 手工出库上架单确认 → 预挂单(只锁仓库侧)
        DocCreateReq manual = new DocCreateReq();
        manual.setDocType(DocType.TRANSFER_OUT);
        manual.setBizDate(LocalDate.of(2026, 7, 9));
        manual.setMachineId(m.getId());
        DocItemReq item = new DocItemReq();
        item.setProductId(p.getId());
        item.setQty(new BigDecimal("10"));
        item.setUnitPrice(new BigDecimal("2.5"));
        List<DocItemReq> items = new ArrayList<>();
        items.add(item);
        manual.setItems(items);
        Long manualId = docService.createDoc(manual, 9L);
        docService.submit(manualId, 9L);
        docService.confirm(manualId, 9L, false, null);
        assertEquals(DocStatus.PRE_PENDING, docHeadMapper.selectById(manualId).getDocStatus());
        assertEquals(0, new BigDecimal("30").compareTo(stockService.getWarehouseStock(p.getId())));

        // 导入同机器+SKU+当日的后台补货记录 → 冲抵
        ImportDtos.CommitResp resp = importFile(ImportBatch.TYPE_REPLENISH, repFile(new Object[][]{
                {"DEV-B4", 8, "雪碧500ml", "690400", 0, 10, 10, "小邱", "2026-07-09 16:30:00"},
        }));
        assertEquals(1, resp.getMatchedPrePending());
        DocHead manualAfter = docHeadMapper.selectById(manualId);
        assertEquals(DocStatus.VOID, manualAfter.getDocStatus());
        assertNotNull(manualAfter.getMatchedDocId());
        // 不双扣:预挂单锁的 10 已释放,只剩导入单扣的 10 → 40-10=30
        assertEquals(0, new BigDecimal("30").compareTo(stockService.getWarehouseStock(p.getId())));
    }

    @Test
    void replenish_warehouseShortage_exemptAndRedFlag() throws Exception {
        machine("DEV-B5", "5楼售卖机");
        Product p = product("新品咖啡", "690500", null);
        alias(null, "690500", "罗伯克咖啡", p.getId());
        // 仓库无货直接补机器(后台先卖新品,穿行场景7)→ 豁免拦截 + 待补录采购红灯
        ImportDtos.CommitResp resp = importFile(ImportBatch.TYPE_REPLENISH, repFile(new Object[][]{
                {"DEV-B5", 7, "罗伯克咖啡", "690500", 0, 5, 5, "小邱", "2026-07-10 11:00:00"},
        }));
        assertEquals(1, resp.getDocsCreated());
        assertEquals(1, resp.getNegativeStock().size(), "应亮负库存红灯(待补录采购)");
        assertEquals(0, new BigDecimal("-5").compareTo(resp.getNegativeStock().get(0).getBalance()));
    }

    @Test
    void replenish_unknownAlias_rowFails_intoPendingQueue() throws Exception {
        machine("DEV-B6", "6楼售卖机");
        ImportDtos.CommitResp resp = importFile(ImportBatch.TYPE_REPLENISH, repFile(new Object[][]{
                {"DEV-B6", 2, "没见过的新商品", "699999", 0, 3, 3, "小邱", "2026-07-11 10:00:00"},
        }));
        assertEquals(0, resp.getDocsCreated());
        assertEquals(1, resp.getRowFail());
        assertEquals(1, resp.getPendingBind());
        AliasPending pending = aliasPendingMapper.selectOne(new LambdaQueryWrapper<AliasPending>()
                .eq(AliasPending::getAliasBarcode, "699999"));
        assertNotNull(pending);
    }

    // ============================== 整批回滚 ==============================

    @Test
    void rollback_saleBatch_removesRecords_andSecondRollbackRejected() throws Exception {
        machine("DEV-C1", "1楼售卖机");
        Product p = product("和其正", "690600", null);
        alias(null, "690600", "达利园和其正600l", p.getId());
        byte[] file = saleFile(new Object[][]{
                {"DD301", "达利园和其正600l", "690600", 1, 2, "DEV-C1", 3.0, "正常订单", "微信", "2026-07-12 13:00:00"},
                {"DD302", "达利园和其正600l", "690600", 1, 2, "DEV-C1", 3.0, "正常订单", "微信", "2026-07-12 14:00:00"},
        });
        ImportDtos.CommitResp resp = importFile(ImportBatch.TYPE_SALE, file);
        ImportDtos.RollbackResp rollback = importService.rollback(resp.getBatchId(), OP);
        assertTrue(rollback.isSuccess());
        assertEquals(2, rollback.getSaleRemoved());
        assertEquals(0, saleRecordMapper.selectCount(new LambdaQueryWrapper<SaleRecord>()
                .eq(SaleRecord::getImportBatchId, resp.getBatchId())));
        assertEquals(ImportBatch.STATUS_ROLLED_BACK,
                importBatchMapper.selectById(resp.getBatchId()).getBatchStatus());
        // 已回滚批次不能再回滚
        assertThrows(BizException.class, () -> importService.rollback(resp.getBatchId(), OP));

        // 回滚后重新导入不被 uk 挡(物理删干净)
        ImportDtos.CommitResp again = importFile(ImportBatch.TYPE_SALE, file);
        assertEquals(2, again.getRowOk());
    }

    @Test
    void rollback_replenishBatch_reversesLedgerAndSnapshots() throws Exception {
        Machine m = machine("DEV-C2", "4楼售卖机");
        Product p = product("红牛", "690700", null);
        alias(null, "690700", "红牛250ml", p.getId());
        stockWarehouse(p.getId(), "60", "5.0");
        ImportDtos.CommitResp resp = importFile(ImportBatch.TYPE_REPLENISH, repFile(new Object[][]{
                {"DEV-C2", 1, "红牛250ml", "690700", 0, 12, 12, "小邱", "2026-07-13 17:00:00"},
        }));
        assertEquals(0, new BigDecimal("48").compareTo(stockService.getWarehouseStock(p.getId())));

        ImportDtos.RollbackResp rollback = importService.rollback(resp.getBatchId(), OP);
        assertTrue(rollback.isSuccess());
        assertEquals(1, rollback.getDocsVoided());
        assertTrue(rollback.getLedgerRemoved() >= 2);
        assertEquals(1, rollback.getSnapshotRemoved());
        // 仓库回到 60,机器回到 0,单据已作废
        assertEquals(0, new BigDecimal("60").compareTo(stockService.getWarehouseStock(p.getId())));
        assertEquals(0, BigDecimal.ZERO.compareTo(stockService.getMachineStock(m.getId(), p.getId())));
        DocHead doc = docHeadMapper.selectList(new LambdaQueryWrapper<DocHead>()
                .eq(DocHead::getImportBatchId, resp.getBatchId())).get(0);
        assertEquals(DocStatus.VOID, doc.getDocStatus());
    }

    @Test
    void rollback_replenishBatch_blockedWhenPrePendingMatched() throws Exception {
        Machine m = machine("DEV-C3", "2楼售卖机");
        Product p = product("脉动", "690800", null);
        alias(null, "690800", "脉动青柠600ml", p.getId());
        stockWarehouse(p.getId(), "30", "3.0");
        // 手工预挂单
        DocCreateReq manual = new DocCreateReq();
        manual.setDocType(DocType.TRANSFER_OUT);
        manual.setBizDate(LocalDate.of(2026, 7, 14));
        manual.setMachineId(m.getId());
        DocItemReq item = new DocItemReq();
        item.setProductId(p.getId());
        item.setQty(new BigDecimal("5"));
        item.setUnitPrice(new BigDecimal("3.0"));
        List<DocItemReq> items = new ArrayList<>();
        items.add(item);
        manual.setItems(items);
        Long manualId = docService.createDoc(manual, 9L);
        docService.submit(manualId, 9L);
        docService.confirm(manualId, 9L, false, null);
        // 导入冲抵
        ImportDtos.CommitResp resp = importFile(ImportBatch.TYPE_REPLENISH, repFile(new Object[][]{
                {"DEV-C3", 4, "脉动青柠600ml", "690800", 0, 5, 5, "小邱", "2026-07-14 12:00:00"},
        }));
        assertEquals(1, resp.getMatchedPrePending());
        // 已冲抵预挂单 = 下游引用 → 拒绝回滚并列出引用
        ImportDtos.RollbackResp rollback = importService.rollback(resp.getBatchId(), OP);
        assertFalse(rollback.isSuccess());
        assertFalse(rollback.getBlockers().isEmpty());
        assertEquals(ImportBatch.STATUS_IMPORTED,
                importBatchMapper.selectById(resp.getBatchId()).getBatchStatus());
    }

    // ============================== 通道3:商品列表 ==============================

    @Test
    void productList_bindsByBarcode_unmatchedGoesPending() throws Exception {
        Product p = product("旺仔牛奶", "6901111", null);
        byte[] file = xlsx(new Object[][]{
                {"商品编号", "商品条形码", "商品名称", "售价", "商品分类"},
                {"G001", "6901111", "旺仔牛奶245ml", 4.5, "饮料"},
                {"G002", "6902222", "后台新商品", 3.0, "零食"},
        });
        ImportDtos.CommitResp resp = importFile(ImportBatch.TYPE_PRODUCT_LIST, file);
        assertEquals(2, resp.getRowOk());
        assertEquals(1, resp.getPendingBind());

        SkuAlias bound = skuAliasMapper.selectOne(new LambdaQueryWrapper<SkuAlias>()
                .eq(SkuAlias::getAliasCode, "G001"));
        assertNotNull(bound, "条码挂上 SKU → 写 sku_alias");
        assertEquals(p.getId(), bound.getProductId());
        assertEquals("商品列表导入", bound.getBindSource());

        AliasPending pending = aliasPendingMapper.selectOne(new LambdaQueryWrapper<AliasPending>()
                .eq(AliasPending::getAliasCode, "G002"));
        assertNotNull(pending, "挂不上的进 alias_pending");
    }

    // ============================== 改价侦测 ==============================

    @Test
    void priceChange_detected_thenConfirmed_updatesProductAndWritesPriceLog() throws Exception {
        machine("DEV-D1", "1楼售卖机");
        Product p = product("雪碧", "690900", "3.5");
        alias(null, "690900", "雪碧500ml", p.getId());
        ImportDtos.CommitResp resp = importFile(ImportBatch.TYPE_SALE, saleFile(new Object[][]{
                {"DD401", "雪碧500ml", "690900", 1, 3, "DEV-D1", 3.0, "正常订单", "微信", "2026-07-15 10:00:00"},
        }));
        assertEquals(1, resp.getPriceChangeCount());
        List<ImportDtos.PriceChange> changes = importService.listPriceChanges(resp.getBatchId());
        assertEquals(1, changes.size());
        assertEquals(0, new BigDecimal("3.5").compareTo(changes.get(0).getRefPrice()));
        assertEquals(0, new BigDecimal("3").compareTo(changes.get(0).getNewPrice().stripTrailingZeros()));

        ImportDtos.PriceConfirmReq confirmReq = new ImportDtos.PriceConfirmReq();
        ImportDtos.PriceConfirmReq.Item item = new ImportDtos.PriceConfirmReq.Item();
        item.setProductId(p.getId());
        item.setNewPrice(new BigDecimal("3.0"));
        List<ImportDtos.PriceConfirmReq.Item> confirmItems = new ArrayList<>();
        confirmItems.add(item);
        confirmReq.setItems(confirmItems);
        assertEquals(1, importService.confirmPriceChanges(resp.getBatchId(), confirmReq, OP));

        Product after = productMapper.selectById(p.getId());
        assertEquals(0, new BigDecimal("3.0").compareTo(after.getRefPrice()));
        PriceLog log = priceLogMapper.selectOne(new LambdaQueryWrapper<PriceLog>()
                .eq(PriceLog::getImportBatchId, resp.getBatchId()));
        assertNotNull(log, "应写 price_log(change_source=导入侦测)");
        assertEquals("导入侦测", log.getChangeSource());
    }

    // ============================== P0-B 路径穿越回归 ==============================

    @Test
    void confirm_maliciousFileNameWithDotDot_archiveStaysInsideBatchDir() throws Exception {
        machine("DEV-SEC1", "安全测试机");
        Product p = product("安全测试水", "690777", null);
        alias(null, "690777", "安全测试水500ml", p.getId());
        byte[] file = saleFile(new Object[][]{
                {"SEC001", "安全测试水500ml", "690777", 1, 1, "DEV-SEC1", 2.0, "正常订单", "微信", "2026-07-09 09:00:00"},
        });

        // 文件名带 ../../../:修复前会把文件搬到存储目录外(可覆盖任意可写文件)
        ImportDtos.PreviewResp preview = importService.upload(
                ImportBatch.TYPE_SALE, "../../../evil.xlsx", file);
        ImportDtos.CommitResp resp = importService.confirm(preview.getToken(), OP);
        assertEquals(1, resp.getRowOk());

        ImportBatch batch = importBatchMapper.selectById(resp.getBatchId());
        File storageRoot = new File("target/test-import-storage").getCanonicalFile();
        File archive = new File(batch.getArchivePath()).getCanonicalFile();
        // ① 归档必须落在 存储根/{batchId}/ 之内
        File batchDir = new File(storageRoot, String.valueOf(batch.getId())).getCanonicalFile();
        assertEquals(batchDir, archive.getParentFile(), "归档必须落在批次目录内:" + archive);
        assertTrue(archive.exists(), "归档文件必须真实存在");
        // ② 归档名 = 服务端 batchNo + 固定后缀,与客户端文件名完全无关
        assertEquals(batch.getBatchNo() + ".xlsx", archive.getName());
        // ③ 原始文件名剥掉路径后只存 DB 字段
        assertEquals("evil.xlsx", batch.getFileName());
        // ④ 存储目录外(修复前的落点 = 项目根)不许出现逃逸文件
        assertFalse(new File(storageRoot.getParentFile().getParentFile(), "evil.xlsx").exists(),
                "存储目录之外绝不许出现上传文件(路径穿越)");
    }

    // ============================== M3-9 P0-3:整批回滚 × 应付链 ==============================

    /** 造一个"已导入"采购批次 + 一张带供应商的采购入库单(确认后自动生成结算单),返回 [batchId, docId] */
    private Long[] purchaseBatchWithSettleBill() {
        ImportBatch batch = new ImportBatch();
        batch.setBatchNo("TB" + IdUtil.fastSimpleUUID().substring(0, 10).toUpperCase());
        batch.setFileName("期初采购.xlsx");
        batch.setFileType(ImportBatch.TYPE_INITIAL_PURCHASE);
        batch.setBatchStatus(ImportBatch.STATUS_IMPORTED);
        importBatchMapper.insert(batch);

        top.aole.vend.modules.basedata.domain.entity.Supplier supplier =
                new top.aole.vend.modules.basedata.domain.entity.Supplier();
        supplier.setSupplierCode("TS" + IdUtil.fastSimpleUUID().substring(0, 8).toUpperCase());
        supplier.setSupplierName("回滚测试供应商");
        supplier.setCoopStatus("合作中");
        supplierMapper.insert(supplier);

        Product p = product("回滚测试商品" + IdUtil.fastSimpleUUID().substring(0, 6), null, null);
        DocCreateReq req = new DocCreateReq();
        req.setDocType(DocType.PURCHASE_IN);
        req.setBizDate(LocalDate.now());
        req.setSupplierId(supplier.getId());
        req.setImportBatchId(batch.getId());
        DocItemReq item = new DocItemReq();
        item.setProductId(p.getId());
        item.setQty(new BigDecimal("100"));
        item.setUnitPrice(new BigDecimal("3.5"));
        req.setItems(java.util.Collections.singletonList(item));
        Long docId = docService.createDoc(req, 9L);
        docService.submit(docId, 9L);
        docService.confirm(docId, 9L, false, null); // 触发结算单自动生成(待确认)
        return new Long[]{batch.getId(), docId};
    }

    private top.aole.vend.modules.settle.domain.entity.SettleBill billOfSource(Long docId) {
        return settleBillMapper.selectOne(
                new LambdaQueryWrapper<top.aole.vend.modules.settle.domain.entity.SettleBill>()
                        .eq(top.aole.vend.modules.settle.domain.entity.SettleBill::getSourceDocId, docId)
                        .eq(top.aole.vend.modules.settle.domain.entity.SettleBill::getDirection, "正常")
                        .last("LIMIT 1"));
    }

    @Test
    void rollback_purchaseBatch_voidsPendingSettleBill() {
        // 分支1(P0-3):结算单仅[待确认]且无付款 → 回滚放行,同事务作废结算单
        Long[] ids = purchaseBatchWithSettleBill();
        top.aole.vend.modules.settle.domain.entity.SettleBill bill = billOfSource(ids[1]);
        assertNotNull(bill, "采购确认自动生成了结算单(应付链存在)");
        assertEquals("待确认", bill.getBillStatus());

        ImportDtos.RollbackResp resp = importService.rollback(ids[0], OP);
        assertTrue(resp.isSuccess(), "待确认无付款 → 允许回滚:" + resp.getBlockers());
        assertEquals(1, resp.getSettleBillsVoided(), "应付链连锁:结算单随回滚作废");
        assertEquals("已作废", settleBillMapper.selectById(bill.getId()).getBillStatus(),
                "结算单不再悬空存活(悬空=可为不存在的货付钱)");
        assertEquals(DocStatus.VOID, docHeadMapper.selectById(ids[1]).getDocStatus());
    }

    @Test
    void rollback_purchaseBatch_blockedWhenBillEnteredPayableFlow() {
        // 分支2(P0-3):结算单已进应付流程(老板复核→待付款,更别说已付款)→ 拒绝回滚,指引红冲连锁
        Long[] ids = purchaseBatchWithSettleBill();
        top.aole.vend.modules.settle.domain.entity.SettleBill bill = billOfSource(ids[1]);
        settleBillService.confirm(bill.getId(), null, 9L, OP, "老板"); // 待确认 → 待付款

        ImportDtos.RollbackResp resp = importService.rollback(ids[0], OP);
        assertFalse(resp.isSuccess(), "已进应付流程不许整批回滚");
        assertTrue(resp.getBlockers().stream().anyMatch(b -> b.contains("红冲")),
                "blocker 指引走红冲连锁(会正确处理应付红字):" + resp.getBlockers());
        assertEquals("待付款", settleBillMapper.selectById(bill.getId()).getBillStatus(), "结算单原样");
        assertEquals(ImportBatch.STATUS_IMPORTED, importBatchMapper.selectById(ids[0]).getBatchStatus(),
                "批次仍是已导入(没被半回滚)");
    }
}
