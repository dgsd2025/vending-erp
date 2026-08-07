package top.aole.vend.modules.pdca.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import top.aole.vend.modules.pdca.domain.entity.ActionItem;

/** 改进任务 CRUD */
@Mapper
public interface ActionItemMapper extends BaseMapper<ActionItem> {
}
