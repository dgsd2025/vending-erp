package top.aole.vend.regression;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.basedata.domain.entity.Supplier;
import top.aole.vend.modules.basedata.infrastructure.mapper.SupplierMapper;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.service.DocService;
import top.aole.vend.modules.settle.domain.entity.SettleBill;
import top.aole.vend.modules.settle.dto.SettleDtos;
import top.aole.vend.modules.settle.mapper.DeductionMapper;
import top.aole.vend.modules.settle.mapper.SettleBillMapper;
import top.aole.vend.modules.settle.service.DeductionService;
import top.aole.vend.modules.settle.service.SettleBillService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 穿行场景13:供应商切换期(审计结论:通,毛刺=deduction 缺 supplier_id,抵扣会串户;
 * P2-11 修复:deduction.supplier_id NOT NULL 必填 + 结算单只允许带入同供应商待抵扣)。
 *
 * M1 已落地:supplier_id 非空约束(数据库层硬拦)+ 供应商维度索引 + 结算单只读校验字段通道。
 * M3-2 已接:结算单带入待抵扣时的同供应商业务校验(SettleBillService.confirm),末例转绿。
 */
class Scenario13SupplierSwitchTest extends RegressionSupport {

    private static final String OPERATOR = "回归测试员";

    @Autowired
    private SupplierMapper supplierMapper;
    @Autowired
    private DeductionMapper deductionMapper;
    @Autowired
    private SettleBillMapper billMapper;
    @Autowired
    private DeductionService deductionService;
    @Autowired
    private SettleBillService settleBillService;

    @Test
    @DisplayName("防串户约束(P2-11):deduction.supplier_id NOT NULL,注释写死\"只允许同供应商待抵扣\"")
    void deductionSupplierIdNotNull() {
        Map<String, Object> col = assertColumn("yc_vend_deduction", "supplier_id");
        assertEquals("NO", String.valueOf(col.get("IS_NULLABLE")), "supplier_id 必填(数据库层硬约束)");
        assertNull(col.get("COLUMN_DEFAULT"), "无默认值:不填就报错,防静默串户");
        assertTrue(String.valueOf(col.get("COLUMN_COMMENT")).contains("同供应商"),
                "口径写死:结算单只允许带入同供应商待抵扣");
    }

