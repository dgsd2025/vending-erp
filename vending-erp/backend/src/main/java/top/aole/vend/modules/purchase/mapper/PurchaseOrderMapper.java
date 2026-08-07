package top.aole.vend.modules.purchase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import top.aole.vend.modules.purchase.domain.entity.PurchaseOrder;

@Mapper
public interface PurchaseOrderMapper extends BaseMapper<PurchaseOrder> {
}
