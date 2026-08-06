package top.aole.vend.modules.replenish.service;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.vend.common.config.LlmProperties;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.ai.domain.ILlmService;
import top.aole.vend.modules.ai.domain.entity.LlmCallLog;
import top.aole.vend.modules.ai.mapper.LlmCallLogMapper;
import top.aole.vend.modules.basedata.application.OpLogService;
import top.aole.vend.modules.basedata.application.ReplenishConfigService;
import top.aole.vend.modules.basedata.domain.entity.Machine;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.basedata.domain.entity.ReplenishConfig;
import top.aole.vend.modules.basedata.domain.entity.Slot;
import top.aole.vend.modules.basedata.infrastructure.mapper.MachineMapper;
import top.aole.vend.modules.basedata.infrastructure.mapper.ProductMapper;
import top.aole.vend.modules.basedata.infrastructure.mapper.ReplenishConfigMapper;
import top.aole.vend.modules.basedata.infrastructure.mapper.SlotMapper;
import top.aole.vend.modules.purchase.service.PurchaseOrderService;
import top.aole.vend.modules.replenish.domain.DemandStats;
import top.aole.vend.modules.replenish.domain.ReplenishCalc;
import top.aole.vend.modules.replenish.domain.entity.ReplenishPlan;
import top.aole.vend.modules.replenish.mapper.ReplenishPlanMapper;
import top.aole.vend.modules.stock.service.StockService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * (R,S) 补货引擎(M2-2):规则出数字,LLM 永不算数。
 *
 * 两级库存两种策略(skill 铁律#2):
 * - 机器侧 = 每天到访补满货道(par level = 货道容量合计,不套 R,S);
 * - 仓库侧 = (R,S) 公式算采购量(S = d̄×(R+L) + Z×σd×√(R+L)),R 默认 15 天仅用于采购;
 * - 慢销品(日均<1.5)不套公式,直接 min/max(config 可配)。
 *
 * 可解释(七律#7):每条建议 formula_json 存公式全部输入值;AI 解释 = ILlmService(mock)
 * 出一句人话 + llm_call_log 透明四件套(推理/输出/置信/原始数据)。
 * 幂等:同日重算覆盖 plan_status='建议' 的行,已忽略/已生成配货单保留且不重复生成。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReplenishEngine {

    public static final String TYPE_MACHINE = "机器补货";
    public static final String TYPE_PURCHASE = "采购建议";
    public static final String STATUS_PENDING = "建议";
    public static final String STATUS_IGNORED = "已忽略";
    public static final String SCENE = "补货解释";

    /** 清仓残余提示阈值:进入清仓中超过 N 天仓库还有货 → 三选一提示(穿行场景9 M2 段) */
    public static final int CLEARANCE_ALERT_DAYS = 30;
    /** 清仓残余三选一固定出路(P2-10 死锁解除的三条腿) */
    public static final List<String> CLEARANCE_CHOICES =
            Collections.unmodifiableList(Arrays.asList("退供", "报损", "换机促销"));

    private final DemandStatsService demandStatsService;
    private final ReplenishPlanMapper planMapper;
    private final ReplenishConfigService configService;
    private final ReplenishConfigMapper configMapper;
    private final ProductMapper productMapper;
    private final MachineMapper machineMapper;
    private final SlotMapper slotMapper;
    private final StockService stockService;
    private final PurchaseOrderService purchaseOrderService;
    private final ILlmService llmService;
    private final LlmCallLogMapper llmCallLogMapper;
    private final LlmProperties llmProperties;
    private final OpLogService opLogService;

    // ============================== 重算入口 ==============================

    /**
     * 一键重算(幂等):生成当日机器侧 + 仓库侧全部建议。
     *
     * @return 摘要:planDate/dataAsOf/staleDays/machineRows/purchaseRows
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> recalc(String operator) {
        DemandStatsService.Snapshot snap = demandStatsService.compute();
        if (snap.asOf == null) {
            throw new BizException("还没有任何销售数据,请先在导入中心导入后台出货明细");
        }
        LocalDate planDate = LocalDate.now();

        // 幂等:同日 '建议' 行覆盖(逻辑删),其他状态(已忽略/已生成配货单…)保留
        planMapper.delete(new LambdaQueryWrapper<ReplenishPlan>()
                .eq(ReplenishPlan::getPlanDate, planDate)
                .eq(ReplenishPlan::getPlanStatus, STATUS_PENDING));
        // 已被人工处理过的行:同键不再重复生成
        Set<String> handledKeys = new HashSet<>();
        for (ReplenishPlan p : planMapper.selectList(new LambdaQueryWrapper<ReplenishPlan>()
                .eq(ReplenishPlan::getPlanDate, planDate))) {
            handledKeys.add(key(p.getPlanType(), p.getMachineId(), p.getProductId()));
        }

        // 基础档案
        Map<Long, Product> products = new LinkedHashMap<>();
        for (Product p : productMapper.selectList(null)) {
            products.put(p.getId(), p);
        }
        Map<Long, Machine> machines = new LinkedHashMap<>();
        for (Machine m : machineMapper.selectList(null)) {
            machines.put(m.getId(), m);
        }
        ConfigResolver cfg = new ConfigResolver(configService.getGlobal(), configMapper.selectList(null));

        int machineRows = insertMachineSide(planDate, snap, products, machines, cfg, handledKeys);
        int purchaseRows = insertPurchaseSide(planDate, snap, products, machines, cfg, handledKeys);

        opLogService.record(operator, "补货重算", "replenish_plan", null, null,
                "planDate=" + planDate + ", asOf=" + snap.asOf
                        + ", 机器侧=" + machineRows + " 行, 采购=" + purchaseRows + " 行");

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("planDate", planDate.toString());
        summary.put("dataAsOf", snap.asOf.toString());
        summary.put("staleDays", java.time.temporal.ChronoUnit.DAYS.between(snap.asOf, planDate));
        summary.put("machineRows", machineRows);
        summary.put("purchaseRows", purchaseRows);
        return summary;
    }

    // ============================== 机器侧:par level 补满货道 ==============================

    private int insertMachineSide(LocalDate planDate, DemandStatsService.Snapshot snap,
                                  Map<Long, Product> products, Map<Long, Machine> machines,
                                  ConfigResolver cfg, Set<String> handledKeys) {
        // 货道容量:machineId → productId → Σcapacity(仅正常货道且绑了 SKU)
        Map<Long, Map<Long, BigDecimal>> capacity = new LinkedHashMap<>();
        for (Slot slot : slotMapper.selectList(new LambdaQueryWrapper<Slot>()
                .isNotNull(Slot::getProductId).eq(Slot::getSlotStatus, "正常"))) {
            capacity.computeIfAbsent(slot.getMachineId(), k -> new LinkedHashMap<>())
                    .merge(slot.getProductId(), nz(slot.getCapacity()), BigDecimal::add);
        }

        int rows = 0;
        for (Map.Entry<Long, Map<Long, BigDecimal>> me : capacity.entrySet()) {
            Long machineId = me.getKey();
            Machine machine = machines.get(machineId);
            if (machine == null) {
                continue;
            }
            for (Map.Entry<Long, BigDecimal> pe : me.getValue().entrySet()) {
                Long productId = pe.getKey();
                Product product = products.get(productId);
                if (product == null || !"在售".equals(product.getProductStatus())) {
                    continue; // 清仓中/停售不进建议
                }
                if (handledKeys.contains(key(TYPE_MACHINE, machineId, productId))) {
                    continue;
                }
                BigDecimal machineQty = stockService.getMachineStock(machineId, productId);
                DemandStats stats = snap.byMachine
                        .getOrDefault(machineId, new HashMap<>()).get(productId);
                BigDecimal avg = stats == null ? null : stats.getAvgDaily();

                ReplenishCalc.MachineCalc calc = ReplenishCalc.machineSuggest(
                        pe.getValue(), machineQty, avg, product.getShelfLifeDays(), snap.asOf);
                if (calc.getSuggestQty().signum() <= 0) {
                    continue;
                }

                ReplenishPlan plan = new ReplenishPlan();
                plan.setPlanDate(planDate);
                plan.setPlanType(TYPE_MACHINE);
                plan.setMachineId(machineId);
                plan.setProductId(productId);
                plan.setCurrentQty(nz(machineQty));
                plan.setAvgDaily(avg);
                plan.setSigmaDaily(stats == null ? null : stats.getSigmaDaily());
                plan.setTargetLevelS(calc.getTargetLevel());
                plan.setSuggestQty(calc.getSuggestQty());
                plan.setBoxRoundQty(null); // 机器侧按件补,不需要整箱
                plan.setPlanStatus(STATUS_PENDING);

                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("策略", "机器侧 par level:到访补满货道,不套 (R,S) 公式(跑一趟的人力比压几瓶货贵)");
                detail.put("公式", "建议补货量 = 货道容量合计 − max(机内现有, 0)");
                detail.put("货道容量合计", pe.getValue());
                detail.put("机内现有(推算)", nz(machineQty));
                if (machineQty != null && machineQty.signum() < 0) {
                    detail.put("警告", "机器账为负(销售>补货记录,快照缺失),按 0 参与计算,建议尽快盘点校准");
                }
                detail.put("日均销量d̄(该机·近28天·正常+兑换)", avg);
                detail.put("σd(该机)", stats == null ? null : stats.getSigmaDaily());
                detail.put("样本天数", stats == null ? 0 : stats.getSampleDays());
                detail.put("存销比(还能卖几天)", calc.getStockDays());
                detail.put("预计售罄日", calc.getSelloutDate() == null ? null : calc.getSelloutDate().toString());
                detail.put("紧急度", calc.getUrgency());
                if (calc.getExpireLimit() != null) {
                    detail.put("临期上限(日均×保质期×0.5)", calc.getExpireLimit());
                    detail.put("临期约束触发", calc.isExpireCapped()
                            ? "是:目标水位从货道容量压到临期上限,防机内过期" : "否");
                }
                detail.put("目标水位", calc.getTargetLevel());
                detail.put("建议补货量", calc.getSuggestQty());
                plan.setFormulaJson(JSONUtil.toJsonStr(detail));

                String draft = String.format("%s在%s还剩 %s %s,%s按日均 %s %s/天算%s,这次到访补 %s %s 把货道补满。",
                        product.getProductName(), machine.getMachineName(),
                        trim0(nz(machineQty).max(BigDecimal.ZERO)), product.getUnit(),
                        "缺货".equals(calc.getUrgency()) ? "已经卖空," : "",
                        avg == null ? "0" : trim0(avg), product.getUnit(),
                        calc.getSelloutDate() == null ? "" : ",预计 " + calc.getSelloutDate() + " 售罄",
                        trim0(calc.getSuggestQty()), product.getUnit());
                attachExplain(plan, cfg, stats, detail, draft);
                planMapper.insert(plan);
                rows++;
            }
        }
        return rows;
    }

    // ============================== 仓库侧:(R,S) / 慢销 min-max ==============================

    private int insertPurchaseSide(LocalDate planDate, DemandStatsService.Snapshot snap,
                                   Map<Long, Product> products, Map<Long, Machine> machines,
                                   ConfigResolver cfg, Set<String> handledKeys) {
        // 机内合计:productId → Σ max(该机推算库存, 0)
        Map<Long, BigDecimal> machineTotal = new HashMap<>();
        for (Long machineId : machines.keySet()) {
            for (Map.Entry<Long, BigDecimal> e : stockService.getMachineStockAll(machineId).entrySet()) {
                machineTotal.merge(e.getKey(), e.getValue().max(BigDecimal.ZERO), BigDecimal::add);
            }
        }
        // 仓库现存(批量) + 在途
        Map<Long, BigDecimal> warehouse = stockService.getWarehouseStockBatch(snap.global.keySet());
        Map<Long, BigDecimal> inTransit = new HashMap<>();
        for (Map<String, Object> row : purchaseOrderService.inTransitAll()) {
            inTransit.put(((Number) row.get("productId")).longValue(),
                    new BigDecimal(row.get("inTransitQty").toString()));
        }

        int rows = 0;
        for (Map.Entry<Long, DemandStats> e : snap.global.entrySet()) {
            Long productId = e.getKey();
            DemandStats stats = e.getValue();
            Product product = products.get(productId);
            if (product == null || !"在售".equals(product.getProductStatus())) {
                continue; // 清仓中禁采购
            }
            if (handledKeys.contains(key(TYPE_PURCHASE, null, productId))) {
                continue;
            }
            ReplenishConfig conf = cfg.forSku(productId);
            BigDecimal wh = nz(warehouse.get(productId));
            BigDecimal mc = nz(machineTotal.get(productId));
            BigDecimal transit = nz(inTransit.get(productId));

            ReplenishCalc.PurchaseCalc calc = ReplenishCalc.purchaseSuggest(
                    stats, conf.getCycleDays(), conf.getLeadTimeDays(), conf.getServiceLevel(),
                    wh, mc, transit, product.getBoxSpec(),
                    cfg.slowMinBoxes(conf), cfg.slowMaxBoxes(conf), snap.asOf);
            if (calc.getSuggestQty().signum() <= 0) {
                continue;
            }

            ReplenishPlan plan = new ReplenishPlan();
            plan.setPlanDate(planDate);
            plan.setPlanType(TYPE_PURCHASE);
            plan.setMachineId(null);
            plan.setProductId(productId);
            plan.setCurrentQty(calc.getAvailable());
            plan.setAvgDaily(stats.getAvgDaily());
            plan.setSigmaDaily(stats.getSigmaDaily());
            plan.setTargetLevelS(calc.getTargetS());
            plan.setSafetyStock(calc.getSafetyStock());
            plan.setSuggestQty(calc.getSuggestQty());
            plan.setBoxRoundQty(calc.getBoxRoundQty());
            plan.setPlanStatus(STATUS_PENDING);

            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("策略", "慢销min-max".equals(calc.getMode())
                    ? "慢销品(日均<1.5)不套公式:低于 min 箱补到 max 箱"
                    : "仓库侧 (R,S):S = d̄×(R+L) + Z×σd×√(R+L),Q = S − (仓库+机内+在途)");
            detail.put("计算模式", calc.getMode());
            detail.put("日均销量d̄(全局·近28天·正常+兑换)", stats.getAvgDaily());
            detail.put("σd", stats.getSigmaDaily());
            detail.put("样本天数", stats.getSampleDays());
            detail.put("补货周期R(天)", conf.getCycleDays());
            detail.put("提前期L(天)", conf.getLeadTimeDays());
            detail.put("服务水平", conf.getServiceLevel());
            detail.put("Z系数", calc.getZ());
            detail.put("星期系数启用", stats.isWeekdayEnabled()
                    ? (calc.isWeekdayApplied() ? "是(周期需求=未来R+L天逐日预测和)" : "是")
                    : "否(样本不足4周,退回移动平均)");
            if (stats.isWeekdayEnabled() && stats.getWeekdayCoef() != null) {
                StringBuilder sb = new StringBuilder();
                String[] names = {"一", "二", "三", "四", "五", "六", "日"};
                for (int i = 0; i < 7; i++) {
                    sb.append("周").append(names[i]).append('=')
                      .append(BigDecimal.valueOf(stats.getWeekdayCoef()[i]).setScale(2, RoundingMode.HALF_UP))
                      .append(i < 6 ? " " : "");
                }
                detail.put("星期系数", sb.toString());
            }
            detail.put("周期需求", calc.getCycleDemand());
            detail.put("安全库存SS", calc.getSafetyStock());
            detail.put("补到水位S", calc.getTargetS());
            detail.put("仓库现存", wh);
            detail.put("机内合计(负值按0)", mc);
            detail.put("在途", transit);
            detail.put("可用量合计", calc.getAvailable());
            detail.put("建议采购量Q", calc.getSuggestQty());
            detail.put("箱规", product.getBoxSpec());
            detail.put("整箱取整", calc.getBoxes() + " 箱 = " + trim0(calc.getBoxRoundQty()) + " " + product.getUnit());
            if ("慢销min-max".equals(calc.getMode())) {
                detail.put("慢销min(箱)", cfg.slowMinBoxes(conf));
                detail.put("慢销max(箱)", cfg.slowMaxBoxes(conf));
            }
            plan.setFormulaJson(JSONUtil.toJsonStr(detail));

            String draft;
            if ("慢销min-max".equals(calc.getMode())) {
                draft = String.format("%s是慢销品(日均 %s %s),仓库+机内+在途只剩 %s %s 低于最低水位,直接补到 %s %s(%s 箱)。",
                        product.getProductName(), trim0(stats.getAvgDaily()), product.getUnit(),
                        trim0(calc.getAvailable()), product.getUnit(),
                        trim0(calc.getBoxRoundQty()), product.getUnit(), trim0(calc.getBoxes()));
            } else {
                draft = String.format("%s近28天日均 %s %s,按 %d+%s 天周期需备 %s %s(含保险量 %s),现有 %s %s,建议采购 %s %s ≈ %s 箱。",
                        product.getProductName(), trim0(stats.getAvgDaily()), product.getUnit(),
                        conf.getCycleDays(), trim0(conf.getLeadTimeDays()),
                        trim0(calc.getTargetS()), product.getUnit(), trim0(calc.getSafetyStock()),
                        trim0(calc.getAvailable()), product.getUnit(),
                        trim0(calc.getSuggestQty()), product.getUnit(), trim0(calc.getBoxes()));
            }
            attachExplain(plan, cfg, stats, detail, draft);
            planMapper.insert(plan);
            rows++;
        }
        return rows;
    }

    // ============================== AI 解释(mock)+ 透明四件套落库 ==============================

    /** 规则出数字后,调 ILlmService(mock)生成一句人话,连同四件套落 llm_call_log */
    private void attachExplain(ReplenishPlan plan, ConfigResolver cfg, DemandStats stats,
                               Map<String, Object> detail, String draft) {
        long start = System.currentTimeMillis();
        String model = cfg.llmModel(llmProperties);
        String prompt = "你是售卖机补货助理。以下是规则引擎算好的补货建议(数字不许改),"
                + "请用一句大白话向老板解释。\n草稿:" + draft + "\n计算明细:" + plan.getFormulaJson();
        String output;
        String callStatus = "成功";
        try {
            output = llmService.chat(prompt);
        } catch (Exception ex) {
            log.warn("[Replenish] AI 解释失败,降级用规则草稿:{}", ex.getMessage());
            output = draft;
            callStatus = "降级";
        }

        LlmCallLog call = new LlmCallLog();
        call.setScene(SCENE);
        call.setModel(model);
        call.setPromptFingerprint("replenish-explain-v1");
        call.setIdempotentKey(SCENE + ":" + plan.getPlanType() + ":" + plan.getMachineId()
                + ":" + plan.getProductId() + ":" + plan.getPlanDate());
        call.setCacheHit(0);
        call.setInputDigest(plan.getFormulaJson());
        call.setReasoning("规则引擎计算过程(LLM 未参与算数):" + detail.get("策略"));
        call.setOutputText(output);
        call.setConfidence(stats == null ? new BigDecimal("0.5") : stats.confidence());
        call.setDurationMs((int) (System.currentTimeMillis() - start));
        call.setCallStatus(callStatus);
        llmCallLogMapper.insert(call);

        plan.setAiExplain(output);
        plan.setLlmCallId(call.getId());
    }

    // ============================== 查询 / 忽略 ==============================

    /** 建议列表(默认最近一次生成日);planDate 传 null 取最近 */
    public Map<String, Object> suggestions(String planType, LocalDate planDate) {
        LocalDate date = planDate != null ? planDate : planMapper.latestPlanDate();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planDate", date == null ? null : date.toString());
        LocalDate asOf = demandStatsService.dataAsOf();
        result.put("dataAsOf", asOf == null ? null : asOf.toString());
        result.put("staleDays", asOf == null ? null
                : java.time.temporal.ChronoUnit.DAYS.between(asOf, LocalDate.now()));
        result.put("rows", date == null ? java.util.Collections.emptyList()
                : planMapper.listByDateAndType(date, planType));
        return result;
    }

    /** 忽略/恢复一条建议(七律#5:留痕) */
    @Transactional(rollbackFor = Exception.class)
    public void setIgnored(Long planId, boolean ignored, String operator) {
        ReplenishPlan plan = planMapper.selectById(planId);
        if (plan == null) {
            throw new BizException("建议不存在:id=" + planId);
        }
        String from = plan.getPlanStatus();
        String to = ignored ? STATUS_IGNORED : STATUS_PENDING;
        if (ignored && !STATUS_PENDING.equals(from)) {
            throw new BizException("仅状态为「建议」的行可忽略,当前:" + from);
        }
        if (!ignored && !STATUS_IGNORED.equals(from)) {
            throw new BizException("仅已忽略的行可恢复,当前:" + from);
        }
        plan.setPlanStatus(to);
        planMapper.updateById(plan);
        opLogService.record(operator, ignored ? "忽略建议" : "恢复建议",
                "replenish_plan", planId, from, to);
    }

    /** 采纳一条建议(仓库侧一键转订货单后回写):建议 → 已采纳 */
    @Transactional(rollbackFor = Exception.class)
    public void adopt(Long planId, String operator) {
        ReplenishPlan plan = planMapper.selectById(planId);
        if (plan == null) {
            throw new BizException("建议不存在:id=" + planId);
        }
        if (!STATUS_PENDING.equals(plan.getPlanStatus())) {
            throw new BizException("仅状态为「建议」的行可采纳,当前:" + plan.getPlanStatus());
        }
        plan.setPlanStatus("已采纳");
        planMapper.updateById(plan);
        opLogService.record(operator, "采纳建议", "replenish_plan", planId, STATUS_PENDING, "已采纳");
    }

    /**
     * 清仓残余提示(只读,穿行场景9 M2 段):清仓中 + 仓库>0 + 超 30 天 →
     * 三选一提示(退供/报损/换机促销)。不落库不改状态,由驾驶舱/建议页轮询展示。
     */
    public List<Map<String, Object>> clearanceAlerts() {
        List<Product> clearing = productMapper.selectList(new LambdaQueryWrapper<Product>()
                .eq(Product::getProductStatus, "清仓中"));
        if (clearing.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (Product p : clearing) {
            ids.add(p.getId());
        }
        Map<Long, BigDecimal> warehouse = stockService.getWarehouseStockBatch(ids);
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> alerts = new ArrayList<>();
        for (Product p : clearing) {
            BigDecimal wh = nz(warehouse.get(p.getId()));
            if (wh.signum() <= 0 || p.getClearanceSince() == null) {
                continue; // 货已出清 / 无计时起点:不催
            }
            long days = ChronoUnit.DAYS.between(p.getClearanceSince(), today);
            if (days <= CLEARANCE_ALERT_DAYS) {
                continue; // 30 天缓冲期内先让自然销售消化
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("productId", p.getId());
            row.put("skuCode", p.getSkuCode());
            row.put("productName", p.getProductName());
            row.put("warehouseQty", wh);
            row.put("clearanceSince", p.getClearanceSince().toString());
            row.put("daysInClearance", days);
            row.put("choices", CLEARANCE_CHOICES);
            row.put("message", String.format(
                    "「%s」进入清仓已 %d 天,仓库还压着 %s %s——请三选一处理:退供 / 报损 / 换机促销",
                    p.getProductName(), days, trim0(wh),
                    p.getUnit() == null ? "件" : p.getUnit()));
            alerts.add(row);
        }
        return alerts;
    }

    /** 🔬 过程详情:公式快照 + llm_call_log 透明四件套 */
    public Map<String, Object> planDetail(Long planId) {
        ReplenishPlan plan = planMapper.selectById(planId);
        if (plan == null) {
            throw new BizException("建议不存在:id=" + planId);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("plan", plan);
        if (plan.getLlmCallId() != null) {
            LlmCallLog call = llmCallLogMapper.selectById(plan.getLlmCallId());
            if (call != null) {
                Map<String, Object> llm = new LinkedHashMap<>();
                llm.put("model", call.getModel());
                llm.put("reasoning", call.getReasoning());
                llm.put("outputText", call.getOutputText());
                llm.put("confidence", call.getConfidence());
                llm.put("inputDigest", call.getInputDigest());
                llm.put("createTime", call.getCreateTime());
                llm.put("callStatus", call.getCallStatus());
                result.put("llm", llm);
            }
        }
        return result;
    }

    // ============================== 参数解析 ==============================

    /** 参数覆盖解析:机器×SKU > SKU > 机器 > 全局(本引擎仓库侧只用 SKU/全局两级) */
    static class ConfigResolver {
        private final ReplenishConfig global;
        private final Map<String, ReplenishConfig> byKey = new HashMap<>();

        ConfigResolver(ReplenishConfig global, List<ReplenishConfig> all) {
            this.global = global;
            for (ReplenishConfig c : all) {
                byKey.put(c.getMachineId() + ":" + c.getProductId(), c);
            }
        }

        /** SKU 级参数:字段级回落(SKU 行缺哪个字段用全局的) */
        ReplenishConfig forSku(Long productId) {
            ReplenishConfig sku = byKey.get("0:" + productId);
            ReplenishConfig merged = new ReplenishConfig();
            merged.setCycleDays(pick(sku == null ? null : sku.getCycleDays(), global.getCycleDays(), 15));
            merged.setServiceLevel(pick(sku == null ? null : sku.getServiceLevel(),
                    global.getServiceLevel(), new BigDecimal("0.95")));
            merged.setLeadTimeDays(pick(sku == null ? null : sku.getLeadTimeDays(),
                    global.getLeadTimeDays(), BigDecimal.ONE));
            merged.setSlowMinBoxes(pick(sku == null ? null : sku.getSlowMinBoxes(),
                    global.getSlowMinBoxes(), new BigDecimal("0.5")));
            merged.setSlowMaxBoxes(pick(sku == null ? null : sku.getSlowMaxBoxes(),
                    global.getSlowMaxBoxes(), BigDecimal.ONE));
            merged.setLlmModel(sku != null && sku.getLlmModel() != null ? sku.getLlmModel() : global.getLlmModel());
            return merged;
        }

        BigDecimal slowMinBoxes(ReplenishConfig resolved) {
            return resolved.getSlowMinBoxes() == null ? new BigDecimal("0.5") : resolved.getSlowMinBoxes();
        }

        BigDecimal slowMaxBoxes(ReplenishConfig resolved) {
            return resolved.getSlowMaxBoxes() == null ? BigDecimal.ONE : resolved.getSlowMaxBoxes();
        }

        /** AI 模型名:config 表可配,空 = 跟随 llm.model 配置,再空 = mock */
        String llmModel(LlmProperties props) {
            if (global.getLlmModel() != null && !global.getLlmModel().isEmpty()) {
                return global.getLlmModel();
            }
            if (props.getModel() != null && !props.getModel().isEmpty()) {
                return props.getModel();
            }
            return "mock";
        }

        private static <T> T pick(T override, T fallback, T dft) {
            if (override != null) {
                return override;
            }
            return fallback != null ? fallback : dft;
        }
    }

    private static String key(String type, Long machineId, Long productId) {
        return type + ":" + machineId + ":" + productId;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** 去掉小数尾 0 的展示用字符串 */
    private static String trim0(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }
}