    @Test
    @DisplayName("数据库硬拦真测:不带 supplier_id 插抵扣单 → 直接被 MySQL 拒;带上则成功且按供应商可查")
    void insertWithoutSupplierRejected() {
        // 老供应商蔡彩云的兑换抵扣,漏填供应商 → 拦
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "INSERT INTO yc_vend_deduction (ded_no, amount) VALUES (?, ?)",
                "DED-RG-001", "351.63"), "缺 supplier_id 必须被数据库拒绝");

        // 填了供应商 → 成功,且供应商维度过滤查得到(结算单带入的取数口)
        jdbc.update("INSERT INTO yc_vend_deduction (ded_no, supplier_id, ded_source, amount, period_desc) "
                        + "VALUES (?, ?, ?, ?, ?)",
                "DED-RG-002", 601L, "兑换", "351.63", "老供应商最后一期兑换");
        jdbc.update("INSERT INTO yc_vend_deduction (ded_no, supplier_id, ded_source, amount, period_desc) "
                        + "VALUES (?, ?, ?, ?, ?)",
                "DED-RG-003", 602L, "厂家补贴", "88.00", "新供应商首期补贴");

        Integer oldSupplier = jdbc.queryForObject(
                "SELECT COUNT(*) FROM yc_vend_deduction WHERE supplier_id=601 AND ded_no LIKE 'DED-RG-%'",
                Integer.class);
        assertEquals(1, oldSupplier, "按供应商过滤各归各户,切换期不串");
    }

    @Test
    @DisplayName("抵扣状态机字段通道:待抵扣默认态 + used_settle_bill_id 回写位 + 供应商状态索引")
    void deductionLifecycleFieldsReady() {
        Map<String, Object> status = assertColumn("yc_vend_deduction", "ded_status");
        assertEquals("待抵扣", String.valueOf(status.get("COLUMN_DEFAULT")));
        assertColumn("yc_vend_deduction", "used_settle_bill_id");
        Integer idx = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() "
                        + "AND table_name='yc_vend_deduction' AND index_name='idx_ded_supplier'",
                Integer.class);
        assertTrue(idx != null && idx > 0, "供应商+状态索引在位(结算单待抵扣查询走它)");
    }

    @Test
    @DisplayName("业务层防串户(M3-2 转绿):新供应商结算单尝试带入老供应商待抵扣 → 拒绝并留在待抵扣;同供应商放行")
    void settleBillOnlyPullsSameSupplierDeduction() {
        // 老供应商蔡彩云(601 概念)与新供应商陈老板:各建档
        Supplier oldS = supplier("蔡彩云-切换期");
        Supplier newS = supplier("陈老板-切换期");
        // 老供应商最后一期兑换抵扣 351.63
        SettleDtos.DeductionCreateReq dedReq = new SettleDtos.DeductionCreateReq();
        dedReq.setSupplierId(oldS.getId());
        dedReq.setDedSource("兑换");
        dedReq.setAmount(new BigDecimal("351.63"));
        dedReq.setPeriodDesc("老供应商最后一期兑换");
        Long oldDed = deductionService.create(dedReq, OP, OPERATOR);

        // 新供应商采购 → 自动生成结算单
        Product p = product("切换期的东鹏", null, null);
        top.aole.vend.modules.doc.dto.DocCreateReq req = req(DocType.PURCHASE_IN, null,
                DocService.SOURCE_MANUAL, LocalDate.now(), new Object[]{p.getId(), "200", "3.5"});
        req.setSupplierId(newS.getId());
        Long docId = docService.createDoc(req, OP);
        docService.submit(docId, OP);
        docService.confirm(docId, OP, false, null);
        SettleBill bill = billMapper.selectOne(new LambdaQueryWrapper<SettleBill>()
                .eq(SettleBill::getSourceDocId, docId).eq(SettleBill::getDirection, "正常"));
        assertNotNull(bill);

        // 串户带入 → 业务层拒绝(数据库层 NOT NULL 已在前两例验过,这里是 deduction_amount 校验层)
        BizException e = assertThrows(BizException.class, () -> settleBillService.confirm(
                bill.getId(), java.util.Collections.singletonList(oldDed), OP, OPERATOR, "老板"));
        assertTrue(e.getMessage().contains("同供应商"), "报错口径指向 P2-11:" + e.getMessage());
        assertEquals("待抵扣", deductionMapper.selectById(oldDed).getDedStatus(), "被拒后原样,不串户");

        // 同供应商待抵扣正常带入
        SettleDtos.DeductionCreateReq sameReq = new SettleDtos.DeductionCreateReq();
        sameReq.setSupplierId(newS.getId());
        sameReq.setDedSource("厂家补贴");
        sameReq.setAmount(new BigDecimal("88.00"));
        Long sameDed = deductionService.create(sameReq, OP, OPERATOR);
        settleBillService.confirm(bill.getId(), java.util.Collections.singletonList(sameDed), OP, OPERATOR, "老板");
        assertEquals("已抵扣", deductionMapper.selectById(sameDed).getDedStatus());
        assertEquals(bill.getId(), deductionMapper.selectById(sameDed).getUsedSettleBillId(), "各归各户");
    }

    private Supplier supplier(String name) {
        Supplier s = new Supplier();
        s.setSupplierCode("RGS13" + SEQ.incrementAndGet());
        s.setSupplierName(name);
        s.setSettleMethod("现结");
        s.setAccountDays(0);
        s.setOpeningPayable(BigDecimal.ZERO);
        s.setCoopStatus("合作中");
        supplierMapper.insert(s);
        return s;
    }
}
