package top.aole.vend.modules.settle.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.domain.entity.Supplier;
import top.aole.vend.modules.basedata.infrastructure.mapper.SupplierMapper;
import top.aole.vend.modules.settle.domain.entity.Deduction;
import top.aole.vend.modules.settle.domain.entity.SettleBill;
import top.aole.vend.modules.settle.dto.SettleDtos;
import top.aole.vend.modules.settle.mapper.DeductionMapper;
import top.aole.vend.modules.settle.mapper.PayableQueryMapper;
import top.aole.vend.modules.settle.mapper.SettleBillMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 应付往来(§7.3 问2"供应商的钱结了没有"):
 *
 * - 应付余额 = 期初 + Σ已确认采购 − Σ退货 − Σ已用抵扣 − Σ已付款(**实时算,不落表**);
 * - 预付 = 余额为负,显示"预付款";
 * - 月结逾期黄灯:有未付清结算单已过到期日(supplier 账期字段);
 * - 对账单 = 期初 + 明细(拿货/退货/抵扣/付款,含红冲负项行)+ 期末,xlsx 一键导出发微信核对。
 */
@Service
@RequiredArgsConstructor
public class PayableService {

    private final PayableQueryMapper queryMapper;
    private final SupplierMapper supplierMapper;
    private final SettleBillMapper billMapper;
    private final DeductionMapper deductionMapper;

    // ============================== 余额(实时算) ==============================

    /** 应付余额 = 期初 + Σ采购 − Σ退货 − Σ抵扣 − Σ付款 */
    public BigDecimal balance(Long supplierId) {
        return overviewOf(mustGetSupplier(supplierId)).getBalance();
    }

    /** p8 供应商卡列表(全部供应商,含停用——停用保留历史往来) */
    public List<SettleDtos.SupplierOverviewRow> overview() {
        List<SettleDtos.SupplierOverviewRow> rows = new ArrayList<>();
        for (Supplier supplier : supplierMapper.selectList(new LambdaQueryWrapper<Supplier>()
                .orderByAsc(Supplier::getCoopStatus).orderByAsc(Supplier::getId))) {
            rows.add(overviewOf(supplier));
        }
        return rows;
    }

    public SettleDtos.SupplierOverviewRow overviewOf(Supplier supplier) {
        Long id = supplier.getId();
        BigDecimal opening = nz(supplier.getOpeningPayable());
        BigDecimal purchase = queryMapper.purchaseTotal(id).subtract(queryMapper.purchaseRedTotal(id));
        BigDecimal ret = queryMapper.returnTotal(id).subtract(queryMapper.returnRedTotal(id));
        BigDecimal deduction = queryMapper.usedDeductionTotal(id);
        BigDecimal payment = queryMapper.paymentTotal(id);
        BigDecimal balance = opening.add(purchase).subtract(ret).subtract(deduction).subtract(payment);

        SettleDtos.SupplierOverviewRow row = new SettleDtos.SupplierOverviewRow();
        row.setSupplierId(id);
        row.setSupplierCode(supplier.getSupplierCode());
        row.setSupplierName(supplier.getSupplierName());
        row.setContact(supplier.getContact());
        row.setSettleMethod(supplier.getSettleMethod());
        row.setAccountDays(supplier.getAccountDays());
        row.setCoopStatus(supplier.getCoopStatus());
        row.setOpeningPayable(opening);
        row.setPurchaseTotal(purchase);
        row.setReturnTotal(ret);
        row.setDeductionTotal(deduction);
        row.setPaymentTotal(payment);
        row.setBalance(balance);
        row.setPrepay(balance.compareTo(BigDecimal.ZERO) < 0);

        LocalDate earliestDue = queryMapper.earliestUnpaidDueDate(id);
        if (earliestDue != null && earliestDue.isBefore(LocalDate.now())) {
            row.setOverdue(true);
            row.setOverdueDays((int) ChronoUnit.DAYS.between(earliestDue, LocalDate.now()));
        }
        row.setPendingDeductions(deductionMapper.selectCount(new LambdaQueryWrapper<Deduction>()
                .eq(Deduction::getSupplierId, id).eq(Deduction::getDedStatus, Deduction.ST_PENDING)));
        row.setPendingRedBills(billMapper.selectCount(new LambdaQueryWrapper<SettleBill>()
                .eq(SettleBill::getSupplierId, id).eq(SettleBill::getDirection, SettleBill.DIR_RED)
                .eq(SettleBill::getBillStatus, SettleBill.ST_RED_PENDING)));
        return row;
    }

