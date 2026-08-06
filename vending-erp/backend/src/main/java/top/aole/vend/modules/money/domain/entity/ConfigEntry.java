package top.aole.vend.modules.money.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import top.aole.vend.modules.basedata.domain.entity.BaseEntity;

/**
 * 全局配置键值对(yc_vend_config,V1.0.7):首个键 settle.mode(附录D 结算双模式)。
 * 改动必写 op_log。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("yc_vend_config")
public class ConfigEntry extends BaseEntity {

    /** settle.mode:UNSET(默认待核实)/ PLATFORM(平台归集)/ DIRECT(微信直连) */
    public static final String KEY_SETTLE_MODE = "settle.mode";

    private String configKey;

    private String configValue;

    private String remark;
}
