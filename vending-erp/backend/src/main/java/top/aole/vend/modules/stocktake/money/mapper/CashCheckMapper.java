package top.aole.vend.modules.stocktake.money.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import top.aole.vend.modules.stocktake.money.domain.entity.CashCheck;

@Mapper
public interface CashCheckMapper extends BaseMapper<CashCheck> {
}
