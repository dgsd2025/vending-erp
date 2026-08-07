package top.aole.vend.modules.monthly.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Service;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.finreport.dto.FinReportDtos;
import top.aole.vend.modules.monthly.dto.MonthlyDtos;
import top.aole.vend.modules.report.dto.ReportDtos;
import top.aole.vend.modules.settle.dto.SettleDtos;
import top.aole.vend.modules.stocktake.dto.StocktakeDtos;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 月度报表包一键导出(M4-4):
 * - Excel(POI XSSF):前六件套各一 sheet(进销存/利润表/现金流水/往来/损耗/资产快照);
 * - Word(POI XWPF):第七件《月度经营分析报告》,AI 综述 + 七节标题/叙述/数据表(含表格)。
 *
 * 只负责"把已算好的包渲染成文件",不做任何计算(数字来自 MonthlyReportService / MonthlyAnalysisService)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonthlyExportService {

    private final MonthlyReportService monthlyReportService;
    private final MonthlyAnalysisService monthlyAnalysisService;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // ============================== Excel(六件套六 sheet)==============================

    public byte[] exportExcel(String month) {
        MonthlyDtos.PackageResp pkg = monthlyReportService.buildPackage(month);
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            CellStyle head = headStyle(wb);
            CellStyle title = titleStyle(wb);

            sheetInventory(wb, head, title, pkg);
            sheetProfit(wb, head, title, pkg);
            sheetCashflow(wb, head, title, pkg);
            sheetPayable(wb, head, title, pkg);
            sheetLoss(wb, head, title, pkg);
            sheetAsset(wb, head, title, pkg);

            wb.write(bos);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new BizException("月度报表包 Excel 导出失败:" + e.getMessage());
        }
    }

    private void sheetInventory(Workbook wb, CellStyle head, CellStyle title, MonthlyDtos.PackageResp pkg) {
        Sheet sh = wb.createSheet("①进销存汇总");
        int r = 0;
        r = titleRow(sh, r, title, "进销存汇总表 · " + pkg.getMonth());
        String[] cols = {"SKU编码", "商品名", "期初数量", "期初金额", "入库数量", "入库金额",
                "出库数量", "出库金额", "期末数量", "期末金额"};
        r = headRow(sh, r, head, cols);
        ReportDtos.InventorySummaryResp inv = pkg.getInventory();
        for (ReportDtos.InventorySummaryRow row : inv.getRows()) {
            Row rr = sh.createRow(r++);
            int c = 0;
            cell(rr, c++, row.getCode());
            cell(rr, c++, row.getName());
            num(rr, c++, row.getOpeningQty());
            num(rr, c++, row.getOpeningAmt());
            num(rr, c++, row.getInQty());
            num(rr, c++, row.getInAmt());
            num(rr, c++, row.getOutQty());
            num(rr, c++, row.getOutAmt());
            num(rr, c++, row.getClosingQty());
            num(rr, c, row.getClosingAmt());
        }
        if (inv.getTotal() != null) {
            ReportDtos.InventorySummaryRow t = inv.getTotal();
            Row rr = sh.createRow(r);
            int c = 0;
            cell(rr, c++, "");
            cell(rr, c++, "合计");
            num(rr, c++, t.getOpeningQty());
            num(rr, c++, t.getOpeningAmt());
            num(rr, c++, t.getInQty());
            num(rr, c++, t.getInAmt());
            num(rr, c++, t.getOutQty());
            num(rr, c++, t.getOutAmt());
            num(rr, c++, t.getClosingQty());
            num(rr, c, t.getClosingAmt());
        }
        autosize(sh, cols.length);
    }

    private void sheetProfit(Workbook wb, CellStyle head, CellStyle title, MonthlyDtos.PackageResp pkg) {
        Sheet sh = wb.createSheet("②简版利润表");
        int r = 0;
        r = titleRow(sh, r, title, "简版利润表 · " + pkg.getMonth()
                + (pkg.isLocked() ? "(已锁账)" : ""));
        r = headRow(sh, r, head, new String[]{"项目", "金额(对经营利润贡献)", "口径说明"});
        FinReportDtos.ProfitResp p = pkg.getProfit();
        for (FinReportDtos.PlRow row : p.getRows()) {
            Row rr = sh.createRow(r++);
            cell(rr, 0, (row.isSubtotal() ? "【" + row.getLabel() + "】" : row.getLabel()));
            num(rr, 1, row.getAmount());
            cell(rr, 2, row.getNote());
        }
        autosize(sh, 3);
    }

    private void sheetCashflow(Workbook wb, CellStyle head, CellStyle title, MonthlyDtos.PackageResp pkg) {
        Sheet sh = wb.createSheet("③现金流水汇总");
        MonthlyDtos.CashflowSummary cf = pkg.getCashflow();
        int r = 0;
        r = titleRow(sh, r, title, "现金流水汇总 · " + pkg.getMonth());

        subTitle(sh, r++, "按账户");
        r = headRow(sh, r, head, new String[]{"账户", "收(元)", "支(元)", "净额(元)", "笔数"});
        for (MonthlyDtos.CashflowRow row : cf.getByAccount()) {
            Row rr = sh.createRow(r++);
            cell(rr, 0, row.getName());
            num(rr, 1, row.getInflow());
            num(rr, 2, row.getOutflow());
            num(rr, 3, row.getNet());
            num(rr, 4, BigDecimal.valueOf(row.getCount()));
        }
        Row tr = sh.createRow(r++);
        cell(tr, 0, "合计");
        num(tr, 1, cf.getTotalInflow());
        num(tr, 2, cf.getTotalOutflow());
        num(tr, 3, cf.getTotalNet());
        r++;

        subTitle(sh, r++, "按类别(利润表行)");
        r = headRow(sh, r, head, new String[]{"类别", "收(元)", "支(元)", "净额(元)", "笔数"});
        for (MonthlyDtos.CashflowRow row : cf.getByCategory()) {
            Row rr = sh.createRow(r++);
            cell(rr, 0, row.getName());
            num(rr, 1, row.getInflow());
            num(rr, 2, row.getOutflow());
            num(rr, 3, row.getNet());
            num(rr, 4, BigDecimal.valueOf(row.getCount()));
        }
        r++;

        subTitle(sh, r++, "各账户月末余额");
        r = headRow(sh, r, head, new String[]{"账户", "类型", "期初余额", "月末余额", "虚拟账户"});
        for (MonthlyDtos.AccountBalanceRow row : cf.getMonthEndBalances()) {
            Row rr = sh.createRow(r++);
            cell(rr, 0, row.getAccountName());
            cell(rr, 1, row.getAccountType());
            num(rr, 2, row.getOpeningBalance());
            num(rr, 3, row.getMonthEndBalance());
            cell(rr, 4, row.isVirtual() ? "是" : "否");
        }
        Row cr = sh.createRow(r);
        cell(cr, 0, "月末现金合计(不含虚拟账户)");
        num(cr, 3, cf.getMonthEndCashTotal());
        autosize(sh, 5);
    }

    private void sheetPayable(Workbook wb, CellStyle head, CellStyle title, MonthlyDtos.PackageResp pkg) {
        Sheet sh = wb.createSheet("④往来表");
        MonthlyDtos.PayableSummary ps = pkg.getPayable();
        int r = 0;
        r = titleRow(sh, r, title, "往来表 · 应付供应商 + 平台待结算");
        r = headRow(sh, r, head, new String[]{"供应商", "结算方式", "账期(天)", "期初应付",
                "本期采购", "本期付款", "应付余额", "逾期"});
        for (SettleDtos.SupplierOverviewRow s : ps.getSuppliers()) {
            Row rr = sh.createRow(r++);
            int c = 0;
            cell(rr, c++, s.getSupplierName());
            cell(rr, c++, s.getSettleMethod());
            num(rr, c++, s.getAccountDays() == null ? null : BigDecimal.valueOf(s.getAccountDays()));
            num(rr, c++, s.getOpeningPayable());
            num(rr, c++, s.getPurchaseTotal());
            num(rr, c++, s.getPaymentTotal());
            num(rr, c++, s.getBalance());
            cell(rr, c, s.isOverdue() ? "逾期" + (s.getOverdueDays() == null ? "" : s.getOverdueDays() + "天") : "");
        }
        Row tr = sh.createRow(r++);
        cell(tr, 0, "应付合计");
        num(tr, 6, ps.getPayableTotal());
        r++;
        subTitle(sh, r++, "平台待结算");
        if (ps.getPlatformPending() != null) {
            Row pr = sh.createRow(r++);
            cell(pr, 0, "待结算余额");
            num(pr, 1, ps.getPlatformPending().getPendingBalance());
            Row pr2 = sh.createRow(r);
            cell(pr2, 0, "结算模式 / 是否超期");
            cell(pr2, 1, ps.getPlatformPending().getMode()
                    + (ps.getPlatformPending().isOverdue() ? " · 超期" : ""));
        }
        autosize(sh, 8);
    }

    private void sheetLoss(Workbook wb, CellStyle head, CellStyle title, MonthlyDtos.PackageResp pkg) {
        Sheet sh = wb.createSheet("⑤损耗报表");
        MonthlyDtos.LossSummary ls = pkg.getLoss();
        int r = 0;
        r = titleRow(sh, r, title, "损耗报表(按原因)· " + pkg.getMonth());
        r = headRow(sh, r, head, new String[]{"原因", "行数", "数量", "金额(元)"});
        for (StocktakeDtos.LossStatRow row : ls.getRows()) {
            Row rr = sh.createRow(r++);
            cell(rr, 0, row.getReason());
            num(rr, 1, row.getItemCount() == null ? null : BigDecimal.valueOf(row.getItemCount()));
            num(rr, 2, row.getQty());
            num(rr, 3, row.getAmount());
        }
        Row tr = sh.createRow(r);
        cell(tr, 0, "合计");
        num(tr, 2, ls.getTotalQty());
        num(tr, 3, ls.getTotalAmount());
        autosize(sh, 4);
    }

    private void sheetAsset(Workbook wb, CellStyle head, CellStyle title, MonthlyDtos.PackageResp pkg) {
        Sheet sh = wb.createSheet("⑥资产快照");
        FinReportDtos.AssetSnapshotResp snap = pkg.getAsset().getSnapshot();
        int r = 0;
        r = titleRow(sh, r, title, "资产快照 + 净家底趋势");
        r = headRow(sh, r, head, new String[]{"项目", "金额(元)"});
        if (snap != null) {
            r = kv(sh, r, "① 库存资产(成本)", snap.getInventoryAmount());
            r = kv(sh, r, "② 平台待结算", snap.getPlatformPending());
            r = kv(sh, r, "③ 账户现金合计", snap.getCashTotal());
            r = kv(sh, r, "④ 索赔应收(申请中)", snap.getClaimReceivable());
            r = kv(sh, r, "⑤ 应付供应商合计", snap.getPayableTotal());
            r = kv(sh, r, "净流动资产(①+②+③+④−⑤)", snap.getNetAsset());
        }
        r++;
        subTitle(sh, r++, "净家底趋势(近 12 月归档快照)");
        r = headRow(sh, r, head, new String[]{"月份", "库存", "待结算", "现金", "索赔应收", "应付", "净家底"});
        for (FinReportDtos.SnapshotRow row : pkg.getAsset().getTrend()) {
            Row rr = sh.createRow(r++);
            int c = 0;
            cell(rr, c++, row.getPeriod());
            num(rr, c++, row.getInventoryAmount());
            num(rr, c++, row.getPlatformPending());
            num(rr, c++, row.getCashTotal());
            num(rr, c++, row.getClaimReceivable());
            num(rr, c++, row.getPayableTotal());
            num(rr, c, row.getNetAsset());
        }
        autosize(sh, 7);
    }

    // ============================== Word(第七件《月度经营分析报告》)==============================

    public byte[] exportWord(String month) {
        MonthlyDtos.AnalysisReport report = monthlyAnalysisService.analyze(month, false);
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            // 标题
            XWPFParagraph tp = doc.createParagraph();
            tp.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun tr = tp.createRun();
            tr.setBold(true);
            tr.setFontSize(20);
            tr.setText(report.getPeriod() + " 月度经营分析报告");

            // 生成信息
            XWPFParagraph meta = doc.createParagraph();
            meta.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun mr = meta.createRun();
            mr.setFontSize(9);
            mr.setColor("888888");
            mr.setText("系统自动生成 · " + LocalDateTime.now().format(TS)
                    + " · AI 起草模型:" + report.getModel()
                    + (report.isMock() ? " · [MOCK 占位]" : ""));

            // AI 综述
            heading(doc, "AI 综述(可改两句即交)");
            para(doc, report.getAiText());

            // 七节
            for (MonthlyDtos.AnalysisSection s : report.getSections()) {
                heading(doc, s.getTitle());
                para(doc, s.getNarrative());
                if (!s.getDataPoints().isEmpty()) {
                    dataTable(doc, s.getDataPoints());
                }
            }
            doc.write(bos);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new BizException("月度经营分析报告 Word 导出失败:" + e.getMessage());
        }
    }

    private void heading(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingBefore(180);
        XWPFRun r = p.createRun();
        r.setBold(true);
        r.setFontSize(14);
        r.setText(text);
    }

    private void para(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        XWPFRun r = p.createRun();
        r.setFontSize(11);
        r.setText(text == null ? "" : text);
    }

    /** 数据表:标签 / 数值 / 数据溯源(三列) */
    private void dataTable(XWPFDocument doc, List<MonthlyDtos.DataPoint> points) {
        XWPFTable table = doc.createTable(points.size() + 1, 3);
        table.setWidth("100%");
        XWPFTableRow h = table.getRow(0);
        setCell(h.getCell(0), "指标", true);
        setCell(h.getCell(1), "数值", true);
        setCell(h.getCell(2), "数据溯源", true);
        int i = 1;
        for (MonthlyDtos.DataPoint dp : points) {
            XWPFTableRow row = table.getRow(i++);
            setCell(row.getCell(0), dp.getLabel(), false);
            setCell(row.getCell(1), dp.getValue(), false);
            setCell(row.getCell(2), dp.getSource(), false);
        }
    }

    private void setCell(org.apache.poi.xwpf.usermodel.XWPFTableCell cell, String text, boolean boldHead) {
        cell.removeParagraph(0);
        XWPFParagraph p = cell.addParagraph();
        XWPFRun r = p.createRun();
        r.setFontSize(boldHead ? 10 : 10);
        r.setBold(boldHead);
        if (boldHead) {
            cell.setColor("EFEBDD");
        }
        r.setText(text == null ? "" : text);
    }

    // ============================== POI 小工具 ==============================

    private CellStyle headStyle(Workbook wb) {
        CellStyle st = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        st.setFont(f);
        return st;
    }

    private CellStyle titleStyle(Workbook wb) {
        CellStyle st = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        f.setFontHeightInPoints((short) 13);
        st.setFont(f);
        return st;
    }

    private int titleRow(Sheet sh, int r, CellStyle style, String text) {
        Row row = sh.createRow(r++);
        Cell c = row.createCell(0);
        c.setCellValue(text);
        c.setCellStyle(style);
        return r;
    }

    private void subTitle(Sheet sh, int r, String text) {
        Row row = sh.createRow(r);
        row.createCell(0).setCellValue("— " + text + " —");
    }

    private int headRow(Sheet sh, int r, CellStyle style, String[] cols) {
        Row row = sh.createRow(r++);
        for (int i = 0; i < cols.length; i++) {
            Cell c = row.createCell(i);
            c.setCellValue(cols[i]);
            c.setCellStyle(style);
        }
        return r;
    }

    private int kv(Sheet sh, int r, String k, BigDecimal v) {
        Row row = sh.createRow(r++);
        cell(row, 0, k);
        num(row, 1, v);
        return r;
    }

    private void cell(Row row, int c, String v) {
        row.createCell(c).setCellValue(v == null ? "" : v);
    }

    private void num(Row row, int c, BigDecimal v) {
        Cell cell = row.createCell(c);
        if (v != null) {
            cell.setCellValue(v.doubleValue());
        }
    }

    /** 固定列宽(不用 autoSizeColumn:headless 无字体环境下它会抛异常/依赖 AWT) */
    private void autosize(Sheet sh, int cols) {
        for (int i = 0; i < cols; i++) {
            sh.setColumnWidth(i, i == 0 || i == 1 ? 5200 : 3600);
        }
    }
}
