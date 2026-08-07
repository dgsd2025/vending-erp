package top.aole.vend.modules.imports.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import top.aole.vend.modules.basedata.domain.entity.AliasPending;
import top.aole.vend.modules.basedata.domain.entity.SkuAlias;
import top.aole.vend.modules.basedata.infrastructure.mapper.AliasPendingMapper;
import top.aole.vend.modules.basedata.infrastructure.mapper.SkuAliasMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 导入侧别名匹配器(通道1/2 共用)。
 *
 * 匹配口径(DESIGN_DOC 遗留裁决点#2 + 冲刺0实测:出货明细没有商品编号列):
 *   条码为主(sku_alias.alias_barcode)→ 名称兜底(alias_name)→ 都不中进 alias_pending。
 * 通道3(商品列表)有编号列,仍按 编号+条码 绑,不走本匹配器的兜底。
 *
 * 用法:每个批次 new Session(一次性把别名表加载进内存,5k 行逐行查库太慢),
 * 绑定后"重处理待绑定行"重开 Session 即拿到最新绑定。
 */
@Component
@RequiredArgsConstructor
public class AliasMatcher {

    private final SkuAliasMapper skuAliasMapper;
    private final AliasPendingMapper aliasPendingMapper;

    public Session openSession() {
        return new Session();
    }

    public class Session {
        private final Map<String, Long> byBarcode = new HashMap<>();
        private final Map<String, Long> byName = new HashMap<>();
        /** 本批已 upsert 过的 pending 键,避免同批同名反复查库 */
        private final Map<String, Long> pendingSeen = new HashMap<>();

        Session() {
            List<SkuAlias> all = skuAliasMapper.selectList(null);
            for (SkuAlias a : all) {
                if (StrUtil.isNotBlank(a.getAliasBarcode())) {
                    byBarcode.put(a.getAliasBarcode(), a.getProductId());
                }
                if (StrUtil.isNotBlank(a.getAliasName())) {
                    byName.put(a.getAliasName(), a.getProductId());
                }
            }
        }

        /** 条码为主、名称兜底;不中返回 null */
        public Long match(String barcode, String name) {
            if (StrUtil.isNotBlank(barcode)) {
                Long hit = byBarcode.get(barcode.trim());
                if (hit != null) {
                    return hit;
                }
            }
            if (StrUtil.isNotBlank(name)) {
                return byName.get(name.trim());
            }
            return null;
        }

        /**
         * 未识别项进待绑定队列(uk=编号+条码;首见插入,再见 hit_count++)。
         * 编号条码全空时用 "N/名称" 作占位编号,躲开 uk(‘’,‘’) 只能存一行的冲突
         * (占位编号只进 pending 队列;确认绑定时会原样写进 sku_alias,名称兜底照样命中)。
         */
        public void pendingUpsert(String code, String barcode, String name, Long batchId) {
            String c = StrUtil.nullToEmpty(StrUtil.trim(code));
            String b = StrUtil.nullToEmpty(StrUtil.trim(barcode));
            if (c.isEmpty() && b.isEmpty()) {
                c = "N/" + StrUtil.nullToEmpty(StrUtil.trim(name));
            }
            String key = c + "\u0001" + b;
            Long seenId = pendingSeen.get(key);
            if (seenId != null) {
                aliasPendingMapper.update(null,
                        new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<AliasPending>()
                                .setSql("hit_count = hit_count + 1").eq("id", seenId));
                return;
            }
            AliasPending existing = aliasPendingMapper.selectOne(new LambdaQueryWrapper<AliasPending>()
                    .eq(AliasPending::getAliasCode, c)
                    .eq(AliasPending::getAliasBarcode, b));
            if (existing != null) {
                aliasPendingMapper.update(null,
                        new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<AliasPending>()
                                .setSql("hit_count = hit_count + 1").eq("id", existing.getId()));
                pendingSeen.put(key, existing.getId());
                return;
            }
            AliasPending pending = new AliasPending();
            pending.setAliasCode(c);
            pending.setAliasBarcode(b);
            pending.setAliasName(StrUtil.nullToEmpty(name));
            pending.setFirstBatchId(batchId);
            pending.setHitCount(1);
            pending.setPendingStatus("待绑定");
            aliasPendingMapper.insert(pending);
            pendingSeen.put(key, pending.getId());
        }
    }
}
