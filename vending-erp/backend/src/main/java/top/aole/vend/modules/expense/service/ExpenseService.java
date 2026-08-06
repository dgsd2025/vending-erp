package top.aole.vend.modules.expense.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.application.OpLogService;
import top.aole.vend.modules.expense.domain.entity.Equipment;
import top.aole.vend.modules.expense.domain.entity.Expense;
import top.aole.vend.modules.expense.dto.ExpenseDtos;
import top.aole.vend.modules.expense.mapper.EquipmentMapper;
import top.aole.vend.modules.expense.mapper.ExpenseMapper;
import top.aole.vend.modules.money.domain.entity.Account;
import top.aole.vend.modules.money.domain.enums.CashFlowCategory;
import top.aole.vend.modules.money.domain.event.MoneyPostingEvent;
import top.aole.vend.modules.money.mapper.AccountMapper;
import top.aole.vend.modules.money.service.AttachmentService;
import top.aole.vend.modules.period.service.PeriodLockService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 支出单(杂费/设备,M3-4 §9.3 场景8):
 * 录入(待确认)→ 传凭证(付款截图/发票,refType=expense,硬门禁)→ 确认:
 * MoneyPostingEvent(支出单 → 电费/维修/杂支/设备购置,pl_line 统一进「杂费」行)落流水;
 * 类别=设备购置 同步建 equipment 台账行(购入价=金额,折余=购入价,展示用)。
 *
 * 纪律:只发事件过账(cash_flow 唯一写手包私有);台账不进流水(展示回本进度)。
 */
@Service
@RequiredArgsConstructor
public class ExpenseService {

    public static final String REF_DOC_TYPE = "支出单";
    public static final String ATTACH_REF_TYPE = "expense";

    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyy-MM");

    /** 类别 → 流水类别映射(pl_line 由 CashFlowCategory 唯一推导,这里不碰去处) */
    private static final Map<String, CashFlowCategory> CATEGORY_FLOW = new HashMap<>();

    static {
        CATEGORY_FLOW.put(Expense.CATEGORY_UTILITY, CashFlowCategory.UTILITY);
        CATEGORY_FLOW.put(Expense.CATEGORY_REPAIR, CashFlowCategory.REPAIR);
        CATEGORY_FLOW.put(Expense.CATEGORY_MISC, CashFlowCategory.MISC);
        CATEGORY_FLOW.put(Expense.CATEGORY_EQUIPMENT, CashFlowCategory.EQUIPMENT);
    }

    private final ExpenseMapper expenseMapper;
    private final EquipmentMapper equipmentMapper;
    private final AccountMapper accountMapper;
    private final AttachmentService attachmentService;
    private final PeriodLockService periodLockService;
    private final ApplicationEventPublisher publisher;
    private final OpLogService opLogService;

    // ============================== 录入(待确认) ==============================

    @Transactional(rollbackFor = Exception.class)
    public Long create(ExpenseDtos.ExpenseCreateReq req, Long userId, String operator) {
        if (!Expense.VALID_CATEGORIES.contains(req.getCategory())) {
            throw new BizException("支出类别只能是 " + String.join("/", Expense.VALID_CATEGORIES)
                    + ":" + req.getCategory());
        }
        if (req.getAmount() == null || req.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("支出金额必须为正数:" + req.getAmount());
        }
        if (Expense.CATEGORY_EQUIPMENT.equals(req.getCategory()) && StrUtil.isBlank(req.getEquipName())) {
            throw new BizException("类别=设备购置 必须填写设备名称(确认后进设备台账)");
        }
        Account account = accountMapper.selectById(req.getAccountId());
        if (account == null) {
            throw new BizException("支出账户不存在:id=" + req.getAccountId());
        }
        if (Boolean.TRUE.equals(account.getIsVirtual())) {
            throw new BizException("虚拟账户「" + account.getAccountName() + "」不能作为支出账户(选真实账户)");
        }
        Expense exp = new Expense();
        exp.setExpNo(generateNo());
        exp.setCategory(req.getCategory());
        exp.setAmount(req.getAmount());
        exp.setAccountId(req.getAccountId());
        exp.setIsEquipment(false);
        exp.setEquipName(StrUtil.blankToDefault(req.getEquipName(), null));
        exp.setExpStatus(Expense.STATUS_PENDING);
        exp.setBizDate(req.getBizDate() == null ? LocalDate.now() : req.getBizDate());
        exp.setRemark(req.getRemark());
        exp.setCreateUser(userId);
        expenseMapper.insert(exp);
        opLogService.record(operator, "录入支出单", "expense", exp.getId(), null, JSONUtil.toJsonStr(exp));
        return exp.getId();
    }

