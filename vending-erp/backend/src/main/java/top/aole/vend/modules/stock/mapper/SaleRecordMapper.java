package top.aole.vend.modules.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import top.aole.vend.modules.stock.domain.entity.SaleRecord;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Mapper
public interface SaleRecordMapper extends BaseMapper<SaleRecord> {

    /**
     * 快照时刻之后的出货合计。
     * 口径(P0-3 补货口径):仅 正常+兑换 计入(真消耗库存),不含 测试/退款/线下补录;
     * 线下补录不扣机器账(盘点差异豁免 offline_exempt 承接,穿行场景5)。
     */
    @Select("<script>" +
            "SELECT COALESCE(SUM(qty),0) FROM yc_vend_sale_record " +
            "WHERE machine_id=#{machineId} AND product_id=#{productId} " +
            "AND order_type IN ('正常','兑换') AND is_deleted=0 " +
            "<if test='after != null'>AND biz_time &gt; #{after}</if>" +
            "</script>")
    BigDecimal sumOutboundAfter(@Param("machineId") Long machineId,
                                @Param("productId") Long productId,
                                @Param("after") LocalDateTime after);
}
