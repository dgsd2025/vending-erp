package top.aole.vend.modules.imports.service;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.application.AliasService;
import top.aole.vend.modules.basedata.application.OpLogService;
import top.aole.vend.modules.basedata.domain.entity.Machine;
import top.aole.vend.modules.basedata.domain.entity.PriceLog;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.basedata.infrastructure.mapper.MachineMapper;
import top.aole.vend.modules.basedata.infrastructure.mapper.PriceLogMapper;
import top.aole.vend.modules.basedata.infrastructure.mapper.ProductMapper;
import top.aole.vend.modules.basedata.interfaces.dto.Dtos;
import top.aole.vend.modules.doc.domain.entity.DocHead;
import top.aole.vend.modules.doc.domain.enums.DocStatus;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.dto.DocCreateReq;
import top.aole.vend.modules.doc.dto.DocItemReq;
import top.aole.vend.modules.doc.mapper.DocHeadMapper;
import top.aole.vend.modules.doc.service.DocService;
import top.aole.vend.modules.imports.dto.ImportDtos.ColumnCheck;
import top.aole.vend.modules.imports.dto.ImportDtos.CommitResp;
import top.aole.vend.modules.imports.dto.ImportDtos.NegativeStock;
import top.aole.vend.modules.imports.dto.ImportDtos.PreviewResp;
import top.aole.vend.modules.imports.dto.ImportDtos.PriceChange;
import top.aole.vend.modules.imports.dto.ImportDtos.PriceConfirmReq;
import top.aole.vend.modules.imports.dto.ImportDtos.ReprocessResp;
import top.aole.vend.modules.imports.dto.ImportDtos.RollbackResp;
import top.aole.vend.modules.imports.domain.entity.ImportBatch;
import top.aole.vend.modules.imports.domain.entity.ImportError;
import top.aole.vend.modules.imports.mapper.ImportBatchMapper;
import top.aole.vend.modules.imports.mapper.ImportErrorMapper;
import top.aole.vend.modules.imports.mapper.ImportQueryMapper;
import top.aole.vend.modules.imports.parser.ExcelParser;
import top.aole.vend.modules.imports.parser.ParsedSheet;
import top.aole.vend.modules.stock.domain.entity.SaleRecord;
import top.aole.vend.modules.stock.domain.entity.StockLedger;
import top.aole.vend.modules.stock.domain.entity.MachineStockSnapshot;
import top.aole.vend.modules.stock.mapper.MachineStockSnapshotMapper;
import top.aole.vend.modules.stock.mapper.SaleRecordMapper;
import top.aole.vend.modules.stock.mapper.StockLedgerMapper;
import top.aole.vend.modules.stock.service.StockService;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 导入中心(M1-3):全系统数据入口,三通道两步式(上传预览 → 确认入账)。
 *
 * 通道1 出货明细 → sale_record(订单号+订单类型去重,重复导入零副作用);
 * 通道2 系统补货记录 → 出库上架/退库转移单(转移单唯一生产者=本通道,P0-4);
 * 通道3 商品列表 → sku_alias 初始化/增量(编号+条码绑,条码挂 SKU)。
 *
 * 通用机制:import_batch 批次落档 + 原始文件归档;行错进 import_error;整批可回滚;
 * 待绑定行(product_id=NULL)绑定后可"重处理"回补;改价侦测待确认清单。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImportService {

    /** 导入操作系统用户占位(SSO 接入后换真实 userId) */
    public static final Long IMPORT_USER = 1L;

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyy-MM");

    // ---- 通道列规格(表头名,与 fanmaiji 导出 / sprint0 数据字典对齐) ----
    private static final String[][] SALE_COLS = {
            {"订单号", "1"}, {"商品名称", "1"}, {"出货数量", "1"}, {"商品金额(元)", "1"},
            {"设备ID", "1"}, {"订单类型", "1"}, {"出货时间", "1"},
            {"商品条形码", "0"}, {"货道号", "0"}, {"支付方式", "0"}, {"设备名称", "0"}};
    private static final String[][] REPLENISH_COLS = {
            {"设备ID", "1"}, {"商品名称", "1"}, {"本次补货数", "1"}, {"补货时间", "1"},
            {"货道号", "0"}, {"商品条形码", "0"}, {"补货前库存", "0"}, {"补货后库存", "0"}, {"补货人", "0"}};
    private static final String[][] PRODUCT_LIST_COLS = {
            {"商品编号", "1"}, {"商品名称", "1"},
            {"商品条形码", "0"}, {"售价", "0"}, {"商品分类", "0"}};

    private final ExcelParser excelParser;
    private final AliasMatcher aliasMatcher;
    private final AliasService aliasService;
    private final DocService docService;
    private final StockService stockService;
    private final OpLogService opLogService;

    private final ImportBatchMapper batchMapper;
    private final ImportErrorMapper errorMapper;
    private final ImportQueryMapper queryMapper;
    private final SaleRecordMapper saleRecordMapper;
    private final MachineMapper machineMapper;
    private final ProductMapper productMapper;
    private final PriceLogMapper priceLogMapper;
    private final DocHeadMapper docHeadMapper;
    private final StockLedgerMapper stockLedgerMapper;
    private final MachineStockSnapshotMapper snapshotMapper;

    /** 归档根目录(默认 backend 同级 ../storage/imports;测试用 target 下临时目录) */
    @Value("${vend.import.storage-dir:../storage/imports}")
    private String storageDir;

    /** 两步式第①步的暂存登记(重启即失效,失效后重新上传即可) */
    private final Map<String, PendingUpload> pendingUploads = new ConcurrentHashMap<>();

    @Data
    static class PendingUpload {
        private String fileType;
        private String fileName;
        private String tmpPath;
    }

    // ============================== 第①步:上传解析预览 ==============================

    public PreviewResp upload(String fileType, String fileName, byte[] content) {
        String[][] spec = specOf(fileType);
        ParsedSheet sheet = excelParser.parse(new ByteArrayInputStream(content));

        PreviewResp resp = new PreviewResp();
        resp.setFileName(fileName);
        resp.setFileType(fileType);
        resp.setRowTotal(sheet.getRows().size());
        resp.setHeaders(sheet.getHeaders());
        boolean ok = true;
        for (String[] col : spec) {
            boolean required = "1".equals(col[1]);
            boolean found = sheet.getHeaders().contains(col[0]);
            resp.getColumnChecks().add(new ColumnCheck(col[0], required, found));
            if (required && !found) {
                ok = false;
                resp.getWarnings().add("缺少必填列「" + col[0] + "」,无法导入(厂家改模板了?)");
            }
        }
        resp.setColumnsOk(ok);
        int previewCount = Math.min(20, sheet.getRows().size());
        for (int i = 0; i < previewCount; i++) {
            resp.getPreviewRows().add(sheet.getRows().get(i).getCells());
        }

        // 暂存原始文件,等第②步确认
        String token = IdUtil.fastSimpleUUID();
        File tmp = new File(storageDir, "tmp/" + token + ".xlsx");
        FileUtil.writeBytes(content, tmp);
        PendingUpload pending = new PendingUpload();
        pending.setFileType(fileType);
        pending.setFileName(fileName);
        pending.setTmpPath(tmp.getAbsolutePath());
        pendingUploads.put(token, pending);
        resp.setToken(token);
        return resp;
    }

    // ============================== 第②步:确认入账 ==============================

    /**
     * P1-1:确认入账整体包事务——中途崩溃/异常时批次行、明细、单据、流水一起回滚,
     * 不再留下永远卡在「处理中」的僵尸批次(归档文件为文件系统操作不随事务回滚,
     * 失败后留下的孤儿归档目录无副作用,重新上传即可)。
     */
    @Transactional(rollbackFor = Exception.class)
    public CommitResp confirm(String token, String operator) {
        PendingUpload pending = pendingUploads.remove(token);
        if (pending == null) {
            throw new BizException("上传凭据已失效(服务重启或已确认过),请重新上传预览");
        }
        File tmp = new File(pending.getTmpPath());
        if (!tmp.exists()) {
            throw new BizException("暂存文件丢失,请重新上传");
        }
        ParsedSheet sheet = excelParser.parse(new ByteArrayInputStream(FileUtil.readBytes(tmp)));

        // 建批次(先落库拿 id,归档路径带批次号)
        ImportBatch batch = new ImportBatch();
        batch.setBatchNo("IMP-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + "-" + IdUtil.fastSimpleUUID().substring(0, 4).toUpperCase());
        // P0-B:原始文件名只存 DB 字段,且 FileUtil.getName 兜底剥掉客户端可能带的路径
        batch.setFileName(FileUtil.getName(pending.getFileName()));
        batch.setFileType(pending.getFileType());
        batch.setRowTotal(sheet.getRows().size());
        batch.setBatchStatus(ImportBatch.STATUS_PROCESSING);
        batch.setCreateUser(IMPORT_USER);
        batchMapper.insert(batch);

        // P0-B 路径穿越修复:归档名一律用服务端生成的 batchNo+固定后缀,
        // 绝不拼接客户端传来的原始文件名(../../xx 之类会写出存储目录并覆盖任意文件)。
        // 原始文件名仅存 import_batch.file_name 字段供展示。
        File archive = new File(storageDir, batch.getId() + "/" + batch.getBatchNo() + ".xlsx");
        FileUtil.move(tmp, archive, true);
        batch.setArchivePath(archive.getAbsolutePath());

        CommitResp resp = new CommitResp();
        resp.setBatchId(batch.getId());
        resp.setBatchNo(batch.getBatchNo());
        resp.setFileType(pending.getFileType());
        resp.setRowTotal(sheet.getRows().size());

        switch (pending.getFileType()) {
            case ImportBatch.TYPE_SALE:
                processSale(batch, sheet, resp);
                break;
            case ImportBatch.TYPE_REPLENISH:
                processReplenish(batch, sheet, resp);
                break;
            case ImportBatch.TYPE_PRODUCT_LIST:
                processProductList(batch, sheet, resp, operator);
                break;
            default:
                throw new BizException("不支持的导入类型:" + pending.getFileType());
        }

        batch.setRowOk(resp.getRowOk());
        batch.setRowFail(resp.getRowFail());
        batch.setRowDup(resp.getRowDup());
        batch.setBatchStatus(ImportBatch.STATUS_IMPORTED);
        batch.setUpdateUser(IMPORT_USER);
        batchMapper.updateById(batch);
        opLogService.record(operator, "导入", "import_batch", batch.getId(), null, batch);

        resp.setPriceChangeCount(listPriceChanges(batch.getId()).size());
        return resp;
    }

    // ============================== 通道1:出货明细 → sale_record ==============================

    /** 包私有:期初向导第③步(历史销售)复用同一处理管线(InitialImportService) */
    void processSale(ImportBatch batch, ParsedSheet sheet, CommitResp resp) {
        AliasMatcher.Session alias = aliasMatcher.openSession();
        Map<String, Long> machineByDevice = loadMachines();
        Set<String> existingKeys = new HashSet<>();
        for (Map<String, Object> row : queryMapper.existingSaleKeys()) {
            existingKeys.add(row.get("orderNo") + "\u0001" + row.get("orderType"));
        }
        Map<Long, BigDecimal> costCache = new HashMap<>();
        Map<String, Boolean> lockCache = new HashMap<>();
        Map<String, Integer> orderOccurrence = new HashMap<>();
        String currentMonth = YearMonth.now().format(PERIOD);
        String minPeriod = null;
        String maxPeriod = null;
        int ok = 0;
        int dup = 0;
        int fail = 0;
        int pendingBind = 0;

        for (ParsedSheet.Row row : sheet.getRows()) {
            try {
                String orderNoRaw = required(row, "订单号");
                String name = required(row, "商品名称");
                BigDecimal qty = parseDecimal(required(row, "出货数量"), "出货数量");
                BigDecimal amount = parseDecimal(required(row, "商品金额(元)"), "商品金额(元)");
                String deviceId = required(row, "设备ID");
                String orderTypeRaw = required(row, "订单类型");
                LocalDateTime bizTime = parseDateTime(required(row, "出货时间"), "出货时间");
                String barcode = row.get("商品条形码");

                Long machineId = machineByDevice.get(deviceId);
                if (machineId == null) {
                    fail++;
                    recordError(batch.getId(), row, ImportError.TYPE_MACHINE_MISSING,
                            "设备ID[" + deviceId + "]没有对应机器档案,请先在基础档案建机器");
                    continue;
                }

                String orderType = normalizeOrderType(orderTypeRaw);
                // 一单多行:同订单号第 2+ 行加确定性后缀 #k(按文件内出现次序,重复导入同文件后缀一致 → 幂等)
                int occ = orderOccurrence.merge(orderNoRaw, 1, Integer::sum);
                String orderNo = occ == 1 ? orderNoRaw : orderNoRaw + "#" + occ;

                String dedupKey = orderNo + "\u0001" + orderType;
                if (existingKeys.contains(dedupKey)) {
                    dup++;
                    continue;
                }

                Long productId = alias.match(barcode, name);
                if (productId == null) {
                    alias.pendingUpsert(null, barcode, name, batch.getId());
                    pendingBind++;
                }

                String bizPeriod = bizTime.format(PERIOD);
                boolean locked = lockCache.computeIfAbsent(bizPeriod, p -> queryMapper.periodLocked(p) > 0);

                SaleRecord sale = new SaleRecord();
                sale.setOrderNo(orderNo);
                sale.setMachineId(machineId);
                sale.setDeviceId(deviceId);
                sale.setSlotNo(row.get("货道号"));
                sale.setAliasCodeRaw(null);
                sale.setAliasBarcodeRaw(barcode);
                sale.setAliasNameRaw(name);
                sale.setProductId(productId);
                sale.setQty(qty);
                sale.setAmountReceived(amount);
                sale.setUnitPrice(qty.compareTo(BigDecimal.ZERO) != 0
                        ? amount.divide(qty, 4, RoundingMode.HALF_UP) : null);
                sale.setPayMethod(row.get("支付方式"));
                sale.setOrderType(orderType);
                sale.setBizTime(bizTime);
                sale.setBizPeriod(bizPeriod);
                sale.setBookPeriod(locked ? currentMonth : bizPeriod);
                sale.setCostAmount(costOf(costCache, productId, qty));
                sale.setImportBatchId(batch.getId());
                sale.setOfflineFlag(false);
                sale.setCreateUser(IMPORT_USER);
                saleRecordMapper.insert(sale);
                existingKeys.add(dedupKey);
                ok++;

                minPeriod = minPeriod == null || bizPeriod.compareTo(minPeriod) < 0 ? bizPeriod : minPeriod;
                maxPeriod = maxPeriod == null || bizPeriod.compareTo(maxPeriod) > 0 ? bizPeriod : maxPeriod;
            } catch (RowException e) {
                fail++;
                recordError(batch.getId(), row, e.errorType, e.getMessage());
            } catch (Exception e) {
                fail++;
                recordError(batch.getId(), row, ImportError.TYPE_FORMAT, "未预期错误:" + e.getMessage());
            }
        }
        if (minPeriod != null) {
            batch.setPeriodRange(minPeriod.equals(maxPeriod) ? minPeriod : minPeriod + " ~ " + maxPeriod);
        }
        resp.setRowOk(ok);
        resp.setRowDup(dup);
        resp.setRowFail(fail);
        resp.setPendingBind(pendingBind);
    }

    // ====================== 通道2:系统补货记录 → 出库上架/退库转移单 ======================

    private void processReplenish(ImportBatch batch, ParsedSheet sheet, CommitResp resp) {
        AliasMatcher.Session alias = aliasMatcher.openSession();
        Map<String, Long> machineByDevice = loadMachines();
        Map<Long, BigDecimal> costCache = new HashMap<>();
        Set<String> seenInFile = new HashSet<>();
        int dup = 0;
        int fail = 0;
        int pendingBind = 0;

        // 有效行分组:机器 + 补货时间戳 + 方向(正=出库上架,负=退库取回) → 一张转移单
        Map<String, List<ReplenishRow>> groups = new LinkedHashMap<>();
        List<ReplenishRow> snapshotRows = new ArrayList<>();

        for (ParsedSheet.Row row : sheet.getRows()) {
            try {
                String deviceId = required(row, "设备ID");
                String name = required(row, "商品名称");
                BigDecimal qty = parseDecimal(required(row, "本次补货数"), "本次补货数");
                LocalDateTime time = parseDateTime(required(row, "补货时间"), "补货时间");
                String barcode = row.get("商品条形码");

                Long machineId = machineByDevice.get(deviceId);
                if (machineId == null) {
                    fail++;
                    recordError(batch.getId(), row, ImportError.TYPE_MACHINE_MISSING,
                            "设备ID[" + deviceId + "]没有对应机器档案");
                    continue;
                }
                if (qty.compareTo(BigDecimal.ZERO) == 0) {
                    fail++;
                    recordError(batch.getId(), row, ImportError.TYPE_FORMAT, "本次补货数为 0,无意义行");
                    continue;
                }
                Long productId = alias.match(barcode, name);
                if (productId == null) {
                    alias.pendingUpsert(null, barcode, name, batch.getId());
                    pendingBind++;
                    fail++;
                    recordError(batch.getId(), row, ImportError.TYPE_ALIAS_UNBOUND,
                            "商品「" + name + "」别名未绑定,已进待绑定队列;绑定后请重新导入本文件(转移单不允许无 SKU)");
                    continue;
                }

                // 防重:同机器+商品+补货时间戳 已有机器账流水 → 跳过(重复导入零副作用)
                String key = machineId + "\u0001" + productId + "\u0001" + time;
                if (!seenInFile.add(key) || queryMapper.machineLedgerExists(machineId, productId, time) > 0) {
                    dup++;
                    continue;
                }

                ReplenishRow r = new ReplenishRow();
                r.machineId = machineId;
                r.productId = productId;
                r.slotNo = row.get("货道号");
                r.qty = qty;
                r.time = time;
                r.stockAfter = row.get("补货后库存") == null ? null
                        : parseDecimal(row.get("补货后库存"), "补货后库存");
                r.rowNo = row.getRowNo();
                r.raw = JSONUtil.toJsonStr(row.getCells());
                String groupKey = machineId + "\u0001" + time + "\u0001" + (qty.signum() > 0 ? "+" : "-");
                groups.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(r);
                if (r.stockAfter != null) {
                    snapshotRows.add(r);
                }
            } catch (RowException e) {
                fail++;
                recordError(batch.getId(), row, e.errorType, e.getMessage());
            } catch (Exception e) {
                fail++;
                recordError(batch.getId(), row, ImportError.TYPE_FORMAT, "未预期错误:" + e.getMessage());
            }
        }

        int ok = 0;
        int docs = 0;
        int matched = 0;
        for (Map.Entry<String, List<ReplenishRow>> e : groups.entrySet()) {
            List<ReplenishRow> rows = e.getValue();
            ReplenishRow first = rows.get(0);
            boolean forward = first.qty.signum() > 0;
            try {
                DocCreateReq req = new DocCreateReq();
                // 负数补货 = 取出调整 → 逆向转移(机器→仓库,退库单)
                req.setDocType(forward ? DocType.TRANSFER_OUT : DocType.RETURN_BACK);
                req.setBizDate(first.time.toLocalDate());
                req.setMachineId(first.machineId);
                req.setImportBatchId(batch.getId());
                req.setRemark("后台补货记录导入 " + first.time.format(DT));
                List<DocItemReq> items = new ArrayList<>();
                for (ReplenishRow r : rows) {
                    DocItemReq item = new DocItemReq();
                    item.setProductId(r.productId);
                    item.setSlotNo(r.slotNo);
                    item.setQty(r.qty.abs());
                    BigDecimal cost = costOf1(costCache, r.productId);
                    item.setUnitPrice(cost);
                    items.add(item);
                }
                req.setItems(items);
                // P1-5:导入来源由服务端受信通道设置(公开 createDoc 强制手工)
                Long docId = docService.createDocWithSource(req, IMPORT_USER, DocService.SOURCE_IMPORT);
                docService.submit(docId, IMPORT_USER);
                // 确认:exempt=true 负库存豁免(导入通道特权,豁免放行后亮"待补录采购"红灯);
                // bizTime=补货记录真实时间戳(机器库存增量推算靠它,绝不能用 00:00)
                docService.confirm(docId, IMPORT_USER, true, first.time);
                if (forward) {
                    // 预挂单冲抵:同机器+SKU+当日窗口的手工预挂单 → 作废+matched_doc_id(P0-4 防双扣)
                    matched += stockService.matchPendingTransfer(docId, IMPORT_USER).size();
                }
                docs++;
                ok += rows.size();
            } catch (Exception ex) {
                for (ReplenishRow r : rows) {
                    fail++;
                    ImportError err = new ImportError();
                    err.setBatchId(batch.getId());
                    err.setRowNo(r.rowNo);
                    err.setRawContent(r.raw);
                    err.setErrorType(ImportError.TYPE_FORMAT);
                    err.setErrorMsg("转移单生成失败:" + ex.getMessage());
                    err.setResolveStatus("待处理");
                    err.setCreateUser(IMPORT_USER);
                    errorMapper.insert(err);
                }
            }
        }

        // 机器快照:用"补货后库存"落锚点(source=补货记录,P2-12 锚点+增量推算法)
        int snapshots = 0;
        for (ReplenishRow r : snapshotRows) {
            stockService.recordMachineSnapshot(r.machineId, r.productId, r.slotNo,
                    r.stockAfter, "补货记录", r.time, IMPORT_USER);
            snapshots++;
        }

        // 负库存红灯:本批导致仓库结存为负的商品(待补录采购)
        for (Map<String, Object> row : queryMapper.negativeWarehouseOfBatch(batch.getId())) {
            NegativeStock ns = new NegativeStock();
            ns.setProductId(((Number) row.get("productId")).longValue());
            ns.setSkuCode((String) row.get("skuCode"));
            ns.setProductName((String) row.get("productName"));
            ns.setBalance(new BigDecimal(row.get("balance").toString()));
            resp.getNegativeStock().add(ns);
        }

        resp.setRowOk(ok);
        resp.setRowDup(dup);
        resp.setRowFail(fail);
        resp.setPendingBind(pendingBind);
        resp.setDocsCreated(docs);
        resp.setMatchedPrePending(matched);
        resp.setSnapshots(snapshots);
    }

    private static class ReplenishRow {
        Long machineId;
        Long productId;
        String slotNo;
        BigDecimal qty;
        LocalDateTime time;
        BigDecimal stockAfter;
        int rowNo;
        String raw;
    }

    // ====================== 通道3:商品列表 → sku_alias 初始化/增量 ======================

    private void processProductList(ImportBatch batch, ParsedSheet sheet, CommitResp resp, String operator) {
        // 挂 SKU 靠条码:product.barcode → product
        Map<String, Product> productByBarcode = new HashMap<>();
        for (Product p : productMapper.selectList(new LambdaQueryWrapper<Product>()
                .isNotNull(Product::getBarcode).ne(Product::getBarcode, ""))) {
            productByBarcode.put(p.getBarcode(), p);
        }
        AliasMatcher.Session alias = aliasMatcher.openSession();
        int ok = 0;
        int fail = 0;
        int pendingBind = 0;
        for (ParsedSheet.Row row : sheet.getRows()) {
            try {
                String code = required(row, "商品编号");
                String name = required(row, "商品名称");
                String barcode = row.get("商品条形码");
                Product product = barcode == null ? null : productByBarcode.get(barcode);
                if (product == null) {
                    // 条码挂不上 SKU → 待绑定队列(人工/AI 建议后确认)
                    alias.pendingUpsert(code, barcode, name, batch.getId());
                    pendingBind++;
                    ok++;
                    continue;
                }
                Dtos.BindAliasReq req = new Dtos.BindAliasReq();
                req.setAliasCode(code);
                req.setAliasBarcode(barcode);
                req.setAliasName(name);
                req.setProductId(product.getId());
                req.setBindSource("商品列表导入");
                aliasService.bind(req, operator);
                ok++;
            } catch (RowException e) {
                fail++;
                recordError(batch.getId(), row, e.errorType, e.getMessage());
            } catch (Exception e) {
                fail++;
                recordError(batch.getId(), row, ImportError.TYPE_FORMAT, "绑定失败:" + e.getMessage());
            }
        }
        resp.setRowOk(ok);
        resp.setRowFail(fail);
        resp.setPendingBind(pendingBind);
    }

    // ============================== 批次查询 / 错误 ==============================

    public Page<ImportBatch> pageBatches(long current, long size, String fileType) {
        return batchMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<ImportBatch>()
                        .eq(StrUtil.isNotBlank(fileType), ImportBatch::getFileType, fileType)
                        .orderByDesc(ImportBatch::getId));
    }

    public Page<ImportError> pageErrors(Long batchId, long current, long size) {
        return errorMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<ImportError>()
                        .eq(ImportError::getBatchId, batchId)
                        .orderByAsc(ImportError::getRowNo));
    }

    // ============================== 整批回滚 ==============================

    /**
     * 整批回滚:撤销该批产生的 sale_record / 转移单 / 库存流水 / 机器快照。
     * 已被下游引用(结算回填/红冲/预挂单冲抵)→ 拒绝并列出引用。
     * sale_record 物理删除(uk 是物理约束,逻辑删会挡住重新导入);原始文件仍在归档,op_log 留痕。
     */
    @Transactional(rollbackFor = Exception.class)
    public RollbackResp rollback(Long batchId, String operator) {
        ImportBatch batch = mustGetBatch(batchId);
        if (!ImportBatch.STATUS_IMPORTED.equals(batch.getBatchStatus())) {
            throw new BizException("批次[" + batch.getBatchNo() + "]状态为「" + batch.getBatchStatus() + "」,只有「已导入」可回滚");
        }
        RollbackResp resp = new RollbackResp();

        if (ImportBatch.TYPE_PRODUCT_LIST.equals(batch.getFileType())
                || ImportBatch.TYPE_INITIAL_PRODUCT.equals(batch.getFileType())) {
            throw new BizException("商品/别名类导入不支持整批回滚(别名绑一次终身生效);如需调整请到「商品档案 → 别名管理」逐条解绑");
        }

        boolean saleLike = ImportBatch.TYPE_SALE.equals(batch.getFileType())
                || ImportBatch.TYPE_INITIAL_SALE.equals(batch.getFileType());
        boolean docLike = ImportBatch.TYPE_REPLENISH.equals(batch.getFileType())
                || ImportBatch.TYPE_INITIAL_PURCHASE.equals(batch.getFileType());

        if (saleLike) {
            // 下游引用检查:已被平台结算单核销的销售记录不许撤
            List<SaleRecord> settled = saleRecordMapper.selectList(new LambdaQueryWrapper<SaleRecord>()
                    .eq(SaleRecord::getImportBatchId, batchId).isNotNull(SaleRecord::getSettlementId));
            if (!settled.isEmpty()) {
                resp.setSuccess(false);
                for (SaleRecord s : settled.subList(0, Math.min(10, settled.size()))) {
                    resp.getBlockers().add("订单[" + s.getOrderNo() + "]已被结算单[" + s.getSettlementId() + "]核销");
                }
                if (settled.size() > 10) {
                    resp.getBlockers().add("……共 " + settled.size() + " 条已核销记录");
                }
                return resp;
            }
            int removed = saleRecordMapper.delete(new LambdaQueryWrapper<SaleRecord>()
                    .eq(SaleRecord::getImportBatchId, batchId));
            resp.setSaleRemoved(removed);
        }

        if (docLike) {
            List<DocHead> docs = docHeadMapper.selectList(new LambdaQueryWrapper<DocHead>()
                    .eq(DocHead::getImportBatchId, batchId));
            List<Long> docIds = docs.stream().map(DocHead::getId).collect(Collectors.toList());
            if (!docIds.isEmpty()) {
                // 下游引用①:被红冲过
                List<DocHead> redRefs = docHeadMapper.selectList(new LambdaQueryWrapper<DocHead>()
                        .in(DocHead::getRedFlushOf, docIds));
                for (DocHead d : redRefs) {
                    resp.getBlockers().add("单据已被红冲单[" + d.getDocNo() + "]引用");
                }
                // 下游引用②:冲抵过手工预挂单(预挂单已作废,撤导入单会让手工账凭空消失)
                List<DocHead> matchedRefs = docHeadMapper.selectList(new LambdaQueryWrapper<DocHead>()
                        .in(DocHead::getMatchedDocId, docIds));
                for (DocHead d : matchedRefs) {
                    resp.getBlockers().add("手工预挂单[" + d.getDocNo() + "]已被本批冲抵,请先人工处理该单");
                }
                if (!resp.getBlockers().isEmpty()) {
                    resp.setSuccess(false);
                    return resp;
                }

                // 反做:库存流水物理删 + 机器快照删 + 单据作废(导入中心特权通道,绕状态机,op_log 留痕)
                List<StockLedger> ledgers = stockLedgerMapper.selectList(new LambdaQueryWrapper<StockLedger>()
                        .in(StockLedger::getDocId, docIds));
                Set<String> snapKeys = new HashSet<>();
                for (StockLedger l : ledgers) {
                    if (StockLedger.LOC_MACHINE.equals(l.getLocationType())) {
                        snapKeys.add(l.getMachineId() + "\u0001" + l.getBizTime());
                    }
                }
                int ledgerRemoved = stockLedgerMapper.delete(new LambdaQueryWrapper<StockLedger>()
                        .in(StockLedger::getDocId, docIds));
                int snapRemoved = 0;
                for (String key : snapKeys) {
                    String[] parts = key.split("\u0001");
                    snapRemoved += snapshotMapper.delete(new LambdaQueryWrapper<MachineStockSnapshot>()
                            .eq(MachineStockSnapshot::getMachineId, Long.valueOf(parts[0]))
                            .eq(MachineStockSnapshot::getSnapshotTime, LocalDateTime.parse(parts[1]))
                            .eq(MachineStockSnapshot::getSnapshotSource, "补货记录"));
                }
                for (DocHead d : docs) {
                    String before = JSONUtil.toJsonStr(d);
                    d.setDocStatus(DocStatus.VOID);
                    d.setRemark(StrUtil.nullToEmpty(d.getRemark()) + "|整批回滚 " + batch.getBatchNo());
                    docHeadMapper.updateById(d);
                    opLogService.record(operator, "整批回滚-单据作废", "doc_head", d.getId(), before, d);
                }
                resp.setDocsVoided(docs.size());
                resp.setLedgerRemoved(ledgerRemoved);
                resp.setSnapshotRemoved(snapRemoved);
            }
        }

        batch.setBatchStatus(ImportBatch.STATUS_ROLLED_BACK);
        batch.setUpdateUser(IMPORT_USER);
        batchMapper.updateById(batch);
        opLogService.record(operator, "整批回滚", "import_batch", batchId, null, batch);
        resp.setSuccess(true);
        return resp;
    }

    // ============================== 重处理待绑定行 ==============================

    /**
     * 绑定后回补:扫该批 product_id 为空的销售行,用最新 sku_alias 重新匹配(条码为主/名称兜底),
     * 命中则回填 product_id + 成本快照。
     */
    @Transactional(rollbackFor = Exception.class)
    public ReprocessResp reprocessPending(Long batchId, String operator) {
        ImportBatch batch = mustGetBatch(batchId);
        if (!ImportBatch.TYPE_SALE.equals(batch.getFileType())
                && !ImportBatch.TYPE_INITIAL_SALE.equals(batch.getFileType())) {
            throw new BizException("只有「出货明细/期初-历史销售」批次支持重处理待绑定行(补货记录的未绑定行请重新导入原文件)");
        }
        List<SaleRecord> rows = saleRecordMapper.selectList(new LambdaQueryWrapper<SaleRecord>()
                .eq(SaleRecord::getImportBatchId, batchId).isNull(SaleRecord::getProductId));
        AliasMatcher.Session alias = aliasMatcher.openSession();
        Map<Long, BigDecimal> costCache = new HashMap<>();
        int rebound = 0;
        for (SaleRecord row : rows) {
            Long productId = alias.match(row.getAliasBarcodeRaw(), row.getAliasNameRaw());
            if (productId == null) {
                continue;
            }
            row.setProductId(productId);
            row.setCostAmount(costOf(costCache, productId, row.getQty()));
            row.setUpdateUser(IMPORT_USER);
            saleRecordMapper.updateById(row);
            rebound++;
        }
        opLogService.record(operator, "重处理待绑定行", "import_batch", batchId, null,
                "扫描" + rows.size() + " 回补" + rebound);
        ReprocessResp resp = new ReprocessResp();
        resp.setScanned(rows.size());
        resp.setRebound(rebound);
        resp.setStillPending(rows.size() - rebound);
        return resp;
    }

    // ============================== 改价侦测 ==============================

    /** 改价待确认清单(P2-9):行成交价 ≠ product.ref_price;档案没参考价(NULL)不算改价 */
    public List<PriceChange> listPriceChanges(Long batchId) {
        ImportBatch batch = mustGetBatch(batchId);
        List<PriceChange> result = new ArrayList<>();
        if (ImportBatch.TYPE_SALE.equals(batch.getFileType())) {
            for (Map<String, Object> row : queryMapper.salePriceGroups(batchId)) {
                Object refObj = row.get("refPrice");
                if (refObj == null) {
                    continue;
                }
                BigDecimal ref = new BigDecimal(refObj.toString());
                BigDecimal latest = new BigDecimal(row.get("latestPrice").toString());
                if (ref.compareTo(latest) == 0) {
                    continue;
                }
                PriceChange pc = new PriceChange();
                pc.setProductId(((Number) row.get("productId")).longValue());
                pc.setSkuCode((String) row.get("skuCode"));
                pc.setProductName((String) row.get("productName"));
                pc.setRefPrice(ref);
                pc.setNewPrice(latest);
                pc.setRowCount(((Number) row.get("rowCount")).longValue());
                result.add(pc);
            }
        } else if (ImportBatch.TYPE_PRODUCT_LIST.equals(batch.getFileType())) {
            // 商品列表通道:重读归档文件比对售价(已绑条码的商品)
            File archive = batch.getArchivePath() == null ? null : new File(batch.getArchivePath());
            if (archive == null || !archive.exists()) {
                return result;
            }
            Map<String, Product> productByBarcode = new HashMap<>();
            for (Product p : productMapper.selectList(new LambdaQueryWrapper<Product>()
                    .isNotNull(Product::getBarcode).ne(Product::getBarcode, ""))) {
                productByBarcode.put(p.getBarcode(), p);
            }
            ParsedSheet sheet = excelParser.parse(new ByteArrayInputStream(FileUtil.readBytes(archive)));
            for (ParsedSheet.Row row : sheet.getRows()) {
                String barcode = row.get("商品条形码");
                String priceText = row.get("售价");
                if (barcode == null || priceText == null) {
                    continue;
                }
                Product p = productByBarcode.get(barcode);
                if (p == null || p.getRefPrice() == null) {
                    continue;
                }
                BigDecimal newPrice;
                try {
                    newPrice = new BigDecimal(priceText);
                } catch (NumberFormatException e) {
                    continue;
                }
                if (p.getRefPrice().compareTo(newPrice) == 0) {
                    continue;
                }
                PriceChange pc = new PriceChange();
                pc.setProductId(p.getId());
                pc.setSkuCode(p.getSkuCode());
                pc.setProductName(p.getProductName());
                pc.setRefPrice(p.getRefPrice());
                pc.setNewPrice(newPrice);
                pc.setRowCount(1);
                result.add(pc);
            }
        }
        return result;
    }

    /** 确认改价:更新档案参考价 + 写 price_log(change_source=导入侦测,喂定价 PDCA) */
    @Transactional(rollbackFor = Exception.class)
    public int confirmPriceChanges(Long batchId, PriceConfirmReq req, String operator) {
        mustGetBatch(batchId);
        int updated = 0;
        for (PriceConfirmReq.Item item : req.getItems()) {
            Product product = productMapper.selectById(item.getProductId());
            if (product == null) {
                throw new BizException("商品不存在:id=" + item.getProductId());
            }
            BigDecimal old = product.getRefPrice();
            if (old != null && old.compareTo(item.getNewPrice()) == 0) {
                continue;
            }
            productMapper.update(null, new LambdaUpdateWrapper<Product>()
                    .eq(Product::getId, product.getId())
                    .set(Product::getRefPrice, item.getNewPrice())
                    .set(Product::getUpdateUser, IMPORT_USER));
            PriceLog log = new PriceLog();
            log.setProductId(product.getId());
            log.setOldPrice(old);
            log.setNewPrice(item.getNewPrice());
            log.setChangeSource("导入侦测");
            log.setImportBatchId(batchId);
            log.setEffectDate(LocalDate.now());
            log.setConfirmBy(IMPORT_USER);
            log.setCreateUser(IMPORT_USER);
            priceLogMapper.insert(log);
            opLogService.record(operator, "确认改价", "product", product.getId(),
                    old, item.getNewPrice());
            updated++;
        }
        return updated;
    }

    // ============================== 内部工具 ==============================

    private ImportBatch mustGetBatch(Long batchId) {
        ImportBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new BizException("导入批次不存在:" + batchId);
        }
        return batch;
    }

    private Map<String, Long> loadMachines() {
        Map<String, Long> map = new HashMap<>();
        for (Machine m : machineMapper.selectList(null)) {
            map.put(m.getDeviceId(), m.getId());
        }
        return map;
    }

    /** 订单类型规整:后台「正常订单/退款订单/兑换订单/测试订单」→ 五枚举 */
    static String normalizeOrderType(String raw) {
        if (raw == null) {
            return "正常";
        }
        if (raw.contains("退款")) {
            return "退款";
        }
        if (raw.contains("兑换")) {
            return "兑换";
        }
        if (raw.contains("测试")) {
            return "测试";
        }
        return "正常";
    }

    /** 成本快照 = 全期加权单价 × 数量;无采购史 NULL(禁 0 加权,§13 场景7) */
    private BigDecimal costOf(Map<Long, BigDecimal> cache, Long productId, BigDecimal qty) {
        BigDecimal unit = costOf1(cache, productId);
        return unit == null ? null : unit.multiply(qty).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal costOf1(Map<Long, BigDecimal> cache, Long productId) {
        if (productId == null) {
            return null;
        }
        if (!cache.containsKey(productId)) {
            cache.put(productId, queryMapper.weightedCost(productId));
        }
        return cache.get(productId);
    }

    private void recordError(Long batchId, ParsedSheet.Row row, String type, String msg) {
        ImportError err = new ImportError();
        err.setBatchId(batchId);
        err.setRowNo(row.getRowNo());
        err.setRawContent(JSONUtil.toJsonStr(row.getCells()));
        err.setErrorType(type);
        err.setErrorMsg(StrUtil.maxLength(msg, 480));
        err.setResolveStatus("待处理");
        err.setCreateUser(IMPORT_USER);
        errorMapper.insert(err);
    }

    private static String required(ParsedSheet.Row row, String header) {
        String v = row.get(header);
        if (v == null) {
            throw new RowException(ImportError.TYPE_FORMAT, "「" + header + "」为空");
        }
        return v;
    }

    private static BigDecimal parseDecimal(String text, String field) {
        try {
            return new BigDecimal(text.replace(",", ""));
        } catch (NumberFormatException e) {
            throw new RowException(ImportError.TYPE_FORMAT, "「" + field + "」不是数字:" + text);
        }
    }

    /** 出货时间是文本(数据字典脏点①),兼容 yyyy-MM-dd HH:mm:ss / yyyy/M/d H:m:s / 无秒 */
    private static LocalDateTime parseDateTime(String text, String field) {
        String t = text.replace('/', '-').trim();
        List<DateTimeFormatter> fmts = Arrays.asList(
                DT,
                DateTimeFormatter.ofPattern("yyyy-M-d H:m:s"),
                DateTimeFormatter.ofPattern("yyyy-M-d H:m"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
        for (DateTimeFormatter f : fmts) {
            try {
                return LocalDateTime.parse(t, f);
            } catch (Exception ignore) {
                // 试下一种
            }
        }
        throw new RowException(ImportError.TYPE_FORMAT, "「" + field + "」时间格式无法识别:" + text);
    }

    private static String[][] specOf(String fileType) {
        if (fileType == null) {
            throw new BizException("导入类型不能为空");
        }
        switch (fileType) {
            case ImportBatch.TYPE_SALE:
                return SALE_COLS;
            case ImportBatch.TYPE_REPLENISH:
                return REPLENISH_COLS;
            case ImportBatch.TYPE_PRODUCT_LIST:
                return PRODUCT_LIST_COLS;
            default:
                throw new BizException("不支持的导入类型:" + fileType + "(可选:出货明细/系统补货记录/商品列表)");
        }
    }

    /** 行级错误(带类型),被捕获后进 import_error */
    private static class RowException extends RuntimeException {
        final String errorType;

        RowException(String errorType, String message) {
            super(message);
            this.errorType = errorType;
        }
    }
}
