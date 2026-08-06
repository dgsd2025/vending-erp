package top.aole.vend.modules.settlement.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import top.aole.vend.modules.settlement.domain.entity.Settlement;

/** 平台结算单/商户账单核对单 mapper */
@Mapper
public interface SettlementMapper extends BaseMapper<Settlement> {
}
