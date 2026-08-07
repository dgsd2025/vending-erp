package top.aole.vend.modules.finreport.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import top.aole.vend.modules.basedata.domain.entity.BaseEntity;

import java.math.BigDecimal;

/**
 * 资产快照(yc_vend_asset_snapshot,月度归档):净家底趋势数据源。
 *
 * 公式(§13.1,效力最高):净流动资产 = 库存(成本)+ 平台待结算 + 账户现金
 * + 索赔应收(claim 申请中)− 应付供应商。
 * 归档纪律:同月重复归档 = 幂等重算覆盖(uk_asset_period);月份一旦锁账(period_lock)
 * → 归档快照永不重算(锁账口径,同"旧报表永不重算")。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("yc_vend_asset_snapshot")
public class AssetSnapshot extends BaseEntity {

    /** 快照月 YYYY-MM(uk) */
    private String period;

    /** ① 库存资产(成本价:仓库+机器两级,移动加权) */
    private BigDecimal inventoryAmount;

    /** ② 平台待结算(仅 PLATFORM 模式;UNSET/DIRECT 恒 0) */
    private BigDecimal platformPending;

    /** ③ 各真实账户现金合计(期初+Σ流水) */
    private BigDecimal cashTotal;

    /** ④ 索赔应收(claim 申请中,P0-6 新增项) */
    private BigDecimal claimReceivable;

    /** ⑤ 应付供应商合计(期初+Σ采购−退货−抵扣−付款) */
    private BigDecimal payableTotal;

    /** 净流动资产 = ①+②+③+④−⑤ */
    private BigDecimal netAsset;

    /** 各项下钻明细快照(JSON,归档时的来源列表) */
    private String detailJson;
}
