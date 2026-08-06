package top.aole.vend.modules.expense.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import top.aole.vend.modules.basedata.domain.entity.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 设备台账(yc_vend_equipment):回本进度展示,不进利润表与流水
 * (现金支出已在支出单确认时进「杂费」行;折余价值仅展示用,单台<5000 一次性费用化)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("yc_vend_equipment")
public class Equipment extends BaseEntity {

    public static final String STATUS_IN_USE = "在用";
    public static final String STATUS_SCRAPPED = "报废";
    public static final String STATUS_SOLD = "出售";
    /** 退回(M3-9 七律修复:来源支出单被红冲,设备已退——台账行不删,标记留痕) */
    public static final String STATUS_RETURNED = "退回";

    public static final Set<String> VALID_STATUS = new LinkedHashSet<>(Arrays.asList(
            STATUS_IN_USE, STATUS_SCRAPPED, STATUS_SOLD, STATUS_RETURNED));

    private String equipName;

    /** 关联机器(售卖机本体时) */
    private Long machineId;

    private BigDecimal buyPrice;

    private LocalDate buyDate;

    /** 折余价值(展示用,默认=购入价,可手工调) */
    private BigDecimal residualValue;

    /** 在用/报废/出售 */
    private String equipStatus;

    /** 来源支出单 */
    private Long expenseId;
}
