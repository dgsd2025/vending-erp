package top.aole.vend.modules.basedata.application;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.domain.entity.Supplier;
import top.aole.vend.modules.basedata.infrastructure.mapper.SupplierMapper;

import java.util.Arrays;
import java.util.List;

/**
 * 供应商应用服务。停用保留历史往来(永不删);期初应付上线后改动应走调整留痕。
 */
@Service
@RequiredArgsConstructor
public class SupplierService {

    public static final List<String> COOP_STATUSES = Arrays.asList("合作中", "停用");
    private static final List<String> SETTLE_METHODS = Arrays.asList("现结", "月结", "预付");

    private final SupplierMapper supplierMapper;
    private final OpLogService opLogService;

    public Page<Supplier> page(long current, long size, String keyword, String coopStatus) {
        LambdaQueryWrapper<Supplier> qw = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(keyword)) {
            qw.and(w -> w.like(Supplier::getSupplierName, keyword)
                    .or().like(Supplier::getSupplierCode, keyword));
        }
        qw.eq(StrUtil.isNotBlank(coopStatus), Supplier::getCoopStatus, coopStatus)
                .orderByAsc(Supplier::getSupplierCode);
        return supplierMapper.selectPage(new Page<>(current, size), qw);
    }

    public Supplier getById(Long id) {
        Supplier supplier = supplierMapper.selectById(id);
        if (supplier == null) {
            throw new BizException("供应商不存在:id=" + id);
        }
        return supplier;
    }

    @Transactional(rollbackFor = Exception.class)
    public Supplier create(Supplier supplier, String operator) {
        if (StrUtil.isBlank(supplier.getSupplierCode()) || StrUtil.isBlank(supplier.getSupplierName())) {
            throw new BizException("供应商编码与名称不能为空");
        }
        validateSettleMethod(supplier.getSettleMethod());
        Long dup = supplierMapper.selectCount(new LambdaQueryWrapper<Supplier>()
                .eq(Supplier::getSupplierCode, supplier.getSupplierCode()));
        if (dup != null && dup > 0) {
            throw new BizException("供应商编码已存在:" + supplier.getSupplierCode());
        }
        supplier.setId(null);
        if (StrUtil.isBlank(supplier.getCoopStatus())) {
            supplier.setCoopStatus("合作中");
        }
        supplierMapper.insert(supplier);
        opLogService.record(operator, "新建", "supplier", supplier.getId(), null, supplier);
        return supplier;
    }

    @Transactional(rollbackFor = Exception.class)
    public Supplier update(Long id, Supplier incoming, String operator) {
        Supplier before = getById(id);
        validateSettleMethod(incoming.getSettleMethod());
        incoming.setId(id);
        incoming.setSupplierCode(before.getSupplierCode());
        incoming.setCoopStatus(before.getCoopStatus()); // 状态走 changeStatus
        supplierMapper.updateById(incoming);
        Supplier after = supplierMapper.selectById(id);
        opLogService.record(operator, "修改", "supplier", id, before, after);
        return after;
    }

    /** 合作状态流转:合作中/停用(停用=保留历史,不是删除) */
    @Transactional(rollbackFor = Exception.class)
    public Supplier changeStatus(Long id, String targetStatus, String operator) {
        if (!COOP_STATUSES.contains(targetStatus)) {
            throw new BizException("非法合作状态:" + targetStatus + ",只允许 合作中/停用");
        }
        Supplier before = getById(id);
        if (targetStatus.equals(before.getCoopStatus())) {
            return before;
        }
        Supplier patch = new Supplier();
        patch.setId(id);
        patch.setCoopStatus(targetStatus);
        supplierMapper.updateById(patch);
        Supplier after = supplierMapper.selectById(id);
        opLogService.record(operator, "改状态", "supplier", id, before, after);
        return after;
    }

    private void validateSettleMethod(String settleMethod) {
        if (StrUtil.isNotBlank(settleMethod) && !SETTLE_METHODS.contains(settleMethod)) {
            throw new BizException("非法结算方式:" + settleMethod + ",只允许 现结/月结/预付");
        }
    }
}
