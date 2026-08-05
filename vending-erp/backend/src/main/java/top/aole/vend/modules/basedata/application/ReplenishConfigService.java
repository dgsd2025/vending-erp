package top.aole.vend.modules.basedata.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.domain.entity.ReplenishConfig;
import top.aole.vend.modules.basedata.infrastructure.mapper.ReplenishConfigMapper;

import java.math.BigDecimal;
import java.util.List;

/**
 * 补货参数应用服务:(R,S) 模型输入的读写。
 * 全局(machine=0,product=0)+ 按 SKU/机器/机器×SKU 覆盖;铁律:每次改动写 op_log(旧值→新值→改动人)。
 */
@Service
@RequiredArgsConstructor
public class ReplenishConfigService {

    private final ReplenishConfigMapper configMapper;
    private final OpLogService opLogService;

    /** 全部参数列表(全局 + 各覆盖行) */
    public List<ReplenishConfig> list() {
        return configMapper.selectList(new LambdaQueryWrapper<ReplenishConfig>()
                .orderByAsc(ReplenishConfig::getMachineId)
                .orderByAsc(ReplenishConfig::getProductId));
    }

    /** 取全局参数(没有则返回内置默认值,不落库) */
    public ReplenishConfig getGlobal() {
        ReplenishConfig global = findByScope(0L, 0L);
        if (global == null) {
            global = new ReplenishConfig();
            global.setScopeType("全局");
            global.setMachineId(0L);
            global.setProductId(0L);
            global.setCycleDays(15);
            global.setServiceLevel(new BigDecimal("0.9500"));
            global.setLeadTimeDays(BigDecimal.ONE);
        }
        return global;
    }

    /**
     * 保存(upsert):按 (machineId, productId) 找到即更新,找不到即新增。
     * scope_type 由后端推导,防止前端传错;每次改动写 op_log。
     */
    @Transactional(rollbackFor = Exception.class)
    public ReplenishConfig save(ReplenishConfig incoming, String operator) {
        long machineId = incoming.getMachineId() == null ? 0L : incoming.getMachineId();
        long productId = incoming.getProductId() == null ? 0L : incoming.getProductId();
        if (incoming.getCycleDays() != null && incoming.getCycleDays() <= 0) {
            throw new BizException("补货周期 R 必须大于 0 天");
        }
        if (incoming.getServiceLevel() != null
                && (incoming.getServiceLevel().compareTo(new BigDecimal("0.5")) < 0
                || incoming.getServiceLevel().compareTo(BigDecimal.ONE) >= 0)) {
            throw new BizException("服务水平应在 0.5 ~ 1 之间(如 0.95)");
        }
        incoming.setMachineId(machineId);
        incoming.setProductId(productId);
        incoming.setScopeType(deriveScope(machineId, productId));

        ReplenishConfig existing = findByScope(machineId, productId);
        if (existing == null) {
            incoming.setId(null);
            configMapper.insert(incoming);
            opLogService.record(operator, "改参数", "replenish_config", incoming.getId(), null, incoming);
            return incoming;
        }
        incoming.setId(existing.getId());
        configMapper.updateById(incoming);
        ReplenishConfig after = configMapper.selectById(existing.getId());
        opLogService.record(operator, "改参数", "replenish_config", existing.getId(), existing, after);
        return after;
    }

    /** 删除覆盖行(回落到全局);全局行不允许删 */
    @Transactional(rollbackFor = Exception.class)
    public void deleteOverride(Long id, String operator) {
        ReplenishConfig before = configMapper.selectById(id);
        if (before == null) {
            throw new BizException("参数不存在:id=" + id);
        }
        if (before.getMachineId() == 0 && before.getProductId() == 0) {
            throw new BizException("全局参数不允许删除,只能修改");
        }
        configMapper.deleteById(id);
        opLogService.record(operator, "删参数覆盖", "replenish_config", id, before, null);
    }

    private ReplenishConfig findByScope(Long machineId, Long productId) {
        return configMapper.selectOne(new LambdaQueryWrapper<ReplenishConfig>()
                .eq(ReplenishConfig::getMachineId, machineId)
                .eq(ReplenishConfig::getProductId, productId));
    }

    private String deriveScope(long machineId, long productId) {
        if (machineId == 0 && productId == 0) {
            return "全局";
        }
        if (machineId == 0) {
            return "SKU";
        }
        if (productId == 0) {
            return "机器";
        }
        return "机器×SKU";
    }
}