    // ============================== 对账单(期初+明细+期末) ==============================

    public SettleDtos.StatementResp statement(Long supplierId) {
        Supplier supplier = mustGetSupplier(supplierId);
        BigDecimal opening = nz(supplier.getOpeningPayable());

        List<Map<String, Object>> lines = new ArrayList<>();
        lines.addAll(queryMapper.purchaseLines(supplierId));   // 拿货 +(红冲为负行)
        lines.addAll(queryMapper.returnLines(supplierId));     // 退货 −(红冲回正)
        lines.addAll(queryMapper.deductionLines(supplierId));  // 抵扣 −
        lines.addAll(queryMapper.paymentLines(supplierId));    // 付款 −
        lines.sort(Comparator.comparing(m -> String.valueOf(m.get("bizDate"))));

        BigDecimal running = opening;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> line : lines) {
            BigDecimal amount = new BigDecimal(String.valueOf(line.get("amount")));
            String type = String.valueOf(line.get("lineType"));
            // 拿货(含红冲负项)加余额;退货/抵扣/付款减余额
            boolean add = type.startsWith("拿货");
            running = add ? running.add(amount) : running.subtract(amount);
            Map<String, Object> row = new LinkedHashMap<>(line);
            row.put("balance", running);
            out.add(row);
        }
        SettleDtos.StatementResp resp = new SettleDtos.StatementResp();
        resp.setSupplierId(supplierId);
        resp.setSupplierName(supplier.getSupplierName());
        resp.setOpening(opening);
        resp.setClosing(running);
        resp.setLines(out);
        return resp;
    }

    /** 对账单 xlsx(一键导出发微信):期初行 + 明细(日期/事项/拿货+/抵扣−/退货−/付款−/余额)+ 期末行 */
    public byte[] statementXlsx(Long supplierId) {
        SettleDtos.StatementResp stmt = statement(supplierId);
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("对账单");
            Font bold = wb.createFont();
            bold.setBold(true);
            CellStyle boldStyle = wb.createCellStyle();
            boldStyle.setFont(bold);

            int r = 0;
            Row title = sheet.createRow(r++);
            cell(title, 0, stmt.getSupplierName() + " · 往来对账单(期初+明细+期末)", boldStyle);
            Row opening = sheet.createRow(r++);
            cell(opening, 0, "期初应付", boldStyle);
            opening.createCell(6).setCellValue(stmt.getOpening().doubleValue());

            Row header = sheet.createRow(r++);
            String[] heads = {"日期", "单号", "事项", "拿货 +", "抵扣 −", "退货/付款 −", "余额"};
            for (int i = 0; i < heads.length; i++) {
                cell(header, i, heads[i], boldStyle);
            }
            for (Map<String, Object> line : stmt.getLines()) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(String.valueOf(line.get("bizDate")));
                row.createCell(1).setCellValue(String.valueOf(line.get("refNo")));
                String type = String.valueOf(line.get("lineType"));
                String remark = line.get("remark") == null ? "" : String.valueOf(line.get("remark"));
                row.createCell(2).setCellValue(type + (remark.isEmpty() ? "" : "·" + remark));
                double amount = new BigDecimal(String.valueOf(line.get("amount"))).doubleValue();
                if (type.startsWith("拿货")) {
                    row.createCell(3).setCellValue(amount);
                } else if ("抵扣".equals(type)) {
                    row.createCell(4).setCellValue(amount);
                } else {
                    row.createCell(5).setCellValue(amount);
                }
                row.createCell(6).setCellValue(new BigDecimal(String.valueOf(line.get("balance"))).doubleValue());
            }
            Row closing = sheet.createRow(r);
            cell(closing, 0, "期末应付(负数=预付款)", boldStyle);
            closing.createCell(6).setCellValue(stmt.getClosing().doubleValue());
            for (int i = 0; i <= 6; i++) {
                sheet.setColumnWidth(i, 18 * 256);
            }
            wb.write(bos);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new BizException("对账单导出失败:" + e.getMessage());
        }
    }

    // ============================== 内部 ==============================

    private void cell(Row row, int idx, String value, CellStyle style) {
        Cell cell = row.createCell(idx);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private Supplier mustGetSupplier(Long supplierId) {
        Supplier supplier = supplierMapper.selectById(supplierId);
        if (supplier == null) {
            throw new BizException("供应商不存在:id=" + supplierId);
        }
        return supplier;
    }

    private BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
