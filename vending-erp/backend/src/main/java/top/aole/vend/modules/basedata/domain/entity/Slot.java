package top.aole.vend.modules.basedata.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 货道(yc_vend_slot)。uk=(machine_id, slot_no)。
 * current_qty 为推算值,权威以后台缺货页/盘点为准。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("yc_vend_slot")
public class Slot extends BaseEntity {

    /** 机器 ID */
    private Long machineId;

    /** 货道号(与后台一致) */
    private String slotNo;

    /** 当前绑定 SKU(可空=空货道) */
    private Long productId;

    /** 货道容量(机器层补货水位 S 的硬上限) */
    private BigDecimal capacity;

    /** 当前数量(推算值) */
    private BigDecimal currentQty;

    /** 货道状态:正常/停用/故障 */
    private String slotStatus;
}
