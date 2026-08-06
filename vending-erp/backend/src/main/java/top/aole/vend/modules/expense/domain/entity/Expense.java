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
 * 支出单(yc_vend_expense,§9.3 场景8 杂费/设备):利润表「杂费」行来源。
 *
 * 流程:录入(待确认)→ 传凭证(付款截图/发票,refType=expense)→ 确认:
 * MoneyPostingEvent(支出)落流水;类别=设备购置 同步建 equipment 台账行(购入价=金额)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("yc_vend_expense")
public class Expense extends BaseEntity {

    public static final String STATUS_PENDING = "待确认";
    /** 确认中(M3-9 P0-2 条件更新抢占过渡态:仅存在于确认事务内) */
    public static final String STATUS_CONFIRMING = "确认中";
    public static final String STATUS_DONE = "已完成";
    /** 已作废(M3-9 七律修复:仅待确认可作废——钱没动) */
    public static final String STATUS_VOID = "已作废";
    /** 已红冲(已确认的唯一逆向:被负额红冲行承接) */
    public static final String STATUS_RED_FLUSHED = "已红冲";
    /** 红冲行自身状态(负额,反向流水已落) */
    public static final String STATUS_RED = "红冲";

    public static final String CATEGORY_UTILITY = "电费";
    public static final String CATEGORY_REPAIR = "维修";
    public static final String CATEGORY_MISC = "杂支";
    public static final String CATEGORY_EQUIPMENT = "设备购置";

    public static final Set<String> VALID_CATEGORIES = new LinkedHashSet<>(Arrays.asList(
            CATEGORY_UTILITY, CATEGORY_REPAIR, CATEGORY_MISC, CATEGORY_EQUIPMENT));

    private String expNo;

    /** 类别:电费/维修/杂支/设备购置 */
    private String category;

    private BigDecimal amount;

    /** 支出账户(真实账户) */
    private Long accountId;

    /** 是否设备:1=已同步写入 equipment 台账 */
    private Boolean isEquipment;

    /** 关联设备台账行(确认时回填) */
    private Long equipmentId;

    /** 设备名称(类别=设备购置时必填;确认时据此建台账行) */
    private String equipName;

    private String expStatus;

    private LocalDate bizDate;

    /** 入账月(确认时按锁账口径推导,与流水同口径) */
    private String bookPeriod;

    private String remark;

    /** 红冲指向的原支出单ID(本行=负额红冲行;原单被红冲后 exp_status=已红冲) */
    private Long redFlushOf;
}
