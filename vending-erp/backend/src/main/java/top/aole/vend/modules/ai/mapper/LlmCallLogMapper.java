package top.aole.vend.modules.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import top.aole.vend.modules.ai.domain.entity.LlmCallLog;

@Mapper
public interface LlmCallLogMapper extends BaseMapper<LlmCallLog> {
}
