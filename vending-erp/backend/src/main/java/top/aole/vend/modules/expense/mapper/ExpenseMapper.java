package top.aole.vend.modules.expense.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import top.aole.vend.modules.expense.domain.entity.Expense;

@Mapper
public interface ExpenseMapper extends BaseMapper<Expense> {
}
