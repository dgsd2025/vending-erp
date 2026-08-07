package top.aole.vend.modules.money.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import top.aole.vend.modules.basedata.domain.entity.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 资金账户(yc_vend_account):真实 4 类(微信/支付宝/银行卡/现金)+ 虚拟 2 类(平台待结算/老板垫付)。
 *
 * 口径(附录D):余额 = 期初 + Σ流水,实时推算**不落静态余额字段**;
 * 期初余额只能设一次(opening_set_at 非空即锁定),后续改动一律走"资金调整单"(M3-5)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("yc_vend_account")
public class Account extends BaseEntity {

    /** 账户名(uk:tenant_id+account_name) */
    private String accountName;

    /** 类型中文值:微信/支付宝/银行卡/现金/平台待结算/老板垫付 */
    private String accountType;

    /** 虚拟账户:1=平台待结算/老板垫付(不参与钱盘实数核对,不可手工收支) */
    private Boolean isVirtual;

    /** 期初余额:只能设一次 */
    private BigDecimal openingBalance;

    /** 期初设定时间(非空即锁定,再改必须走资金调整单) */
    private LocalDateTime openingSetAt;
}
