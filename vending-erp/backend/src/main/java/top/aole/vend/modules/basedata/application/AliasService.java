package top.aole.vend.modules.basedata.application;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.domain.entity.AliasPending;
import top.aole.vend.modules.basedata.domain.entity.SkuAlias;
import top.aole.vend.modules.basedata.infrastructure.mapper.AliasPendingMapper;
import top.aole.vend.modules.basedata.infrastructure.mapper.SkuAliasMapper;
import top.aole.vend.modules.basedata.interfaces.dto.Dtos;

import java.util.Objects;

/**
 * SKU 别名应用服务。
 * 冲刺 0 拍板:绑定键 = 后台商品编号 + 条码(不绑名称,名称仅留痕);绑一次终身生效。
 * alias_pending 待绑定队列的 列表/确认绑定 在此立好接口,供 M1-3 导入模块使用。
 */
@Service
@RequiredArgsConstructor
public class AliasService {

    private final SkuAliasMapper skuAliasMapper;
    private final AliasPendingMapper aliasPendingMapper;
    private final ProductService productService;
    private final OpLogService opLogService;

    /** 别名分页列表:可按商品过滤、按 编号/条码/名称 关键字搜 */
    public Page<SkuAlias> page(long current, long size, Long productId, String keyword) {
        LambdaQueryWrapper<SkuAlias> qw = new LambdaQueryWrapper<>();
        qw.eq(productId != null, SkuAlias::getProductId, productId);
        if (StrUtil.isNotBlank(keyword)) {
            qw.and(w -> w.like(SkuAlias::getAliasCode, keyword)
                    .or().like(SkuAlias::getAliasBarcode, keyword)
                    .or().like(SkuAlias::getAliasName, keyword));
        }
        qw.orderByDesc(SkuAlias::getId);
        return skuAliasMapper.selectPage(new Page<>(current, size), qw);
    }

    /** 绑定:同 编号+条码 已有绑定则改绑(留 op_log),否则新增 */
    @Transactional(rollbackFor = Exception.class)
    public SkuAlias bind(Dtos.BindAliasReq req, String operator) {
        String aliasCode = StrUtil.nullToEmpty(StrUtil.trim(req.getAliasCode()));
        String aliasBarcode = StrUtil.nullToEmpty(StrUtil.trim(req.getAliasBarcode()));
        if (aliasCode.isEmpty() && aliasBarcode.isEmpty()) {
            throw new BizException("后台商品编号与条码至少填一个(不允许按名称绑定)");
        }
        productService.getById(req.getProductId());

        SkuAlias existing = skuAliasMapper.selectOne(new LambdaQueryWrapper<SkuAlias>()
                .eq(SkuAlias::getAliasCode, aliasCode)
                .eq(SkuAlias::getAliasBarcode, aliasBarcode));
        if (existing != null) {
            if (Objects.equals(existing.getProductId(), req.getProductId())) {
                return existing; // 幂等:重复绑定同一商品,零副作用
            }
            SkuAlias before = skuAliasMapper.selectById(existing.getId());
            existing.setProductId(req.getProductId());
            existing.setAliasName(req.getAliasName());
            existing.setBindSource(StrUtil.blankToDefault(req.getBindSource(), "人工"));
            skuAliasMapper.updateById(existing);
            opLogService.record(operator, "改绑别名", "sku_alias", existing.getId(), before, existing);
            return existing;
        }

        SkuAlias alias = new SkuAlias();
        alias.setAliasCode(aliasCode);
        alias.setAliasBarcode(aliasBarcode);
        alias.setAliasName(req.getAliasName());
        alias.setProductId(req.getProductId());
        alias.setBindSource(StrUtil.blankToDefault(req.getBindSource(), "人工"));
        skuAliasMapper.insert(alias);
        opLogService.record(operator, "绑定别名", "sku_alias", alias.getId(), null, alias);
        return alias;
    }

    /** 解绑:物理删除(唯一键原因,见 Mapper 注释),op_log 留 before 痕 */
    @Transactional(rollbackFor = Exception.class)
    public void unbind(Long id, String operator) {
        SkuAlias before = skuAliasMapper.selectById(id);
        if (before == null) {
            throw new BizException("别名不存在:id=" + id);
        }
        skuAliasMapper.deletePhysically(id);
        opLogService.record(operator, "解绑别名", "sku_alias", id, before, null);
    }

    // ---------- alias_pending 待绑定队列(供 M1-3 导入用,接口先立好) ----------

    /** 待绑定队列分页:默认只看 待绑定 */
    public Page<AliasPending> pendingPage(long current, long size, String pendingStatus) {
        LambdaQueryWrapper<AliasPending> qw = new LambdaQueryWrapper<>();
        qw.eq(StrUtil.isNotBlank(pendingStatus), AliasPending::getPendingStatus, pendingStatus)
                .orderByDesc(AliasPending::getHitCount)
                .orderByDesc(AliasPending::getId);
        return aliasPendingMapper.selectPage(new Page<>(current, size), qw);
    }

    /** 确认绑定:待绑定项 → 写入 sku_alias + 状态置已绑定(sale_record 回补由 M1-3 导入模块做) */
    @Transactional(rollbackFor = Exception.class)
    public SkuAlias confirmPending(Long pendingId, Long productId, String operator) {
        AliasPending pending = aliasPendingMapper.selectById(pendingId);
        if (pending == null) {
            throw new BizException("待绑定项不存在:id=" + pendingId);
        }
        if (!"待绑定".equals(pending.getPendingStatus())) {
            throw new BizException("该项当前状态为「" + pending.getPendingStatus() + "」,不能重复确认");
        }
        Dtos.BindAliasReq req = new Dtos.BindAliasReq();
        req.setAliasCode(pending.getAliasCode());
        req.setAliasBarcode(pending.getAliasBarcode());
        req.setAliasName(pending.getAliasName());
        req.setProductId(productId);
        req.setBindSource(Objects.equals(productId, pending.getSuggestProductId()) ? "AI建议采纳" : "人工");
        SkuAlias alias = bind(req, operator);

        AliasPending before = aliasPendingMapper.selectById(pendingId);
        pending.setPendingStatus("已绑定");
        aliasPendingMapper.updateById(pending);
        opLogService.record(operator, "确认绑定", "alias_pending", pendingId, before, pending);
        return alias;
    }

    /** 忽略:不再提醒(可重新进队列由导入侧 hit_count++ 决定) */
    @Transactional(rollbackFor = Exception.class)
    public void ignorePending(Long pendingId, String operator) {
        AliasPending before = aliasPendingMapper.selectById(pendingId);
        if (before == null) {
            throw new BizException("待绑定项不存在:id=" + pendingId);
        }
        AliasPending patch = new AliasPending();
        patch.setId(pendingId);
        patch.setPendingStatus("忽略");
        aliasPendingMapper.updateById(patch);
        opLogService.record(operator, "忽略待绑定", "alias_pending", pendingId, before, patch);
    }
}
