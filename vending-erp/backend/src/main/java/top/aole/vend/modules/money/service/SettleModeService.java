package top.aole.vend.modules.money.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.application.OpLogService;
import top.aole.vend.modules.money.domain.entity.ConfigEntry;
import top.aole.vend.modules.money.dto.MoneyDtos;
import top.aole.vend.modules.money.mapper.ConfigEntryMapper;

import java.util.Arrays;
import java.util.List;

/**
 * 结算模式配置(附录D 双模式,待办#1 核实后设置中心一键定型):
 *
 * UNSET(默认)→ UI 显示"结算模式待核实"横幅,两模式报表只出假设对比不出正式数;
 * PLATFORM → 平台归集:销售入"平台待结算"虚账,结算单核销+两差对账(§7.3 问3);
 * DIRECT → 微信/支付宝直连:销售直接视为商户号入账,按账单导入核对,无待结算虚账。
 *
 * 已录数据按所选模式重述(sale_record.settlement_id 语义随模式解释),不需迁移——
 * 所以允许改(核实前试错),每次改动写 op_log。
 */
@Service
@RequiredArgsConstructor
public class SettleModeService {

    public static final String MODE_UNSET = "UNSET";
    public static final String MODE_PLATFORM = "PLATFORM";
    public static final String MODE_DIRECT = "DIRECT";
    private static final List<String> MODES = Arrays.asList(MODE_UNSET, MODE_PLATFORM, MODE_DIRECT);

    /** UNSET 横幅文案(前端原样展示) */
    public static final String UNSET_BANNER =
            "结算模式待核实:请先追一笔真实交易,确认平台是「归集结算(PLATFORM)」还是「微信/支付宝直连(DIRECT)」。"
                    + "核实前资金报表只出假设对比预览,不出正式数;核实后在设置中心一键定型,已录数据自动按所选模式重述。";

    private final ConfigEntryMapper configMapper;
    private final OpLogService opLogService;

    public MoneyDtos.SettleModeResp get() {
        ConfigEntry entry = find();
        String mode = entry == null || entry.getConfigValue() == null ? MODE_UNSET : entry.getConfigValue();
        MoneyDtos.SettleModeResp resp = new MoneyDtos.SettleModeResp();
        resp.setMode(mode);
        resp.setBanner(MODE_UNSET.equals(mode) ? UNSET_BANNER : null);
        resp.getOptions().add(new MoneyDtos.ModeOption(MODE_UNSET, "待核实",
                "默认档:报表只出假设对比预览,不出正式数"));
        resp.getOptions().add(new MoneyDtos.ModeOption(MODE_PLATFORM, "平台归集",
                "销售入「平台待结算」虚账,平台结算单核销 + 两差对账"));
        resp.getOptions().add(new MoneyDtos.ModeOption(MODE_DIRECT, "微信/支付宝直连",
                "销售直接视为商户号入账,按账单导入核对,无待结算虚账"));
        return resp;
    }

    /** 当前模式(其他模块判断用,如 M3-4 结算单入口) */
    public String currentMode() {
        return get().getMode();
    }

    @Transactional(rollbackFor = Exception.class)
    public void set(String mode, Long userId, String operator) {
        if (!MODES.contains(mode)) {
            throw new BizException("结算模式只能是 UNSET/PLATFORM/DIRECT:" + mode);
        }
        ConfigEntry entry = find();
        String before = entry == null ? MODE_UNSET : entry.getConfigValue();
        if (entry == null) {
            entry = new ConfigEntry();
            entry.setConfigKey(ConfigEntry.KEY_SETTLE_MODE);
            entry.setConfigValue(mode);
            entry.setRemark("附录D 结算双模式;核实真实交易后定型");
            entry.setCreateUser(userId);
            configMapper.insert(entry);
        } else {
            entry.setConfigValue(mode);
            entry.setUpdateUser(userId);
            configMapper.updateById(entry);
        }
        opLogService.record(operator, "设置结算模式", "config", entry.getId(), before, mode);
    }

    private ConfigEntry find() {
        return configMapper.selectOne(new LambdaQueryWrapper<ConfigEntry>()
                .eq(ConfigEntry::getConfigKey, ConfigEntry.KEY_SETTLE_MODE).last("LIMIT 1"));
    }
}
