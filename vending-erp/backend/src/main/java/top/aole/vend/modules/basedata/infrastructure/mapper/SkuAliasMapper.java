package top.aole.vend.modules.basedata.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import top.aole.vend.modules.basedata.domain.entity.SkuAlias;

/** SkuAlias 表 Mapper(MyBatis-Plus 通用 CRUD)。 */
@Mapper
public interface SkuAliasMapper extends BaseMapper<SkuAlias> {

    /**
     * 解绑 = 物理删除(唯一键 uk_alias 含已删行会挡住重新绑定,故不走逻辑删除;
     * 解绑动作本身在 op_log 里留痕 before_json,历史可追溯)。
     */
    @Delete("DELETE FROM yc_vend_sku_alias WHERE id = #{id}")
    int deletePhysically(@Param("id") Long id);
}
