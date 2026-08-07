package top.aole.vend.modules.stocktake.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import top.aole.vend.modules.stocktake.domain.entity.StocktakeItem;

@Mapper
public interface StocktakeItemMapper extends BaseMapper<StocktakeItem> {
}