    // ============================== 确认(凭证门禁 → 落流水/台账) ==============================

    @Transactional(rollbackFor = Exception.class)
    public ExpenseDtos.ExpenseRow confirm(Long id, Long userId, String operator) {
        Expense exp = mustGet(id);
        if (!Expense.STATUS_PENDING.equals(exp.getExpStatus())) {
            throw new BizException(String.format("支出单[%s]状态为[%s],仅待确认可确认",
                    exp.getExpNo(), exp.getExpStatus()));
        }
        // 凭证硬门禁(§9.3 场景8:付款截图/发票必传)
        if (attachmentService.countOf(ATTACH_REF_TYPE, id) == 0) {
            throw new BizException(String.format(
                    "支出单[%s]未上传凭证——先传付款截图/发票(refType=expense)再确认落流水", exp.getExpNo()));
        }
        // 条件更新抢占(M3-9 P0-2):并发双确认会落两笔支出(杂费翻倍+设备台账插两行)
        int claimed = expenseMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Expense>()
                        .eq(Expense::getId, id)
                        .eq(Expense::getExpStatus, Expense.STATUS_PENDING)
                        .set(Expense::getExpStatus, Expense.STATUS_CONFIRMING));
        if (claimed != 1) {
            throw new BizException(String.format("支出单[%s]已被他人处理(并发确认防双记),请刷新查看", exp.getExpNo()));
        }
        String before = JSONUtil.toJsonStr(exp);

        // 过账:唯一合法通道 = 单据确认事件(写手校验失败整体回滚)
        MoneyPostingEvent event = new MoneyPostingEvent(REF_DOC_TYPE, id, userId);
        event.outflow(exp.getAccountId(), exp.getAmount(), CATEGORY_FLOW.get(exp.getCategory()),
                exp.getBizDate().atStartOfDay(),
                String.format("%s %s%s", exp.getCategory(), exp.getExpNo(),
                        StrUtil.isBlank(exp.getRemark()) ? "" : "(" + exp.getRemark() + ")"));
        publisher.publishEvent(event);

        // 类别=设备购置 → 同步建台账行(购入价=金额,折余=购入价;台账只展示不进流水)
        if (Expense.CATEGORY_EQUIPMENT.equals(exp.getCategory())) {
            Equipment equipment = new Equipment();
            equipment.setEquipName(exp.getEquipName());
            equipment.setBuyPrice(exp.getAmount());
            equipment.setBuyDate(exp.getBizDate());
            equipment.setResidualValue(exp.getAmount());
            equipment.setEquipStatus(Equipment.STATUS_IN_USE);
            equipment.setExpenseId(exp.getId());
            equipment.setCreateUser(userId);
            equipmentMapper.insert(equipment);
            exp.setIsEquipment(true);
            exp.setEquipmentId(equipment.getId());
        }

