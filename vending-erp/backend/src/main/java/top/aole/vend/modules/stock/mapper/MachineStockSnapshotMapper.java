package top.aole.vend.modules.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import top.aole.vend.modules.stock.domain.entity.MachineStockSnapshot;

@Mapper
public interface MachineStockSnapshotMapper extends BaseMapper<MachineStockSnapshot> {

    /** 最近一张快照(按快照业务时间,同刻取后写入的) */
    @Select("SELECT * FROM yc_vend_machine_stock_snapshot " +
            "WHERE machine_id=#{machineId} AND product_id=#{productId} AND is_deleted=0 " +
            "ORDER BY snapshot_time DESC, id DESC LIMIT 1")
    MachineStockSnapshot latest(@Param("machineId") Long machineId,
                                @Param("productId") Long productId);
}
