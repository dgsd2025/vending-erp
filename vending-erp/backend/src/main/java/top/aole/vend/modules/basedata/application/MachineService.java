package top.aole.vend.modules.basedata.application;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.domain.entity.Machine;
import top.aole.vend.modules.basedata.domain.entity.Slot;
import top.aole.vend.modules.basedata.infrastructure.mapper.MachineMapper;
import top.aole.vend.modules.basedata.infrastructure.mapper.SlotMapper;
import top.aole.vend.modules.basedata.interfaces.dto.Dtos;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 机器与货道应用服务。
 * device_id(后台设备 ID)唯一,是与 fanmaiji.top 对齐的锚点;停用保留历史;货道 uk=(machine_id, slot_no)。
 */
@Service
@RequiredArgsConstructor
public class MachineService {

    public static final List<String> MACHINE_STATUSES = Arrays.asList("在线", "故障", "停用");
    private static final List<String> SLOT_STATUSES = Arrays.asList("正常", "停用", "故障");

    private final MachineMapper machineMapper;
    private final SlotMapper slotMapper;
    private final ProductService productService;
    private final OpLogService opLogService;

    public Page<Machine> page(long current, long size, String keyword, String machineStatus) {
        LambdaQueryWrapper<Machine> qw = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(keyword)) {
            qw.and(w -> w.like(Machine::getMachineName, keyword)
                    .or().like(Machine::getMachineCode, keyword)
                    .or().like(Machine::getDeviceId, keyword));
        }
        qw.eq(StrUtil.isNotBlank(machineStatus), Machine::getMachineStatus, machineStatus)
                .orderByAsc(Machine::getMachineCode);
        return machineMapper.selectPage(new Page<>(current, size), qw);
    }

    public Machine getById(Long id) {
        Machine machine = machineMapper.selectById(id);
        if (machine == null) {
            throw new BizException("机器不存在:id=" + id);
        }
        return machine;
    }

    @Transactional(rollbackFor = Exception.class)
    public Machine create(Machine machine, String operator) {
        if (StrUtil.isBlank(machine.getMachineCode()) || StrUtil.isBlank(machine.getMachineName())
                || StrUtil.isBlank(machine.getDeviceId())) {
            throw new BizException("机器编号/名称/后台设备ID 不能为空");
        }
        assertUnique(Machine::getDeviceId, machine.getDeviceId(), "后台设备ID已存在:");
        assertUnique(Machine::getMachineCode, machine.getMachineCode(), "机器编号已存在:");
        machine.setId(null);
        if (StrUtil.isBlank(machine.getMachineStatus())) {
            machine.setMachineStatus("在线");
        }
        machineMapper.insert(machine);
        opLogService.record(operator, "新建", "machine", machine.getId(), null, machine);
        return machine;
    }

    @Transactional(rollbackFor = Exception.class)
    public Machine update(Long id, Machine incoming, String operator) {
        Machine before = getById(id);
        if (StrUtil.isNotBlank(incoming.getDeviceId()) && !incoming.getDeviceId().equals(before.getDeviceId())) {
            assertUnique(Machine::getDeviceId, incoming.getDeviceId(), "后台设备ID已存在:");
        }
        incoming.setId(id);
        incoming.setMachineCode(before.getMachineCode());
        incoming.setMachineStatus(before.getMachineStatus()); // 状态走 changeStatus
        machineMapper.updateById(incoming);
        Machine after = machineMapper.selectById(id);
        opLogService.record(operator, "修改", "machine", id, before, after);
        return after;
    }

    /** 机器状态流转:在线/故障/停用(撤点应先退库单再停用——退库单属 M1-4 单据引擎,此处仅提示) */
    @Transactional(rollbackFor = Exception.class)
    public Machine changeStatus(Long id, String targetStatus, String operator) {
        if (!MACHINE_STATUSES.contains(targetStatus)) {
            throw new BizException("非法机器状态:" + targetStatus + ",只允许 在线/故障/停用");
        }
        Machine before = getById(id);
        if (targetStatus.equals(before.getMachineStatus())) {
            return before;
        }
        Machine patch = new Machine();
        patch.setId(id);
        patch.setMachineStatus(targetStatus);
        machineMapper.updateById(patch);
        Machine after = machineMapper.selectById(id);
        opLogService.record(operator, "改状态", "machine", id, before, after);
        return after;
    }

    // ---------- 货道 ----------

    /** 按机器列货道(货道号升序) */
    public List<Slot> listSlots(Long machineId) {
        getById(machineId);
        return slotMapper.selectList(new LambdaQueryWrapper<Slot>()
                .eq(Slot::getMachineId, machineId)
                .orderByAsc(Slot::getSlotNo));
    }

    /**
     * 批量初始化货道:机器 × 货道号。给 slotNos 用列表,给 slotCount 自动生成 01..NN;
     * 已存在的货道号跳过(幂等),完成后同步 machine.slot_count。
     */
    @Transactional(rollbackFor = Exception.class)
    public List<Slot> initSlots(Long machineId, Dtos.SlotInitReq req, String operator) {
        Machine machine = getById(machineId);
        List<String> wanted = new ArrayList<>();
        if (req.getSlotNos() != null && !req.getSlotNos().isEmpty()) {
            for (String no : req.getSlotNos()) {
                if (StrUtil.isNotBlank(no)) {
                    wanted.add(no.trim());
                }
            }
        } else if (req.getSlotCount() != null && req.getSlotCount() > 0) {
            if (req.getSlotCount() > 200) {
                throw new BizException("货道数不能超过 200");
            }
            for (int i = 1; i <= req.getSlotCount(); i++) {
                wanted.add(String.format("%02d", i));
            }
        } else {
            throw new BizException("请提供 slotNos 列表或 slotCount 数量");
        }

        Set<String> existing = slotMapper.selectList(new LambdaQueryWrapper<Slot>()
                        .eq(Slot::getMachineId, machineId)).stream()
                .map(Slot::getSlotNo).collect(Collectors.toCollection(HashSet::new));
        BigDecimal capacity = req.getCapacity() == null ? BigDecimal.ZERO : req.getCapacity();
        List<Slot> created = new ArrayList<>();
        for (String no : wanted) {
            if (!existing.add(no)) {
                continue; // 已存在跳过,幂等
            }
            Slot slot = new Slot();
            slot.setMachineId(machineId);
            slot.setSlotNo(no);
            slot.setCapacity(capacity);
            slot.setCurrentQty(BigDecimal.ZERO);
            slot.setSlotStatus("正常");
            slotMapper.insert(slot);
            created.add(slot);
        }
        // 同步机器货道数
        Long total = slotMapper.selectCount(new LambdaQueryWrapper<Slot>().eq(Slot::getMachineId, machineId));
        Machine patch = new Machine();
        patch.setId(machineId);
        patch.setSlotCount(total == null ? 0 : total.intValue());
        machineMapper.updateById(patch);
        opLogService.record(operator, "批量初始化货道", "machine", machineId,
                machine.getSlotCount(), "新增" + created.size() + "道,共" + total + "道");
        return created;
    }

    /** 货道编辑:绑 SKU(productId=0 解绑)/容量/状态 */
    @Transactional(rollbackFor = Exception.class)
    public Slot updateSlot(Long slotId, Dtos.SlotUpdateReq req, String operator) {
        Slot before = slotMapper.selectById(slotId);
        if (before == null) {
            throw new BizException("货道不存在:id=" + slotId);
        }
        if (req.getSlotStatus() != null && !SLOT_STATUSES.contains(req.getSlotStatus())) {
            throw new BizException("非法货道状态:" + req.getSlotStatus() + ",只允许 正常/停用/故障");
        }
        if (req.getProductId() != null && req.getProductId() > 0) {
            productService.getById(req.getProductId()); // 绑定前校验商品存在
        }
        LambdaUpdateWrapper<Slot> uw = new LambdaUpdateWrapper<Slot>().eq(Slot::getId, slotId);
        if (req.getProductId() != null) {
            uw.set(Slot::getProductId, req.getProductId() == 0 ? null : req.getProductId());
        }
        if (req.getCapacity() != null) {
            uw.set(Slot::getCapacity, req.getCapacity());
        }
        if (req.getSlotStatus() != null) {
            uw.set(Slot::getSlotStatus, req.getSlotStatus());
        }
        slotMapper.update(null, uw);
        Slot after = slotMapper.selectById(slotId);
        opLogService.record(operator, "修改货道", "slot", slotId, before, after);
        return after;
    }

    private void assertUnique(com.baomidou.mybatisplus.core.toolkit.support.SFunction<Machine, ?> column,
                              String value, String messagePrefix) {
        Long dup = machineMapper.selectCount(new LambdaQueryWrapper<Machine>().eq(column, value));
        if (dup != null && dup > 0) {
            throw new BizException(messagePrefix + value);
        }
    }
}
