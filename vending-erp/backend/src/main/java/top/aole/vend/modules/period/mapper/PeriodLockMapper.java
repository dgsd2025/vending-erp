package top.aole.vend.modules.period.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import top.aole.vend.modules.period.domain.entity.PeriodLock;

import java.time.LocalDateTime;

@Mapper
public interface PeriodLockMapper extends BaseMapper<PeriodLock> {

    /** 当前锁账线 = 未删锁记录的最大 period(该月及之前全部锁定);无锁返回 NULL */
    @Select("SELECT MAX(period) FROM yc_vend_period_lock WHERE is_deleted=0")
    String lockLine();

    /**
     * 找同月已解锁(逻辑删)的旧锁记录:uk_lock_period(tenant_id,period) 连同已删行占位,
     * 解锁后再锁同月必须**复活旧行**而不能 insert(2026-08-06 浏览器真测踩雷:Duplicate entry)。
     */
    @Select("SELECT id FROM yc_vend_period_lock WHERE period=#{period} AND is_deleted=1 ORDER BY id LIMIT 1")
    Long findUnlockedId(@Param("period") String period);

    /** 复活旧锁记录(重新锁定同一个月) */
    @Update("UPDATE yc_vend_period_lock SET is_deleted=0, locked_at=#{lockedAt}, locked_by=#{lockedBy}, " +
            "lock_note=#{lockNote}, update_user=#{lockedBy} WHERE id=#{id}")
    int relock(@Param("id") Long id, @Param("lockedAt") LocalDateTime lockedAt,
               @Param("lockedBy") Long lockedBy, @Param("lockNote") String lockNote);
}
