package top.aole.vend.modules.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import top.aole.vend.modules.task.domain.entity.TaskInstance;

@Mapper
public interface TaskInstanceMapper extends BaseMapper<TaskInstance> {
}
