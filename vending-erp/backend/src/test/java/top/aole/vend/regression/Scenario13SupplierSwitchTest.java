package top.aole.vend.regression;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 穿行场景13:供应商切换期(审计结论:通,毛刺=deduction 缺 supplier_id,抵扣会串户;
 * P2-11 修复:deduction.supplier_id NOT NULL 必填 + 结算单只允许带入同供应商待抵扣)。
 *
 * M1 已落地:supplier_id 非空约束(数据库层硬拦)+ 供应商维度索引 + 结算单只读校验字段通道。
 * M3 待接:结算单带入待抵扣时的同供应商业务校验(settle_bill.deduction_amount)。
 */
class Scenario13SupplierSwitchTest extends RegressionSupport {

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
    @Disabled("M3-pending:结算单服务未实现——缺\"settle_bill 只允许带入同供应商待抵扣(deduction_amount 校验)\"业务断言;数据库层防串户约束已在本类前两例验过")
    void settleBillOnlyPullsSameSupplierDeduction() {
        // M3 实装后:新供应商结算单尝试带入老供应商待抵扣 → 拒绝
    }
}
