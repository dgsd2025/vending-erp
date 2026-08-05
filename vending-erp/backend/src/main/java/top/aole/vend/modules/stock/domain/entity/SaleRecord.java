package top.aole.vend.modules.stock.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 销售记录(yc_vend_sale_record)。
 * 库存引擎只读它(机器库存增量推算的"销售-"来源);写入方在导入中心(M1-3)。
 * 补货口径(P0-3):正常+兑换=真消耗库存,不含测试/退款。
 */
@Data
@TableName("yc_vend_sale_record")
public class SaleRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;
    private String orderNo;
    private Long machineId;
    private String deviceId;
    private String slotNo;
    private String aliasCodeRaw;
    private String aliasNameRaw;
    private Long productId;
    private BigDecimal qty;
    private BigDecimal amountReceived;
    private BigDecimal unitPrice;
    private String payMethod;
    /** 正常/兑换/退款/测试/线下补录 */
    private String orderType;
    /** 业务时间戳(出货时间):机器库存增量推算按此排序 */
    private LocalDateTime bizTime;
    private String bizPeriod;
    private String bookPeriod;
    private BigDecimal costAmount;
    private Long settlementId;
    private Long importBatchId;
    private Boolean offlineFlag;

    private Long createUser;
    private Long updateUser;
}
