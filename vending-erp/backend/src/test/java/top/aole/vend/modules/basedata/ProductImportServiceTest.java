package top.aole.vend.modules.basedata;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.application.AliasService;
import top.aole.vend.modules.basedata.application.ProductImportService;
import top.aole.vend.modules.basedata.application.ProductService;
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
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商品建档导入单测。
 * 刻意不挂 @SpringBootTest:解析口径/行判定/入档分支这几件事跟库无关,mock 掉 mapper 后
 * 无需 MySQL 就能全跑,CI 和没装 docker 的开发机都拦得住回归。
 * (真库链路由 basedata 既有的 @SpringBootTest 用例覆盖。)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductImportServiceTest {

    @Mock
    private ProductMapper productMapper;
    @Mock
    private ProductService productService;
    @Mock
    private AliasService aliasService;
    @Mock
    private AliasPendingMapper aliasPendingMapper;

    private ProductImportService service;
    /** 自增 id,模拟 create 之后拿到主键 */
    private long seq;

    @BeforeEach
    void setUp() {
        service = new ProductImportService(new ExcelParser(), productMapper, productService,
                aliasService, aliasPendingMapper);
        seq = 100;
        when(productMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(aliasPendingMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(productService.create(any(), anyString())).thenAnswer(inv -> {
            Product p = inv.getArgument(0);
            p.setId(++seq);
            return p;
        });
    }

    // ---------- 解析 ----------

    @Test
    void 厂家后台导出的表头直接认得() {
        // 厂家表用的是「售价」「商品分类」,不是模板里的「参考售价」「分类」
        byte[] xlsx = xlsx(
                new String[]{"商品编号", "商品名称", "商品条形码", "售价", "商品分类"},
                new String[]{"SP101", "东方树叶青柑普洱500ml", "6925303730642", "5.00", "饮料"},
                new String[]{"SP102", "康师傅红烧牛肉面", "6920152400111", "5.00", "泡面"});

        ProductImportDtos.ParseResp resp = service.parse("商品列表.xlsx", xlsx);

        assertEquals(2, resp.getRowTotal());
        assertEquals(2, resp.getCreateCount());
        assertEquals(0, resp.getErrorCount());
        assertEquals("SP101", resp.getRows().get(0).getSkuCode());
        assertEquals("5.00", resp.getRows().get(0).getRefPrice());
        assertEquals("饮料", resp.getRows().get(0).getCategory());
    }

    @Test
    void 缺必填列直接拒绝并把读到的表头回给用户() {
        byte[] xlsx = xlsx(new String[]{"名字", "价格"}, new String[]{"可乐", "3"});

        BizException e = assertThrows(BizException.class, () -> service.parse("乱表.xlsx", xlsx));
        assertTrue(e.getMessage().contains("商品编号"), e.getMessage());
        assertTrue(e.getMessage().contains("商品名称"), "两个必填列都缺,要一次说全:" + e.getMessage());
        assertTrue(e.getMessage().contains("名字"), "报错要带上实际读到的表头,方便对照:" + e.getMessage());
    }

    @Test
    void 编号已存在的判更新_没有的判新建() {
        when(productMapper.selectList(any())).thenReturn(Collections.singletonList(product(7L, "SP101")));
        byte[] xlsx = xlsx(
                new String[]{"商品编号", "商品名称"},
                new String[]{"SP101", "改了名的青柑普洱"},
                new String[]{"SP999", "全新商品"});

        ProductImportDtos.ParseResp resp = service.parse("x.xlsx", xlsx);

        assertEquals(1, resp.getUpdateCount());
        assertEquals(1, resp.getCreateCount());
        assertEquals(ProductImportDtos.ACTION_UPDATE, resp.getRows().get(0).getAction());
        assertEquals(7L, resp.getRows().get(0).getExistingProductId());
        assertEquals(ProductImportDtos.ACTION_CREATE, resp.getRows().get(1).getAction());
    }

    @Test
    void 空编号_空名称_脏数字_文件内重复_四类都判错且说得出原因() {
        byte[] xlsx = xlsx(
                new String[]{"商品编号", "商品名称", "参考售价"},
                new String[]{"", "没编号", "3"},
                new String[]{"SP201", "", "3"},
                new String[]{"SP202", "售价填了句话", "两块五"},
                new String[]{"SP203", "第一次", "3"},
                new String[]{"SP203", "又来一次", "3"});

        ProductImportDtos.ParseResp resp = service.parse("x.xlsx", xlsx);

        assertEquals(4, resp.getErrorCount());
        assertTrue(resp.getRows().get(0).getErrorMsg().contains("商品编号"));
        assertTrue(resp.getRows().get(1).getErrorMsg().contains("商品名称"));
        assertTrue(resp.getRows().get(2).getErrorMsg().contains("参考售价"), resp.getRows().get(2).getErrorMsg());
        assertEquals(ProductImportDtos.ACTION_CREATE, resp.getRows().get(3).getAction());
        assertTrue(resp.getRows().get(4).getErrorMsg().contains("不止一次"), resp.getRows().get(4).getErrorMsg());
    }

    @Test
    void 带单位和千分位的价格照收_不判错() {
        byte[] xlsx = xlsx(
                new String[]{"商品编号", "商品名称", "参考成本", "参考售价"},
                new String[]{"SP301", "整箱矿泉水", "¥1,200.00", "5.00 元"});

        ProductImportDtos.ParseResp resp = service.parse("x.xlsx", xlsx);

        assertEquals(0, resp.getErrorCount(), resp.getRows().get(0).getErrorMsg());
        service.commit(resp.getRows(), "单测");
        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(productService).create(captor.capture(), anyString());
        assertEquals(0, new BigDecimal("1200.00").compareTo(captor.getValue().getRefCost()));
        assertEquals(0, new BigDecimal("5.00").compareTo(captor.getValue().getRefPrice()));
    }

    @Test
    void 预估能顺带消掉几条待绑别名() {
        when(aliasPendingMapper.selectList(any())).thenReturn(Arrays.asList(
                pending(1L, "SP101", "6925303730642"),   // 编号对得上
                pending(2L, "XX999", "6920152400111"),   // 条码对得上
                pending(3L, "ZZ000", "0000000000000")));  // 都对不上
        byte[] xlsx = xlsx(
                new String[]{"商品编号", "商品名称", "商品条形码"},
                new String[]{"SP101", "青柑普洱", "6925303730642"},
                new String[]{"SP102", "红烧牛肉面", "6920152400111"});

        assertEquals(2, service.parse("x.xlsx", xlsx).getPendingHitCount());
    }

    // ---------- 入档 ----------

    @Test
    void 入档_新建走create_已存在走update_错误行跳过() {
        when(productMapper.selectList(any())).thenReturn(Collections.singletonList(product(7L, "SP101")));
        List<ProductImportDtos.Row> rows = Arrays.asList(
                row("SP101", "已存在要更新", null),
                row("SP900", "新商品", null),
                row("", "没编号的坏行", null));

        ProductImportDtos.CommitResp resp = service.commit(rows, "单测");

        assertEquals(1, resp.getCreated());
        assertEquals(1, resp.getUpdated());
        assertEquals(1, resp.getFailed());
        verify(productService).update(anyLong(), any(), anyString());
        verify(productService, times(1)).create(any(), anyString());
        assertEquals(1, resp.getErrors().size());
        assertTrue(resp.getErrors().get(0).getMessage().contains("商品编号"));
    }

    @Test
    void 更新用的补丁不带sku编码和状态_免得覆盖掉停售() {
        when(productMapper.selectList(any())).thenReturn(Collections.singletonList(product(7L, "SP101")));

        service.commit(Collections.singletonList(row("SP101", "新名字", "6925303730642")), "单测");

        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(productService).update(anyLong(), captor.capture(), anyString());
        assertNull(captor.getValue().getSkuCode(), "sku_code 不该出现在补丁里(ProductService.update 会挡,但这里也别送)");
        assertNull(captor.getValue().getProductStatus(), "状态只能走 changeStatus,导入不许带");
        assertEquals("新名字", captor.getValue().getProductName());
    }

    @Test
    void 建档同时把后台编号加条码绑成别名() {
        service.commit(Collections.singletonList(row("SP101", "青柑普洱", "6925303730642")), "老王");

        ArgumentCaptor<Dtos.BindAliasReq> captor = ArgumentCaptor.forClass(Dtos.BindAliasReq.class);
        verify(aliasService).bind(captor.capture(), anyString());
        assertEquals("SP101", captor.getValue().getAliasCode());
        assertEquals("6925303730642", captor.getValue().getAliasBarcode());
        assertEquals(ProductImportService.BIND_SOURCE, captor.getValue().getBindSource());
    }

    @Test
    void 建完档顺手消掉待绑队列里对得上的条目() {
        when(aliasPendingMapper.selectList(any())).thenReturn(new ArrayList<>(Arrays.asList(
                pending(1L, "SP101", "6925303730642"),
                pending(2L, "别的", "9999999999999"))));

        ProductImportDtos.CommitResp resp =
                service.commit(Collections.singletonList(row("SP101", "青柑普洱", "6925303730642")), "老王");

        assertEquals(1, resp.getPendingCleared());
        verify(aliasService).confirmPending(1L, 101L, "老王");
        verify(aliasService, never()).confirmPending(2L, 101L, "老王");
    }

    @Test
    void 别名绑失败不算这行没建成_档案照样保住() {
        when(aliasService.bind(any(), anyString())).thenThrow(new BizException("条码撞车了"));

        ProductImportDtos.CommitResp resp =
                service.commit(Collections.singletonList(row("SP101", "青柑普洱", "6925303730642")), "老王");

        assertEquals(1, resp.getCreated());
        assertEquals(0, resp.getFailed());
        assertEquals(0, resp.getAliasBound());
        assertTrue(resp.getErrors().get(0).getMessage().contains("别名没绑上"));
    }

    @Test
    void 一行炸掉不拖累后面的行() {
        // 重新打桩必须走 doAnswer:when(mock.create(..)) 会先拿 null 参数跑一遍已有的 answer
        doAnswer(inv -> {
            Product p = inv.getArgument(0);
            if ("SP902".equals(p.getSkuCode())) {
                throw new BizException("SKU 编码已存在:SP902");
            }
            p.setId(++seq);
            return p;
        }).when(productService).create(any(), anyString());

        ProductImportDtos.CommitResp resp = service.commit(Arrays.asList(
                row("SP901", "甲", null), row("SP902", "乙", null), row("SP903", "丙", null)), "单测");

        assertEquals(2, resp.getCreated());
        assertEquals(1, resp.getFailed());
        assertEquals("SP902", resp.getErrors().get(0).getSkuCode());
    }

    // ---------- 模板 ----------

    @Test
    void 模板自己解析得开_表头就是解析口径() {
        byte[] tpl = service.template();

        ParsedSheet sheet = new ExcelParser().parse(new ByteArrayInputStream(tpl));
        assertTrue(sheet.getHeaders().contains("商品编号*"), sheet.getHeaders().toString());
        assertTrue(sheet.getHeaders().contains("参考售价"), sheet.getHeaders().toString());
        // 必填列带的 * 不能把解析绊倒
        ProductImportDtos.ParseResp resp = service.parse("商品导入模板.xlsx", tpl);
        assertEquals(2, resp.getRowTotal());
        assertEquals(0, resp.getErrorCount(), resp.getRows().get(0).getErrorMsg());
        assertEquals("SP101", resp.getRows().get(0).getSkuCode());
    }

    // ---------- 小工具 ----------

    private static Product product(Long id, String code) {
        Product p = new Product();
        p.setId(id);
        p.setSkuCode(code);
        p.setProductName("已有-" + code);
        p.setProductStatus("停售");
        return p;
    }

    private static AliasPending pending(Long id, String code, String barcode) {
        AliasPending p = new AliasPending();
        p.setId(id);
        p.setAliasCode(code);
        p.setAliasBarcode(barcode);
        p.setAliasName("后台名-" + code);
        p.setPendingStatus("待绑定");
        return p;
    }

    private static ProductImportDtos.Row row(String code, String name, String barcode) {
        ProductImportDtos.Row r = new ProductImportDtos.Row();
        r.setSkuCode(code);
        r.setProductName(name);
        r.setBarcode(barcode);
        return r;
    }

    /** 造一个内存 xlsx:第一行表头,其余数据行 */
    private static byte[] xlsx(String[] headers, String[]... dataRows) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Sheet1");
            Row head = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                head.createCell(i).setCellValue(headers[i]);
            }
            for (int r = 0; r < dataRows.length; r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < dataRows[r].length; c++) {
                    row.createCell(c).setCellValue(dataRows[r][c]);
                }
            }
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
