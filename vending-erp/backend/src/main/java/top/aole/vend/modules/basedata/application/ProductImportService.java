package top.aole.vend.modules.basedata.application;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.domain.entity.AliasPending;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.basedata.infrastructure.mapper.AliasPendingMapper;
import top.aole.vend.modules.basedata.infrastructure.mapper.ProductMapper;
import top.aole.vend.modules.basedata.interfaces.dto.Dtos;
import top.aole.vend.modules.basedata.interfaces.dto.ProductImportDtos;
import top.aole.vend.modules.imports.parser.ExcelParser;
import top.aole.vend.modules.imports.parser.ParsedSheet;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 商品建档导入(设置中心 → 商品 → 「导入商品列表」)。
 *
 * <p>为什么单开一条通道:导入中心的通道③「商品列表」只按条码给<b>已存在</b>的商品挂别名,
 * 挂不上就进待绑队列——档案是空的时候整批都挂不上,只能一条条手工建。这条通道反过来做:
 * 先把档案建出来,顺手把「后台编号+条码」绑成别名,并消掉队列里对得上的待绑条目。
 *
 * <p>口径:
 * <ul>
 *   <li>sku_code 取表里的「商品编号」——与期初导入向导一致(后台编号即 SKU 编号)。</li>
 *   <li>已存在的 sku_code 走<b>更新</b>:表里给了值的字段才覆盖,没给的列不动。</li>
 *   <li>建档/改档一律经 {@link ProductService},op_log 与 price_log 由它统一写(单一真相源)。</li>
 *   <li>逐行独立:一行失败只记该行错误,不回滚整批(与导入中心 rowOk/rowFail 口径一致)。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductImportService {

    /** 建档来源标记:落到 sku_alias.bind_source,便于事后追这批别名从哪来 */
    public static final String BIND_SOURCE = "商品建档导入";

    /**
     * 模板列 = 规范列名 → 可接受的表头写法。
     * 第一个元素是模板/导出用的规范名;后面是兼容写法(厂家后台商品列表导出直接可用,不用改表头)。
     */
    private static final String[][] COLUMNS = {
            {"商品编号", "商品编码", "sku编码", "sku", "编码", "编号"},
            {"商品名称", "商品名", "名称", "采购商品名", "品名"},
            {"商品条形码", "条形码", "条码", "barcode"},
            {"商品分类", "分类", "品类", "类别"},
            {"基本单位", "单位"},
            {"箱规", "每箱数量", "装箱数"},
            {"保质期(天)", "保质期天数", "保质期"},
            {"参考成本", "成本", "成本价", "进价", "采购单价"},
            {"参考售价", "售价", "零售价", "销售价", "单价"},
            {"机内上限", "机内上限建议", "货道容量"},
            {"备注", "说明"},
    };

    /** 必填列(缺了整份文件没法用);用 LinkedHashSet 保证报错里的列名顺序稳定 */
    private static final Set<String> REQUIRED =
            new LinkedHashSet<>(java.util.Arrays.asList("商品编号", "商品名称"));

    private final ExcelParser excelParser;
    private final ProductMapper productMapper;
    private final ProductService productService;
    private final AliasService aliasService;
    private final AliasPendingMapper aliasPendingMapper;

    // ============================== 解析(上传即出列表) ==============================

    public ProductImportDtos.ParseResp parse(String fileName, byte[] content) {
        ParsedSheet sheet = excelParser.parse(new ByteArrayInputStream(content));

        ProductImportDtos.ParseResp resp = new ProductImportDtos.ParseResp();
        resp.setFileName(fileName);
        resp.getHeaders().addAll(sheet.getHeaders());

        // 规范列名 → 文件里的实际表头
        Map<String, String> colMap = matchHeaders(sheet.getHeaders());
        List<String> missing = new ArrayList<>();
        for (String required : REQUIRED) {
            if (!colMap.containsKey(required)) {
                missing.add("「" + required + "」");
            }
        }
        if (!missing.isEmpty()) {
            throw new BizException("缺少必填列" + String.join("、", missing) + ",这份表没法建档。"
                    + "文件里读到的表头:" + String.join(" / ", sheet.getHeaders())
                    + "。可下载模板对照,或把表头改成模板里的写法。");
        }
        for (String[] col : COLUMNS) {
            if (!REQUIRED.contains(col[0]) && !colMap.containsKey(col[0])) {
                resp.getWarnings().add("没找到「" + col[0] + "」列,该字段留空(可在下方列表里手填)");
            }
        }
        if (sheet.getRows().isEmpty()) {
            throw new BizException("文件里没有数据行(只有表头?)");
        }

        Map<String, Product> existing = loadExistingByCode();
        List<AliasPending> pendings = loadPendings();
        Set<Long> pendingHit = new HashSet<>();
        Set<String> seenCodes = new HashSet<>();

        for (ParsedSheet.Row raw : sheet.getRows()) {
            ProductImportDtos.Row row = new ProductImportDtos.Row();
            row.setRowNo(raw.getRowNo());
            row.setSkuCode(cell(raw, colMap, "商品编号"));
            row.setProductName(cell(raw, colMap, "商品名称"));
            row.setBarcode(cell(raw, colMap, "商品条形码"));
            row.setCategory(cell(raw, colMap, "商品分类"));
            row.setUnit(cell(raw, colMap, "基本单位"));
            row.setBoxSpec(cell(raw, colMap, "箱规"));
            row.setShelfLifeDays(cell(raw, colMap, "保质期(天)"));
            row.setRefCost(cell(raw, colMap, "参考成本"));
            row.setRefPrice(cell(raw, colMap, "参考售价"));
            row.setMinDisplayQty(cell(raw, colMap, "机内上限"));
            row.setRemark(cell(raw, colMap, "备注"));

            String dupError = seenCodes.add(StrUtil.nullToEmpty(row.getSkuCode()))
                    ? null : "本次文件里「" + row.getSkuCode() + "」出现了不止一次";
            classify(row, existing, dupError);

            if (!ProductImportDtos.ACTION_ERROR.equals(row.getAction())) {
                for (AliasPending p : matchPendings(pendings, row.getSkuCode(), row.getBarcode())) {
                    pendingHit.add(p.getId());
                }
            }
            resp.getRows().add(row);
        }

        resp.setRowTotal(resp.getRows().size());
        resp.setCreateCount(count(resp.getRows(), ProductImportDtos.ACTION_CREATE));
        resp.setUpdateCount(count(resp.getRows(), ProductImportDtos.ACTION_UPDATE));
        resp.setErrorCount(count(resp.getRows(), ProductImportDtos.ACTION_ERROR));
        resp.setPendingHitCount(pendingHit.size());
        return resp;
    }

    // ============================== 入档(确认列表内容) ==============================

    /**
     * 逐行建档/更新 + 绑别名 + 消待绑队列。
     * 不加类级事务:一行炸掉不该拖走其它行(ProductService.create/update 自身是事务,单行仍原子)。
     */
    public ProductImportDtos.CommitResp commit(List<ProductImportDtos.Row> rows, String operator) {
        ProductImportDtos.CommitResp resp = new ProductImportDtos.CommitResp();
        if (rows == null || rows.isEmpty()) {
            throw new BizException("没有可导入的行");
        }
        // 档案表与待绑队列各只查一次:新建的边建边补进 map,消化掉的待绑条目就地摘走
        Map<String, Product> existing = loadExistingByCode();
        List<AliasPending> pendings = loadPendings();
        Set<String> seenCodes = new HashSet<>();

        for (ProductImportDtos.Row row : rows) {
            // 前端可能改过内容,一律按当下的档案重新判定,不信客户端传来的 action
            String dupError = seenCodes.add(StrUtil.nullToEmpty(StrUtil.trim(row.getSkuCode())))
                    ? null : "本次提交里「" + row.getSkuCode() + "」重复";
            classify(row, existing, dupError);
            if (ProductImportDtos.ACTION_ERROR.equals(row.getAction())) {
                resp.setFailed(resp.getFailed() + 1);
                resp.getErrors().add(new ProductImportDtos.RowError(row.getRowNo(), row.getSkuCode(), row.getErrorMsg()));
                continue;
            }
            Long productId;
            try {
                if (ProductImportDtos.ACTION_UPDATE.equals(row.getAction())) {
                    Product before = existing.get(row.getSkuCode());
                    productId = before.getId();
                    productService.update(productId, patchOf(row), operator);
                    resp.setUpdated(resp.getUpdated() + 1);
                } else {
                    Product created = productService.create(newProductOf(row), operator);
                    productId = created.getId();
                    existing.put(created.getSkuCode(), created);
                    resp.setCreated(resp.getCreated() + 1);
                }
            } catch (Exception e) {
                resp.setFailed(resp.getFailed() + 1);
                resp.getErrors().add(new ProductImportDtos.RowError(row.getRowNo(), row.getSkuCode(),
                        StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName())));
                continue;
            }
            // 别名与待绑队列是"顺带"的:失败只记提示,不把已经建好的档案算成失败行
            try {
                bindAlias(row, productId, operator);
                resp.setAliasBound(resp.getAliasBound() + 1);
            } catch (Exception e) {
                resp.getErrors().add(new ProductImportDtos.RowError(row.getRowNo(), row.getSkuCode(),
                        "档案已建好,但别名没绑上:" + e.getMessage()));
            }
            resp.setPendingCleared(resp.getPendingCleared() + clearPendings(pendings, row, productId, operator));
        }
        return resp;
    }

    // ============================== 模板 ==============================

    /** 生成导入模板(表头 + 两行示例),列顺序与解析口径一致 */
    public byte[] template() {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("商品列表");
            CellStyle head = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            bold.setColor(IndexedColors.WHITE.getIndex());
            head.setFont(bold);
            head.setAlignment(HorizontalAlignment.CENTER);
            head.setFillForegroundColor(IndexedColors.GREY_50_PERCENT.getIndex());
            head.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);

            org.apache.poi.ss.usermodel.Row header = sheet.createRow(0);
            for (int i = 0; i < COLUMNS.length; i++) {
                String name = COLUMNS[i][0] + (REQUIRED.contains(COLUMNS[i][0]) ? "*" : "");
                org.apache.poi.ss.usermodel.Cell cell = header.createCell(i);
                cell.setCellValue(name);
                cell.setCellStyle(head);
                sheet.setColumnWidth(i, 14 * 256);
            }
            String[][] samples = {
                    {"SP101", "东方树叶青柑普洱500ml", "6925303730642", "饮料", "瓶", "15", "365", "3.20", "5.00", "8", "示例行,导入前请删掉"},
                    {"SP102", "康师傅红烧牛肉面", "6920152400111", "泡面", "袋", "24", "180", "2.60", "5.00", "6", ""},
            };
            for (int r = 0; r < samples.length; r++) {
                org.apache.poi.ss.usermodel.Row row = sheet.createRow(r + 1);
                for (int c = 0; c < samples[r].length && c < COLUMNS.length; c++) {
                    row.createCell(c).setCellValue(samples[r][c]);
                }
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BizException("生成模板失败:" + e.getMessage());
        }
    }

    // ============================== 内部 ==============================

    /** 判定这一行是新建/更新/错误,并写回 action + errorMsg + existingProductId */
    private void classify(ProductImportDtos.Row row, Map<String, Product> existing, String dupError) {
        row.setErrorMsg(null);
        row.setExistingProductId(null);
        String code = StrUtil.trim(row.getSkuCode());
        String name = StrUtil.trim(row.getProductName());
        row.setSkuCode(code);
        row.setProductName(name);

        String error = null;
        if (StrUtil.isBlank(code)) {
            error = "商品编号为空";
        } else if (StrUtil.isBlank(name)) {
            error = "商品名称为空";
        } else if (dupError != null) {
            error = dupError;
        } else {
            error = firstNumberError(row);
        }
        if (error != null) {
            row.setAction(ProductImportDtos.ACTION_ERROR);
            row.setErrorMsg(error);
            return;
        }
        Product hit = existing.get(code);
        if (hit != null) {
            row.setAction(ProductImportDtos.ACTION_UPDATE);
            row.setExistingProductId(hit.getId());
        } else {
            row.setAction(ProductImportDtos.ACTION_CREATE);
        }
    }

    /** 数字列体检:返回第一个说不通的列,全对返回 null */
    private String firstNumberError(ProductImportDtos.Row row) {
        String[][] checks = {
                {"箱规", row.getBoxSpec()}, {"保质期(天)", row.getShelfLifeDays()},
                {"参考成本", row.getRefCost()}, {"参考售价", row.getRefPrice()},
                {"机内上限", row.getMinDisplayQty()},
        };
        for (String[] c : checks) {
            if (StrUtil.isBlank(c[1])) {
                continue;
            }
            BigDecimal v = toDecimal(c[1]);
            if (v == null) {
                return "「" + c[0] + "」不是数字:" + c[1];
            }
            if (v.signum() < 0) {
                return "「" + c[0] + "」不能是负数:" + c[1];
            }
        }
        return null;
    }

    private Product newProductOf(ProductImportDtos.Row row) {
        Product p = new Product();
        p.setSkuCode(row.getSkuCode());
        p.setProductName(row.getProductName());
        p.setBarcode(blankToNull(row.getBarcode()));
        p.setCategory(blankToNull(row.getCategory()));
        p.setUnit(blankToNull(row.getUnit()));
        p.setBoxSpec(toDecimal(row.getBoxSpec()));
        p.setShelfLifeDays(toInt(row.getShelfLifeDays()));
        p.setRefCost(toDecimal(row.getRefCost()));
        p.setRefPrice(toDecimal(row.getRefPrice()));
        p.setMinDisplayQty(toDecimal(row.getMinDisplayQty()));
        p.setRemark(blankToNull(row.getRemark()));
        p.setProductStatus("在售");
        return p;
    }

    /**
     * 更新用的补丁对象:只带表里给了值的字段。
     * MyBatis-Plus updateById 默认跳过 null 字段,所以文件里没有的列不会被清空
     * (ProductService.update 另外会挡住改 sku_code、挡住绕过状态流转)。
     */
    private Product patchOf(ProductImportDtos.Row row) {
        Product p = new Product();
        p.setProductName(row.getProductName());
        p.setBarcode(blankToNull(row.getBarcode()));
        p.setCategory(blankToNull(row.getCategory()));
        p.setUnit(blankToNull(row.getUnit()));
        p.setBoxSpec(toDecimal(row.getBoxSpec()));
        p.setShelfLifeDays(toInt(row.getShelfLifeDays()));
        p.setRefCost(toDecimal(row.getRefCost()));
        p.setRefPrice(toDecimal(row.getRefPrice()));
        p.setMinDisplayQty(toDecimal(row.getMinDisplayQty()));
        p.setRemark(blankToNull(row.getRemark()));
        return p;
    }

    /** 把「后台编号 + 条码」绑成别名,后续出货明细导入即可直接归集到这个 SKU */
    private void bindAlias(ProductImportDtos.Row row, Long productId, String operator) {
        Dtos.BindAliasReq req = new Dtos.BindAliasReq();
        req.setAliasCode(row.getSkuCode());
        req.setAliasBarcode(StrUtil.nullToEmpty(row.getBarcode()));
        req.setAliasName(row.getProductName());
        req.setProductId(productId);
        req.setBindSource(BIND_SOURCE);
        aliasService.bind(req, operator);
    }

    /** 消掉待绑队列里编号或条码对得上的条目(走 confirmPending,按队列自己的键绑,留 op_log) */
    private int clearPendings(List<AliasPending> pool, ProductImportDtos.Row row, Long productId, String operator) {
        int cleared = 0;
        for (AliasPending p : matchPendings(pool, row.getSkuCode(), row.getBarcode())) {
            try {
                aliasService.confirmPending(p.getId(), productId, operator);
                pool.remove(p);
                cleared++;
            } catch (Exception e) {
                pool.remove(p);
                log.warn("商品建档导入:待绑条目 {} 消化失败 {}", p.getId(), e.getMessage());
            }
        }
        return cleared;
    }

    /** 编号相等,或条码非空且相等 —— 与 sku_alias 的绑定键(编号+条码)同口径,不按名称猜 */
    private List<AliasPending> matchPendings(List<AliasPending> pendings, String skuCode, String barcode) {
        List<AliasPending> hit = new ArrayList<>();
        String code = StrUtil.trim(skuCode);
        String bar = StrUtil.trim(barcode);
        for (AliasPending p : pendings) {
            boolean byCode = StrUtil.isNotBlank(code) && code.equals(StrUtil.trim(p.getAliasCode()));
            boolean byBarcode = StrUtil.isNotBlank(bar) && bar.equals(StrUtil.trim(p.getAliasBarcode()));
            if (byCode || byBarcode) {
                hit.add(p);
            }
        }
        return hit;
    }

    private List<AliasPending> loadPendings() {
        return aliasPendingMapper.selectList(new LambdaQueryWrapper<AliasPending>()
                .eq(AliasPending::getPendingStatus, "待绑定"));
    }

    private Map<String, Product> loadExistingByCode() {
        Map<String, Product> map = new HashMap<>();
        for (Product p : productMapper.selectList(new LambdaQueryWrapper<Product>())) {
            map.put(p.getSkuCode(), p);
        }
        return map;
    }

    /** 规范列名 → 文件实际表头;大小写/空格/全角括号都当成同一个写法 */
    private Map<String, String> matchHeaders(List<String> headers) {
        Map<String, String> normalized = new LinkedHashMap<>();
        for (String h : headers) {
            normalized.putIfAbsent(normalize(h), h);
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String[] col : COLUMNS) {
            for (String candidate : col) {
                String actual = normalized.get(normalize(candidate));
                if (actual != null && !result.containsValue(actual)) {
                    result.put(col[0], actual);
                    break;
                }
            }
        }
        return result;
    }

    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return s.trim().toLowerCase()
                .replace("（", "(").replace("）", ")")
                .replace(" ", "").replace(" ", "")
                .replace("*", "").replace("(必填)", "").replace("必填", "");
    }

    private String cell(ParsedSheet.Row row, Map<String, String> colMap, String canonical) {
        String actual = colMap.get(canonical);
        return actual == null ? null : blankToNull(row.get(actual));
    }

    private static int count(List<ProductImportDtos.Row> rows, String action) {
        int n = 0;
        for (ProductImportDtos.Row r : rows) {
            if (action.equals(r.getAction())) {
                n++;
            }
        }
        return n;
    }

    private static String blankToNull(String v) {
        return StrUtil.isBlank(v) ? null : v.trim();
    }

    /** 宽松转数字:「¥3.50」「3.5 元」「1,200」都收;真不是数字返回 null */
    private static BigDecimal toDecimal(String v) {
        if (StrUtil.isBlank(v)) {
            return null;
        }
        String cleaned = v.replaceAll("[^0-9.\\-]", "");
        if (cleaned.isEmpty() || "-".equals(cleaned) || ".".equals(cleaned)) {
            return null;
        }
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer toInt(String v) {
        BigDecimal d = toDecimal(v);
        return d == null ? null : d.intValue();
    }
}
