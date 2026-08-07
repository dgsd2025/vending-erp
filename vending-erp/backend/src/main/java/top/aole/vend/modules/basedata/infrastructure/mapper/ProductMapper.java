package top.aole.vend.modules.basedata.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import top.aole.vend.modules.basedata.domain.entity.Product;

/** Product 表 Mapper(MyBatis-Plus 通用 CRUD)。 */
@Mapper
public interface ProductMapper extends BaseMapper<Product> {
}
