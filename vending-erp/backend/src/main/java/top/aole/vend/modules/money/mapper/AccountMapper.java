package top.aole.vend.modules.money.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import top.aole.vend.modules.money.domain.entity.Account;

@Mapper
public interface AccountMapper extends BaseMapper<Account> {

    /**
     * 期初一次性锁:仅当 opening_set_at 仍为 NULL 时写入(条件更新防并发双设,
     * 同 P0-A 单据双过账思路)。返回受影响行数,0 = 已被设过。
     */
    @Update("UPDATE yc_vend_account SET opening_balance=#{opening}, opening_set_at=NOW(), " +
            "update_user=#{userId} WHERE id=#{id} AND opening_set_at IS NULL AND is_deleted=0")
    int setOpeningOnce(@Param("id") Long id, @Param("opening") java.math.BigDecimal opening,
                       @Param("userId") Long userId);
}
