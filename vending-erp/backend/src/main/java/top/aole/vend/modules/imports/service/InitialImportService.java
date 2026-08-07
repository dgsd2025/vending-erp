package top.aole.vend.modules.imports.service;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.basedata.domain.entity.Supplier;
import top.aole.vend.modules.basedata.infrastructure.mapper.MachineMapper;
import top.aole.vend.modules.basedata.infrastructure.mapper.ProductMapper;
import top.aole.vend.modules.basedata.infrastructure.mapper.SupplierMapper;
import top.aole.vend.modules.basedata.interfaces.dto.Dtos;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.dto.DocCreateReq;
import top.aole.vend.modules.doc.dto.DocItemReq;
import top.aole.vend.modules.doc.service.DocService;
import top.aole.vend.modules.imports.domain.entity.ImportBatch;
import top.aole.vend.modules.imports.dto.ImportDtos.CommitResp;
import top.aole.vend.modules.imports.dto.InitialDtos.ConflictGroup;
import top.aole.vend.modules.imports.dto.InitialDtos.ConflictResolution;
import top.aole.vend.modules.imports.dto.InitialDtos.StatusResp;
import top.aole.vend.modules.imports.dto.InitialDtos.Step1ConfirmReq;
import top.aole.vend.modules.imports.dto.InitialDtos.Step1PreviewResp;
import top.aole.vend.modules.imports.dto.InitialDtos.Step1Resp;
import top.aole.vend.modules.imports.dto.InitialDtos.Step2PreviewResp;
import top.aole.vend.modules.imports.dto.InitialDtos.Step2Resp;
import top.aole.vend.modules.imports.dto.InitialDtos.Step3PreviewResp;
import top.aole.vend.modules.imports.dto.InitialDtos.StepState;
import top.aole.vend.modules.imports.dto.InitialDtos.ValidateReq;
import top.aole.vend.modules.imports.dto.InitialDtos.ValidateResp;
import top.aole.vend.modules.imports.mapper.ImportBatchMapper;
import top.aole.vend.modules.imports.parser.ExcelParser;
import top.aole.vend.modules.imports.parser.ParsedSheet;
import top.aole.vend.modules.imports.parser.RawSheet;
import top.aole.vend.modules.report.mapper.ReportQueryMapper;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 期初导入向导(M1-6,imports 第四通道):上线一次性,把老 Excel 进销存套表三步搬进系统。
 *
 * 三步(每步 上传→预览→确认,可整批回滚重来):
 * ① 商品档案+别名:老表「商品档案」+「配比采购销售编码底稿」→ product + sku_alias + machine;
 *    一码多品冲突清洗(冲刺0实锤 SP009/010/011/012/046):拆分为新码(SP009A/B,原码留 legacy_code)
 *    或取首行(其余名并为别名);未给方案整批不放行。
 * ② 历史采购:老表「采购入库表」→ doc_type=期初 单据(按 入库日+供应商 分组),过账建立加权成本历史;
 * ③ 历史销售:老表「销售明细表6-7月」→ 复用通道1(sale_record,订单号去重幂等)。
 *
 * 对平校验:系统总采购额/总销售额 vs 老账数字相符(±0.5 元)才算「期初完成」(op_log 留痕)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InitialImportService {

    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyy-MM");
    /** 对平容差(单价保留 6 位小数的累计舍入) */
    private static final BigDecimal TOLERANCE = new BigDecimal("0.50");

    private static final String SHEET_PRODUCT = "商品档案";
    private static final String SHEET_MAPPING = "配比";
    private static final String SHEET_SALE = "销售明细";
    private static final String SHEET_PURCHASE = "采购入库";

    private final ExcelParser excelParser;
    private final ImportService importService;
    private final AliasService aliasService;
    private final DocService docService;
    private final OpLogService opLogService;
    private final ProductMapper productMapper;
    private final MachineMapper machineMapper;
    private final SupplierMapper supplierMapper;
    private final ImportBatchMapper batchMapper;
    private final ReportQueryMapper reportQueryMapper;

    @Value("${vend.import.storage-dir:../storage/imports}")
    private String storageDir;

    private final Map<String, Pending> pendings = new ConcurrentHashMap<>();

    @Data
    static class Pending {
        private String step;
        private String fileName;
        private String tmpPath;
    }

    // ============================== 解析模型 ==============================

    @Data
    static class ProductRow {
        String code;
        String name;
        String category;
        String unit;
        BigDecimal boxSpec;
        BigDecimal refCost;
        BigDecimal refPrice;
        Integer shelfLifeDays;
    }

    @Data
    static class MappingRow {
        String saleName;
        String purchaseName;
        String code;
    }

    @Data
    static class Workbook1 {
        /** code → 同码多行(不同名 = 一码多品冲突) */
        Map<String, List<ProductRow>> products = new LinkedHashMap<>();
        List<MappingRow> mappings = new ArrayList<>();
        /** 配比有映射但档案缺失,需补建的编码 */
        Set<String> autoCreateCodes = new LinkedHashSet<>();
        /** deviceId → 设备名(来自销售明细 sheet) */
        Map<String, String> devices = new LinkedHashMap<>();
        /** 销售名 → 最常见条码 */
        Map<String, String> topBarcode = new LinkedHashMap<>();
    }

    // ============================== 第①步:商品档案+别名 ==============================

    public Step1PreviewResp step1Upload(String fileName, byte[] content) {
        Workbook1 wb = parseWorkbook1(content);
        Step1PreviewResp resp = new Step1PreviewResp();
        resp.setFileName(fileName);
        resp.setProductCount(wb.getProducts().size());
        resp.setAliasCount(wb.getMappings().size());
        resp.setMachineCount(wb.getDevices().size());
        resp.getAutoCreateCodes().addAll(wb.getAutoCreateCodes());
        for (Map.Entry<String, List<ProductRow>> e : wb.getProducts().entrySet()) {
            if (e.getValue().size() > 1) {
                ConflictGroup g = new ConflictGroup();
                g.setCode(e.getKey());
                char letter = 'A';
                for (ProductRow row : e.getValue()) {
                    g.getNames().add(row.getName());
                    g.getSplitCodes().add(e.getKey() + letter++);
                }
                resp.getConflicts().add(g);
            }
        }
        if (wb.getDevices().isEmpty()) {
            resp.getWarnings().add("套表里没有「销售明细」sheet,机器档案与条码建不了——第③步导销售前请先手工建机器");
        }
        resp.setToken(stash("step1", fileName, content));
        return resp;
    }

    @Transactional(rollbackFor = Exception.class)
    public Step1Resp step1Confirm(Step1ConfirmReq req, String operator) {
        Pending pending = take(req.getToken(), "step1");
        byte[] content = FileUtil.readBytes(new File(pending.getTmpPath()));
        Workbook1 wb = parseWorkbook1(content);

        // 冲突必须全部给出处理方案,否则整批不放行(编码冲突,§期初通道)
        Map<String, String> resolutions = new HashMap<>();
        for (ConflictResolution rr : req.getResolutions()) {
            resolutions.put(rr.getCode(), rr.getMode());
        }
        List<String> unresolved = new ArrayList<>();
        for (Map.Entry<String, List<ProductRow>> e : wb.getProducts().entrySet()) {
            if (e.getValue().size() > 1 && !resolutions.containsKey(e.getKey())) {
                unresolved.add(e.getKey());
            }
        }
        if (!unresolved.isEmpty()) {
            throw new BizException("一码多品冲突未处理,整批不放行(编码冲突):" + unresolved
                    + "。每组请选择「拆分为新码」或「取首行」");
        }

        ImportBatch batch = createBatch(ImportBatch.TYPE_INITIAL_PRODUCT, pending,
                wb.getProducts().size() + wb.getMappings().size());

        // ---- 建商品(一码多品:split=拆新码留 legacy_code;first=取首行) ----
        Map<String, Product> byExactCode = new HashMap<>();
        // code → (name → product),拆分组按名称精确路由
        Map<String, Map<String, Product>> byCodeName = new HashMap<>();
        int[] counters = {0, 0}; // created / skipped
        int splitCount = 0;
        for (Map.Entry<String, List<ProductRow>> e : wb.getProducts().entrySet()) {
            String code = e.getKey();
            List<ProductRow> rows = e.getValue();
            boolean split = rows.size() > 1 && "split".equals(resolutions.get(code));
            if (split) {
                char letter = 'A';
                for (ProductRow row : rows) {
                    String newCode = code + letter++;
                    int before = counters[0];
                    Product p = upsertProduct(newCode, code, row, counters);
                    if (counters[0] > before) {
                        splitCount++;
                    }
                    byCodeName.computeIfAbsent(code, k -> new LinkedHashMap<>()).put(row.getName(), p);
                }
            } else {
                ProductRow first = rows.get(0);
                Product p = upsertProduct(code, null, first, counters);
                byExactCode.put(code, p);
                Map<String, Product> nameMap = byCodeName.computeIfAbsent(code, k -> new LinkedHashMap<>());
                for (ProductRow row : rows) {
                    nameMap.put(row.getName(), p); // 取首行:其余名同指首行商品
                }
            }
        }
        int created = counters[0];
        int skipped = counters[1];

        // ---- 建机器(销售明细 sheet 的设备,幂等) ----
        int machineCreated = 0;
        long machineSeq = machineMapper.selectCount(null) + 1;
        for (Map.Entry<String, String> e : wb.getDevices().entrySet()) {
            Machine exist = machineMapper.selectOne(new LambdaQueryWrapper<Machine>()
                    .eq(Machine::getDeviceId, e.getKey()));
            if (exist != null) {
                continue;
            }
            Machine machine = new Machine();
            machine.setMachineCode(String.format("VM%03d", machineSeq++));
            machine.setMachineName(e.getValue());
            machine.setDeviceId(e.getKey());
            machine.setMachineStatus("在线");
            machine.setCreateUser(ImportService.IMPORT_USER);
            machineMapper.insert(machine);
            machineCreated++;
        }

        // ---- 建别名(每个销售名一条:条码为主/名称兜底两条路都可命中,QC-序号确定性幂等) ----
        int aliasCreated = 0;
        int seq = 0;
        for (MappingRow m : wb.getMappings()) {
            seq++;
            Product target = resolveProduct(m.getCode(), m.getPurchaseName(), byExactCode, byCodeName);
            if (target == null) {
                continue; // 理论不可达:autoCreateCodes 已补建
            }
            Dtos.BindAliasReq bind = new Dtos.BindAliasReq();
            bind.setAliasCode(String.format("QC-%03d", seq));
            bind.setAliasBarcode(StrUtil.nullToEmpty(wb.getTopBarcode().get(m.getSaleName())));
            bind.setAliasName(m.getSaleName());
            bind.setProductId(target.getId());
            bind.setBindSource("期初导入");
            aliasService.bind(bind, operator);
            aliasCreated++;
        }

        finishBatch(batch, created + machineCreated + aliasCreated, 0, 0);
        opLogService.record(operator, "期初①商品别名", "import_batch", batch.getId(), null,
                "商品+" + created + " 跳过" + skipped + " 机器+" + machineCreated + " 别名" + aliasCreated);

        Step1Resp resp = new Step1Resp();
        resp.setBatchId(batch.getId());
        resp.setProductCreated(created);
        resp.setProductSkipped(skipped);
        resp.setAliasCreated(aliasCreated);
        resp.setMachineCreated(machineCreated);
        resp.setSplitProducts(splitCount);
        return resp;
    }

    // ============================== 第②步:历史采购 ==============================

    public Step2PreviewResp step2Upload(String fileName, byte[] content) {
        List<PurchaseRow> rows = parsePurchase(content);
        Step2PreviewResp resp = new Step2PreviewResp();
        resp.setFileName(fileName);
        resp.setRowCount(rows.size());
        BigDecimal qty = BigDecimal.ZERO;
        BigDecimal amt = BigDecimal.ZERO;
        Set<String> dates = new TreeSet<>();
        Set<String> suppliers = new LinkedHashSet<>();
        Set<String> missing = new LinkedHashSet<>();
        for (PurchaseRow r : rows) {
            qty = qty.add(r.qty);
            amt = amt.add(r.amount);
            dates.add(r.date.toString());
            suppliers.add(r.supplierName);
            if (findProduct(r.code, r.name) == null) {
                missing.add(r.code + " " + r.name);
            }
        }
        resp.setTotalQty(qty);
        resp.setTotalAmt(amt.setScale(2, RoundingMode.HALF_UP));
        resp.getDates().addAll(dates);
        resp.getSupplierNames().addAll(suppliers);
        resp.getMissingProducts().addAll(missing);
        if (!missing.isEmpty()) {
            resp.getWarnings().add("有 " + missing.size() + " 个商品在档案里找不到——请先完成第①步(商品档案+别名)");
        }
        resp.setToken(stash("step2", fileName, content));
        return resp;
    }

    @Transactional(rollbackFor = Exception.class)
    public Step2Resp step2Confirm(String token, String operator) {
        Pending pending = take(token, "step2");
        guardNotDone(ImportBatch.TYPE_INITIAL_PURCHASE,
                "历史采购已导入过(先在批次历史整批回滚旧批次再重导)");
        List<PurchaseRow> rows = parsePurchase(FileUtil.readBytes(new File(pending.getTmpPath())));
        ImportBatch batch = createBatch(ImportBatch.TYPE_INITIAL_PURCHASE, pending, rows.size());

        int supplierCreated = 0;
        Map<String, Supplier> supplierByCode = new HashMap<>();
        for (Supplier s : supplierMapper.selectList(null)) {
            supplierByCode.put(s.getSupplierCode(), s);
        }
        int fail = 0;
        // 按 入库日 + 供应商 分组 → 一张期初单
        Map<String, List<PurchaseRow>> groups = new LinkedHashMap<>();
        for (PurchaseRow r : rows) {
            if (findProduct(r.code, r.name) == null) {
                fail++;
                continue;
            }
            groups.computeIfAbsent(r.date + "" + r.supplierCode, k -> new ArrayList<>()).add(r);
        }
        if (fail > 0) {
            throw new BizException("有 " + fail + " 行商品档案缺失,整批不放行——请先完成第①步");
        }

        int docs = 0;
        int items = 0;
        BigDecimal totalAmt = BigDecimal.ZERO;
        TreeSet<String> periods = new TreeSet<>();
        for (Map.Entry<String, List<PurchaseRow>> e : groups.entrySet()) {
            List<PurchaseRow> group = e.getValue();
            PurchaseRow first = group.get(0);
            Supplier supplier = supplierByCode.get(first.supplierCode);
            if (supplier == null && StrUtil.isNotBlank(first.supplierCode)) {
                supplier = new Supplier();
                supplier.setSupplierCode(first.supplierCode);
                supplier.setSupplierName(first.supplierName);
                supplier.setCoopStatus("合作中");
                supplier.setCreateUser(ImportService.IMPORT_USER);
                supplierMapper.insert(supplier);
                supplierByCode.put(first.supplierCode, supplier);
                supplierCreated++;
            }
            DocCreateReq req = new DocCreateReq();
            req.setDocType(DocType.OPENING);
            req.setBizDate(first.date);
            req.setSupplierId(supplier == null ? null : supplier.getId());
            req.setImportBatchId(batch.getId());
            req.setRemark("期初历史采购 " + first.date + " " + first.supplierName);
            List<DocItemReq> itemReqs = new ArrayList<>();
            for (PurchaseRow r : group) {
                Product p = findProduct(r.code, r.name);
                DocItemReq item = new DocItemReq();
                item.setProductId(p.getId());
                item.setQty(r.qty);
                // 单价 = 金额/数量(6 位小数,整批合计与老账差 < 容差)
                item.setUnitPrice(r.amount.divide(r.qty, 6, RoundingMode.HALF_UP));
                item.setRemark(r.name);
                itemReqs.add(item);
                items++;
                totalAmt = totalAmt.add(r.amount);
            }
            req.setItems(itemReqs);
            // P1-5:导入来源由服务端受信通道设置(公开 createDoc 强制手工)
            Long docId = docService.createDocWithSource(req, ImportService.IMPORT_USER, DocService.SOURCE_IMPORT);
            docService.submit(docId, ImportService.IMPORT_USER);
            // 期初单确认过账:仓库+,biz_time=入库日 00:00(先于当日销售,对齐冲刺0事件排序)
            docService.confirm(docId, ImportService.IMPORT_USER, true, first.date.atStartOfDay());
            docs++;
            periods.add(first.date.format(PERIOD));
        }
        if (!periods.isEmpty()) {
            batch.setPeriodRange(periods.first().equals(periods.last())
                    ? periods.first() : periods.first() + " ~ " + periods.last());
        }
        finishBatch(batch, items, 0, 0);
        opLogService.record(operator, "期初②历史采购", "import_batch", batch.getId(), null,
                "单据" + docs + " 明细" + items + " 金额" + totalAmt);

        Step2Resp resp = new Step2Resp();
        resp.setBatchId(batch.getId());
        resp.setDocsCreated(docs);
        resp.setItemCount(items);
        resp.setTotalAmt(totalAmt.setScale(2, RoundingMode.HALF_UP));
        resp.setSupplierCreated(supplierCreated);
        resp.setRowFail(fail);
        return resp;
    }

    // ============================== 第③步:历史销售(复用通道1) ==============================

    public Step3PreviewResp step3Upload(String fileName, byte[] content) {
        ParsedSheet sheet = toChannel1Sheet(content);
        Step3PreviewResp resp = new Step3PreviewResp();
        resp.setFileName(fileName);
        resp.setRowCount(sheet.getRows().size());
        BigDecimal amt = BigDecimal.ZERO;
        for (ParsedSheet.Row row : sheet.getRows()) {
            String v = row.get("商品金额(元)");
            if (v != null) {
                amt = amt.add(new BigDecimal(v));
            }
        }
        resp.setTotalAmt(amt.setScale(2, RoundingMode.HALF_UP));
        resp.setToken(stash("step3", fileName, content));
        return resp;
    }

    @Transactional(rollbackFor = Exception.class)
    public CommitResp step3Confirm(String token, String operator) {
        Pending pending = take(token, "step3");
        ParsedSheet sheet = toChannel1Sheet(FileUtil.readBytes(new File(pending.getTmpPath())));
        ImportBatch batch = createBatch(ImportBatch.TYPE_INITIAL_SALE, pending, sheet.getRows().size());

        CommitResp resp = new CommitResp();
        resp.setBatchId(batch.getId());
        resp.setBatchNo(batch.getBatchNo());
        resp.setFileType(batch.getFileType());
        resp.setRowTotal(sheet.getRows().size());
        importService.processSale(batch, sheet, resp); // 复用通道1:去重幂等/别名匹配/锁账口径/成本快照
        finishBatch(batch, resp.getRowOk(), resp.getRowFail(), resp.getRowDup());
        opLogService.record(operator, "期初③历史销售", "import_batch", batch.getId(), null,
                "成功" + resp.getRowOk() + " 重复" + resp.getRowDup() + " 失败" + resp.getRowFail());
        return resp;
    }

    // ============================== 状态 / 对平校验 ==============================

    public StatusResp status() {
        StatusResp resp = new StatusResp();
        fillStep(resp.getStep1(), ImportBatch.TYPE_INITIAL_PRODUCT);
        fillStep(resp.getStep2(), ImportBatch.TYPE_INITIAL_PURCHASE);
        fillStep(resp.getStep3(), ImportBatch.TYPE_INITIAL_SALE);
        resp.setAllStepsDone(resp.getStep1().isDone() && resp.getStep2().isDone() && resp.getStep3().isDone());
        resp.setSystemPurchaseTotal(reportQueryMapper.systemPurchaseTotal());
        resp.setSystemSaleTotal(reportQueryMapper.systemSaleTotal());
        return resp;
    }

    /** 对平校验:系统数 vs 老账数(±0.5 元);双过 = 期初完成(op_log 留痕) */
    public ValidateResp validate(ValidateReq req, String operator) {
        if (req.getExpectedPurchase() == null || req.getExpectedSale() == null) {
            throw new BizException("请填入老账的采购总额与销售总额(对平基准)");
        }
        ValidateResp resp = new ValidateResp();
        resp.setSystemPurchase(reportQueryMapper.systemPurchaseTotal());
        resp.setSystemSale(reportQueryMapper.systemSaleTotal());
        resp.setExpectedPurchase(req.getExpectedPurchase());
        resp.setExpectedSale(req.getExpectedSale());
        resp.setPurchaseDiff(resp.getSystemPurchase().subtract(req.getExpectedPurchase())
                .setScale(2, RoundingMode.HALF_UP));
        resp.setSaleDiff(resp.getSystemSale().subtract(req.getExpectedSale())
                .setScale(2, RoundingMode.HALF_UP));
        resp.setPurchasePass(resp.getPurchaseDiff().abs().compareTo(TOLERANCE) <= 0);
        resp.setSalePass(resp.getSaleDiff().abs().compareTo(TOLERANCE) <= 0);
        resp.setPass(resp.isPurchasePass() && resp.isSalePass());
        if (resp.isPass()) {
            opLogService.record(operator, "期初完成", "import_batch", null, null,
                    "采购 " + resp.getSystemPurchase() + " vs " + req.getExpectedPurchase()
                            + " · 销售 " + resp.getSystemSale() + " vs " + req.getExpectedSale());
        }
        return resp;
    }

    // ============================== 解析 ==============================

    private Workbook1 parseWorkbook1(byte[] content) {
        Workbook1 wb = new Workbook1();
        // 商品档案:0名称 1编码 2分类 4整件规格 5单位 6进货价 7售价 9保质期
        RawSheet productSheet = excelParser.parseRaw(new ByteArrayInputStream(content), SHEET_PRODUCT);
        for (RawSheet.RawRow row : productSheet.getRows()) {
            String name = row.get(0);
            String code = row.get(1);
            if (name == null || code == null || !code.matches("^SP\\d+.*")) {
                continue;
            }
            List<ProductRow> list = wb.getProducts().computeIfAbsent(code, k -> new ArrayList<>());
            boolean dupName = list.stream().anyMatch(p -> p.getName().equals(name));
            if (dupName) {
                continue; // 同码同名多行 = 不同批次进价,只留首行
            }
            ProductRow p = new ProductRow();
            p.setCode(code);
            p.setName(name);
            p.setCategory(row.get(2));
            p.setUnit(StrUtil.blankToDefault(row.get(5), "件"));
            p.setRefCost(parseDecimalOrNull(row.get(6)));
            p.setRefPrice(parseDecimalOrNull(row.get(7)));
            String shelf = row.get(9);
            p.setShelfLifeDays(shelf != null && shelf.matches("\\d+") ? Integer.valueOf(shelf) : null);
            list.add(p);
        }
        if (wb.getProducts().isEmpty()) {
            throw new BizException("「商品档案」sheet 没解析到任何 SPxxx 商品行");
        }
        // 配比底稿:0销售名 4/5 主对(采购名/编码) 6/7 兜底对
        RawSheet mappingSheet = excelParser.parseRaw(new ByteArrayInputStream(content), SHEET_MAPPING);
        for (RawSheet.RawRow row : mappingSheet.getRows()) {
            String saleName = row.get(0);
            String code = row.get(5) != null ? row.get(5) : row.get(7);
            String purchaseName = row.get(5) != null ? row.get(4) : row.get(6);
            if (saleName == null || code == null || !code.matches("^SP\\d+.*") || "销售商品名称".equals(saleName)) {
                continue;
            }
            MappingRow m = new MappingRow();
            m.setSaleName(saleName);
            m.setPurchaseName(purchaseName == null ? saleName : purchaseName);
            m.setCode(code);
            wb.getMappings().add(m);
            if (!wb.getProducts().containsKey(code)) {
                // 档案缺失自动补建(冲刺0:SP068 只在配比出现)
                wb.getAutoCreateCodes().add(code);
                ProductRow p = new ProductRow();
                p.setCode(code);
                p.setName(m.getPurchaseName());
                p.setUnit("件");
                wb.getProducts().put(code, new ArrayList<>(java.util.Collections.singletonList(p)));
            }
        }
        // 销售明细(可选):设备档案 + 每销售名最常见条码
        try {
            RawSheet saleSheet = excelParser.parseRaw(new ByteArrayInputStream(content), SHEET_SALE);
            Map<String, Map<String, Integer>> votes = new HashMap<>();
            for (RawSheet.RawRow row : saleSheet.getRows()) {
                if (row.get(9) == null || "订单号".equals(row.get(9))) {
                    continue;
                }
                String name = row.get(0);
                String barcode = row.get(2);
                String deviceId = row.get(5);
                String deviceName = row.get(6);
                if (deviceId != null) {
                    wb.getDevices().putIfAbsent(deviceId, StrUtil.blankToDefault(deviceName, deviceId));
                }
                if (name != null && barcode != null) {
                    votes.computeIfAbsent(name, k -> new HashMap<>()).merge(barcode, 1, Integer::sum);
                }
            }
            for (Map.Entry<String, Map<String, Integer>> e : votes.entrySet()) {
                e.getValue().entrySet().stream().max(Comparator.comparingInt(Map.Entry::getValue))
                        .ifPresent(top -> wb.getTopBarcode().put(e.getKey(), top.getKey()));
            }
        } catch (BizException ignore) {
            // 没有销售明细 sheet:允许,机器/条码留空
        }
        return wb;
    }

    @Data
    static class PurchaseRow {
        LocalDate date;
        String supplierCode;
        String supplierName;
        String code;
        String name;
        BigDecimal qty;
        BigDecimal amount;
    }

    /** 采购入库表:0入库日期 2供应商编码 3供应商名称 4商品编码 5采购商品名称 10采购数量 12采购金额 */
    private List<PurchaseRow> parsePurchase(byte[] content) {
        RawSheet sheet = excelParser.parseRaw(new ByteArrayInputStream(content), SHEET_PURCHASE);
        List<PurchaseRow> rows = new ArrayList<>();
        for (RawSheet.RawRow row : sheet.getRows()) {
            String dateText = row.get(0);
            String name = row.get(5);
            if (dateText == null || name == null || "入库日期".equals(dateText)) {
                continue; // 表头/底部 #N/A 公式空行
            }
            BigDecimal qty = parseDecimalOrNull(row.get(10));
            BigDecimal amount = parseDecimalOrNull(row.get(12));
            if (qty == null || amount == null || qty.signum() == 0) {
                continue;
            }
            PurchaseRow r = new PurchaseRow();
            r.setDate(parseDate(dateText));
            r.setSupplierCode(StrUtil.nullToEmpty(row.get(2)));
            r.setSupplierName(StrUtil.blankToDefault(row.get(3), row.get(2)));
            r.setCode(row.get(4));
            r.setName(name);
            r.setQty(qty);
            r.setAmount(amount);
            rows.add(r);
        }
        if (rows.isEmpty()) {
            throw new BizException("「采购入库表」没解析到有效行");
        }
        return rows;
    }

    /** 销售明细 sheet → 通道1格式(0名称 2条码 3数量 4货道 5设备ID 6设备名 8金额 9订单号 10类型 11支付 12时间) */
    private ParsedSheet toChannel1Sheet(byte[] content) {
        RawSheet raw = excelParser.parseRaw(new ByteArrayInputStream(content), SHEET_SALE);
        ParsedSheet sheet = new ParsedSheet();
        sheet.getHeaders().addAll(java.util.Arrays.asList(
                "订单号", "商品名称", "商品条形码", "出货数量", "货道号", "设备ID", "设备名称",
                "商品金额(元)", "订单类型", "支付方式", "出货时间"));
        for (RawSheet.RawRow row : raw.getRows()) {
            if (row.get(9) == null || "订单号".equals(row.get(9))) {
                continue;
            }
            ParsedSheet.Row r = new ParsedSheet.Row();
            r.setRowNo(row.getRowNo());
            r.getCells().put("订单号", row.get(9));
            r.getCells().put("商品名称", row.get(0));
            r.getCells().put("商品条形码", row.get(2));
            r.getCells().put("出货数量", row.get(3));
            r.getCells().put("货道号", row.get(4));
            r.getCells().put("设备ID", row.get(5));
            r.getCells().put("设备名称", row.get(6));
            r.getCells().put("商品金额(元)", row.get(8));
            r.getCells().put("订单类型", StrUtil.blankToDefault(row.get(10), "正常订单"));
            r.getCells().put("支付方式", row.get(11));
            r.getCells().put("出货时间", row.get(12));
            sheet.getRows().add(r);
        }
        if (sheet.getRows().isEmpty()) {
            throw new BizException("「销售明细」没解析到有效行");
        }
        return sheet;
    }

    // ============================== 内部 ==============================

    /** skuCode 精确命中,或 legacy_code 命中(拆分组按名称路由,名对不上取该组第一个) */
    private Product findProduct(String code, String name) {
        if (code == null) {
            return null;
        }
        Product exact = productMapper.selectOne(new LambdaQueryWrapper<Product>()
                .eq(Product::getSkuCode, code));
        if (exact != null) {
            return exact;
        }
        List<Product> splits = productMapper.selectList(new LambdaQueryWrapper<Product>()
                .eq(Product::getLegacyCode, code).orderByAsc(Product::getSkuCode));
        if (splits.isEmpty()) {
            return null;
        }
        for (Product p : splits) {
            if (p.getProductName().equals(name)) {
                return p;
            }
        }
        return splits.get(0);
    }

    private Product resolveProduct(String code, String purchaseName,
                                   Map<String, Product> byExactCode,
                                   Map<String, Map<String, Product>> byCodeName) {
        Map<String, Product> nameMap = byCodeName.get(code);
        if (nameMap != null) {
            Product byName = nameMap.get(purchaseName);
            if (byName != null) {
                return byName;
            }
            if (!nameMap.isEmpty()) {
                return nameMap.values().iterator().next();
            }
        }
        return byExactCode.get(code);
    }

    /** 幂等建商品:sku_code 已存在则跳过(counters[0]=新建数 counters[1]=跳过数) */
    private Product upsertProduct(String skuCode, String legacyCode, ProductRow row, int[] counters) {
        Product exist = productMapper.selectOne(new LambdaQueryWrapper<Product>()
                .eq(Product::getSkuCode, skuCode));
        if (exist != null) {
            counters[1]++;
            return exist;
        }
        Product p = new Product();
        p.setSkuCode(skuCode);
        p.setLegacyCode(legacyCode);
        p.setProductName(row.getName());
        p.setCategory(row.getCategory());
        p.setUnit(row.getUnit());
        p.setRefCost(row.getRefCost());
        p.setRefPrice(row.getRefPrice());
        p.setShelfLifeDays(row.getShelfLifeDays());
        p.setProductStatus("在售");
        p.setCreateUser(ImportService.IMPORT_USER);
        productMapper.insert(p);
        counters[0]++;
        return p;
    }

    private void fillStep(StepState state, String fileType) {
        ImportBatch latest = batchMapper.selectOne(new LambdaQueryWrapper<ImportBatch>()
                .eq(ImportBatch::getFileType, fileType)
                .eq(ImportBatch::getBatchStatus, ImportBatch.STATUS_IMPORTED)
                .orderByDesc(ImportBatch::getId).last("LIMIT 1"));
        if (latest != null) {
            state.setDone(true);
            state.setBatchId(latest.getId());
            state.setBatchNo(latest.getBatchNo());
            state.setBatchStatus(latest.getBatchStatus());
            state.setRowOk(latest.getRowOk() == null ? 0 : latest.getRowOk());
            state.setRowFail(latest.getRowFail() == null ? 0 : latest.getRowFail());
            state.setDoneAt(latest.getCreateTime() == null ? null
                    : latest.getCreateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        }
    }

    private void guardNotDone(String fileType, String message) {
        Long count = batchMapper.selectCount(new LambdaQueryWrapper<ImportBatch>()
                .eq(ImportBatch::getFileType, fileType)
                .eq(ImportBatch::getBatchStatus, ImportBatch.STATUS_IMPORTED));
        if (count != null && count > 0) {
            throw new BizException(message);
        }
    }

    private String stash(String step, String fileName, byte[] content) {
        String token = IdUtil.fastSimpleUUID();
        File tmp = new File(storageDir, "tmp/" + token + ".xlsx");
        FileUtil.writeBytes(content, tmp);
        Pending pending = new Pending();
        pending.setStep(step);
        pending.setFileName(fileName);
        pending.setTmpPath(tmp.getAbsolutePath());
        pendings.put(token, pending);
        return token;
    }

    private Pending take(String token, String step) {
        Pending pending = pendings.remove(token);
        if (pending == null || !step.equals(pending.getStep())) {
            throw new BizException("上传凭据已失效(服务重启或已确认过),请重新上传预览");
        }
        if (!new File(pending.getTmpPath()).exists()) {
            throw new BizException("暂存文件丢失,请重新上传");
        }
        return pending;
    }

    private ImportBatch createBatch(String fileType, Pending pending, int rowTotal) {
        ImportBatch batch = new ImportBatch();
        batch.setBatchNo("IMP-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + "-" + IdUtil.fastSimpleUUID().substring(0, 4).toUpperCase());
        // P0-B:原始文件名只存 DB 字段(FileUtil.getName 剥路径兜底)
        batch.setFileName(FileUtil.getName(pending.getFileName()));
        batch.setFileType(fileType);
        batch.setRowTotal(rowTotal);
        batch.setBatchStatus(ImportBatch.STATUS_PROCESSING);
        batch.setCreateUser(ImportService.IMPORT_USER);
        batchMapper.insert(batch);
        // P0-B 路径穿越修复:归档名=服务端 batchNo+固定后缀,不拼客户端原始文件名
        File archive = new File(storageDir, batch.getId() + "/" + batch.getBatchNo() + ".xlsx");
        FileUtil.move(new File(pending.getTmpPath()), archive, true);
        batch.setArchivePath(archive.getAbsolutePath());
        return batch;
    }

    private void finishBatch(ImportBatch batch, int ok, int fail, int dup) {
        batch.setRowOk(ok);
        batch.setRowFail(fail);
        batch.setRowDup(dup);
        batch.setBatchStatus(ImportBatch.STATUS_IMPORTED);
        batch.setUpdateUser(ImportService.IMPORT_USER);
        batchMapper.updateById(batch);
    }

    private static BigDecimal parseDecimalOrNull(String text) {
        if (text == null) {
            return null;
        }
        try {
            return new BigDecimal(text.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate parseDate(String text) {
        String t = text.replace('/', '-').trim();
        if (t.length() >= 10) {
            t = t.substring(0, 10);
        }
        return LocalDate.parse(t);
    }
}