        // 入账月与流水同口径(P0-2:业务月已锁 → 记当月)
        String bizPeriod = exp.getBizDate().format(PERIOD);
        exp.setBookPeriod(periodLockService.isLocked(bizPeriod)
                ? YearMonth.now().format(PERIOD) : bizPeriod);
        exp.setExpStatus(Expense.STATUS_DONE);
        exp.setUpdateUser(userId);
        expenseMapper.updateById(exp);
        opLogService.record(operator, "确认支出单", "expense", id, before, JSONUtil.toJsonStr(exp));
        return toRow(exp);
    }

    // ============================== 查询 ==============================

    public List<ExpenseDtos.ExpenseRow> list(String status) {
        List<ExpenseDtos.ExpenseRow> rows = new ArrayList<>();
        for (Expense e : expenseMapper.selectList(new LambdaQueryWrapper<Expense>()
                .eq(StrUtil.isNotBlank(status), Expense::getExpStatus, status)
                .orderByDesc(Expense::getId))) {
            rows.add(toRow(e));
        }
        return rows;
    }

    // ============================== 设备台账 ==============================

    public List<ExpenseDtos.EquipmentRow> listEquipment() {
        List<ExpenseDtos.EquipmentRow> rows = new ArrayList<>();
        for (Equipment e : equipmentMapper.selectList(new LambdaQueryWrapper<Equipment>()
                .orderByDesc(Equipment::getId))) {
            ExpenseDtos.EquipmentRow row = new ExpenseDtos.EquipmentRow();
            row.setId(e.getId());
            row.setEquipName(e.getEquipName());
            row.setMachineId(e.getMachineId());
            row.setBuyPrice(e.getBuyPrice());
            row.setBuyDate(e.getBuyDate());
            row.setResidualValue(e.getResidualValue());
            row.setEquipStatus(e.getEquipStatus());
            row.setExpenseId(e.getExpenseId());
            row.setCreateTime(e.getCreateTime());
            rows.add(row);
        }
        return rows;
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateEquipment(Long id, ExpenseDtos.EquipmentUpdateReq req, Long userId, String operator) {
        Equipment equipment = equipmentMapper.selectById(id);
        if (equipment == null) {
            throw new BizException("设备台账行不存在:id=" + id);
        }
        if (!Equipment.VALID_STATUS.contains(req.getEquipStatus())) {
            throw new BizException("设备状态只能是 " + String.join("/", Equipment.VALID_STATUS)
                    + ":" + req.getEquipStatus());
        }
        String before = JSONUtil.toJsonStr(equipment);
        equipment.setEquipName(req.getEquipName());
        equipment.setMachineId(req.getMachineId());
        equipment.setResidualValue(req.getResidualValue());
        equipment.setEquipStatus(req.getEquipStatus());
        equipment.setUpdateUser(userId);
        equipmentMapper.updateById(equipment);
        opLogService.record(operator, "编辑设备台账", "equipment", id, before, JSONUtil.toJsonStr(equipment));
    }

    // ============================== 内部 ==============================

    private ExpenseDtos.ExpenseRow toRow(Expense e) {
        ExpenseDtos.ExpenseRow row = new ExpenseDtos.ExpenseRow();
        row.setId(e.getId());
        row.setExpNo(e.getExpNo());
        row.setCategory(e.getCategory());
        row.setAmount(e.getAmount());
        row.setAccountId(e.getAccountId());
        Account account = accountMapper.selectById(e.getAccountId());
        row.setAccountName(account == null ? null : account.getAccountName());
        row.setIsEquipment(e.getIsEquipment());
        row.setEquipmentId(e.getEquipmentId());
        row.setEquipName(e.getEquipName());
        row.setExpStatus(e.getExpStatus());
        row.setBizDate(e.getBizDate());
        row.setBookPeriod(e.getBookPeriod());
        row.setRemark(e.getRemark());
        row.setCreateTime(e.getCreateTime());
        row.setAttachmentCount(attachmentService.countOf(ATTACH_REF_TYPE, e.getId()));
        return row;
    }

    private Expense mustGet(Long id) {
        Expense exp = expenseMapper.selectById(id);
        if (exp == null) {
            throw new BizException("支出单不存在:id=" + id);
        }
        return exp;
    }

    private String generateNo() {
        String like = "ZC-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
        Long count = expenseMapper.selectCount(new LambdaQueryWrapper<Expense>()
                .likeRight(Expense::getExpNo, like));
        return like + String.format("%03d", count + 1);
    }
}
