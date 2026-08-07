package top.aole.vend.regression;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import top.aole.vend.BaseIntegrationTest;
import top.aole.vend.modules.basedata.domain.entity.Machine;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.basedata.domain.entity.SkuAlias;
import top.aole.vend.modules.basedata.infrastructure.mapper.MachineMapper;
import top.aole.vend.modules.basedata.infrastructure.mapper.ProductMapper;
import top.aole.vend.modules.basedata.infrastructure.mapper.SkuAliasMapper;
import top.aole.vend.modules.imports.dto.ImportDtos;
import top.aole.vend.modules.imports.service.ImportService;
import top.aole.vend.modules.stock.domain.entity.SaleRecord;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-8 穿行审计 14 场景回归套件基类(vend_test_regression 独立库,Flyway 自迁,每例事务回滚)。
 *
 * 场景来源:穿行审计报告-断点卡点逻辑错误.md(14 场景)+ DESIGN_DOC ④(场景→表/字段映射)+ ⑤口径
 *          + M2 新增场景15-17(补货闭环/盘点闭环/慢销与容量,M2-8 回归收口)。
 * 约定:M1/M2 已落地的链路端到端真测(建档→单据→导入→库存→补货→盘点→任务→报表);
 *       依赖 M3 钱账的断言 @Disabled 并注明缺什么、挂哪个里程碑(M2 断言已全部打开)。
 */
@ActiveProfiles("test-regression")
public abstract class RegressionSupport extends BaseIntegrationTest {

    protected static final String OPERATOR = "回归测试员";
    protected static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000);
    protected static final DateTimeFormatter PERIOD_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    @Autowired
    protected ImportService importService;
    @Autowired
    protected ProductMapper productMapper;
    @Autowired
    protected MachineMapper machineMapper;
    @Autowired
    protected SkuAliasMapper skuAliasMapper;
    @Autowired
    protected JdbcTemplate jdbc;

    // ============================== 造数工具 ==============================

    /** 商品档案(可带参考售价) */
    protected Product product(String name, String barcode, String refPrice) {
        Product p = new Product();
        p.setSkuCode("RG" + SEQ.incrementAndGet());
        p.setProductName(name);
        p.setBarcode(barcode);
        p.setUnit("瓶");
        p.setProductStatus("在售");
        if (refPrice != null) {
            p.setRefPrice(new BigDecimal(refPrice));
        }
        productMapper.insert(p);
        return p;
    }

    /** 机器档案(后台设备ID是导入通道锚点) */
    protected Machine machine(String name) {
        Machine m = new Machine();
        long n = SEQ.incrementAndGet();
        m.setMachineCode("RGM" + n);
        m.setMachineName(name);
        m.setDeviceId("RGDEV" + n);
        m.setMachineStatus("在线");
        machineMapper.insert(m);
        return m;
    }

    /** 别名映射:后台条码 → 采购 SKU(通道1/2 匹配主键) */
    protected void alias(String barcode, String backendName, Long productId) {
        SkuAlias a = new SkuAlias();
        a.setAliasCode("");
        a.setAliasBarcode(barcode == null ? "" : barcode);
        a.setAliasName(backendName);
        a.setProductId(productId);
        a.setBindSource("人工");
        skuAliasMapper.insert(a);
    }

    /** 销售记录(带金额/类型/时间;biz=book 正常口径) */
    protected SaleRecord sale(Long machineId, Long productId, String qty, String amount,
                              String orderType, LocalDateTime bizTime) {
        SaleRecord s = new SaleRecord();
        s.setOrderNo("RG-" + SEQ.incrementAndGet());
        s.setMachineId(machineId);
        s.setProductId(productId);
        s.setQty(new BigDecimal(qty));
        s.setAmountReceived(new BigDecimal(amount));
        s.setOrderType(orderType);
        s.setBizTime(bizTime);
        s.setBizPeriod(bizTime.format(PERIOD_FMT));
        s.setBookPeriod(s.getBizPeriod());
        saleRecordMapper.insert(s);
        return s;
    }

    // ============================== 导入通道(真实服务链) ==============================

    /** 内存里造一个 xlsx(第一行表头) */
    protected byte[] xlsx(Object[][] rows) throws Exception {
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

    private static final String[] SALE_HEADER = {
            "订单号", "商品名称", "商品条形码", "出货数量", "货道号", "设备ID", "商品金额(元)", "订单类型", "支付方式", "出货时间"};

    /** 出货明细文件(通道1) */
    protected byte[] saleFile(Object[][] dataRows) throws Exception {
        Object[][] all = new Object[dataRows.length + 1][];
        all[0] = SALE_HEADER;
        System.arraycopy(dataRows, 0, all, 1, dataRows.length);
        return xlsx(all);
    }

    private static final String[] REP_HEADER = {
            "设备ID", "货道号", "商品名称", "商品条形码", "补货前库存", "本次补货数", "补货后库存", "补货人", "补货时间"};

    /** 系统补货记录文件(通道2) */
    protected byte[] repFile(Object[][] dataRows) throws Exception {
        Object[][] all = new Object[dataRows.length + 1][];
        all[0] = REP_HEADER;
        System.arraycopy(dataRows, 0, all, 1, dataRows.length);
        return xlsx(all);
    }

    /** 两步式导入:上传预览 → 确认入账(真实服务链) */
    protected ImportDtos.CommitResp importFile(String fileType, byte[] content) {
        ImportDtos.PreviewResp preview = importService.upload(fileType, "regression-" + fileType + ".xlsx", content);
        assertTrue(preview.isColumnsOk(), "列映射校验应通过:" + preview.getWarnings());
        return importService.confirm(preview.getToken(), OPERATOR);
    }

    // ============================== Schema 断言(表结构类场景) ==============================

    /** 断言表存在(当前库) */
    protected void assertTableExists(String table) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?",
                Integer.class, table);
        assertTrue(n != null && n > 0, "表应存在:" + table);
    }

    /** 断言列存在,返回列元数据(IS_NULLABLE / COLUMN_DEFAULT / COLUMN_COMMENT) */
    protected Map<String, Object> assertColumn(String table, String column) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT IS_NULLABLE, COLUMN_DEFAULT, COLUMN_TYPE, COLUMN_COMMENT " +
                        "FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? AND column_name=?",
                table, column);
        assertTrue(!rows.isEmpty(), "列应存在:" + table + "." + column);
        return rows.get(0);
    }
}
