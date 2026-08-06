package top.aole.vend.modules.settle.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.application.OpLogService;
import top.aole.vend.modules.basedata.infrastructure.mapper.SupplierMapper;
import top.aole.vend.modules.settle.domain.entity.Deduction;
import top.aole.vend.modules.settle.dto.SettleDtos;
import top.aole.vend.modules.settle.mapper.DeductionMapper;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 抵扣确认单服务(§9.3-6):兑换/厂家补贴 → 待抵扣额 → 进下张应付结算单。
 *
 * supplier_id 必填防串户(P2-11,数据库层 NOT NULL 双保险);
 * 厂家结账凭证走通用凭证件(attachment refType=deduction);
 * 状态:待抵扣 → 已抵扣(used_settle_bill_id 指向结算单)/作废;红冲连锁会释放回待抵扣。
 */
@Service
@RequiredArgsConstructor
public class DeductionService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private final DeductionMapper deductionMapper;
    private final SupplierMapper supplierMapper;
    private final OpLogService opLogService;

    @Transactional(rollbackFor = Exception.class)
    public Long create(SettleDtos.DeductionCreateReq req, Long userId, String operator) {
        if (req.getSupplierId() == null) {
            throw new BizException("抵扣确认单必须指定供应商(P2-11 防串户):结算单只允许带入同供应商待抵扣");
        }
        if (supplierMapper.selectById(req.getSupplierId()) == null) {
            throw new BizException("供应商不存在:id=" + req.getSupplierId());
        }
        if (!"兑换".equals(req.getDedSource()) && !"厂家补贴".equals(req.getDedSource())) {
            throw new BizException("抵扣来源只能是 兑换/厂家补贴:" + req.getDedSource());
        }
        Deduction ded = new Deduction();
        ded.setDedNo(nextDedNo());
        ded.setSupplierId(req.getSupplierId());
        ded.setDedSource(req.getDedSource());
        ded.setAmount(req.getAmount());
        ded.setDedStatus(Deduction.ST_PENDING);
        ded.setPeriodDesc(req.getPeriodDesc());
        ded.setCreateUser(userId);
        deductionMapper.insert(ded);
        opLogService.record(operator, "录入抵扣确认单", "deduction", ded.getId(), null,
                String.format("供应商%d %s %s(%s)", req.getSupplierId(), req.getDedSource(),
                        req.getAmount(), req.getPeriodDesc() == null ? "-" : req.getPeriodDesc()));
        return ded.getId();
    }

    /** 某供应商抵扣单列表(status 空=全部;待抵扣列表是结算单确认弹窗的勾选源) */
    public List<Deduction> list(Long supplierId, String status) {
        return deductionMapper.selectList(new LambdaQueryWrapper<Deduction>()
                .eq(supplierId != null, Deduction::getSupplierId, supplierId)
                .eq(status != null && !status.isEmpty(), Deduction::getDedStatus, status)
                .orderByDesc(Deduction::getId));
    }

    /** 作废(仅待抵扣可作废;已抵扣要先红冲结算链) */
    @Transactional(rollbackFor = Exception.class)
    public void voidDeduction(Long id, Long userId, String operator) {
        Deduction ded = deductionMapper.selectById(id);
        if (ded == null) {
            throw new BizException("抵扣确认单不存在:id=" + id);
        }
        if (!Deduction.ST_PENDING.equals(ded.getDedStatus())) {
            throw new BizException(String.format("抵扣确认单[%s]状态为[%s],仅[待抵扣]可作废", ded.getDedNo(), ded.getDedStatus()));
        }
        ded.setDedStatus(Deduction.ST_VOID);
        ded.setUpdateUser(userId);
        deductionMapper.updateById(ded);
        opLogService.record(operator, "作废抵扣确认单", "deduction", id, ded.getDedNo(), null);
    }

    private String nextDedNo() {
        String like = "DK-" + LocalDate.now().format(DAY) + "-";
        Long count = deductionMapper.selectCount(new LambdaQueryWrapper<Deduction>()
                .likeRight(Deduction::getDedNo, like));
        return like + String.format("%03d", count + 1);
    }
}
