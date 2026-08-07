package top.aole.vend.modules.doc.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.doc.domain.entity.DocHead;
import top.aole.vend.modules.doc.domain.enums.DocStatus;
import top.aole.vend.modules.doc.mapper.DocHeadMapper;

/**
 * 单据状态条件更新单点(盲审 P0-A 并发双过账修复)。
 *
 * 问题:原实现"先读状态 → 内存改 → updateById 无条件覆盖",两个并发请求都能把
 * 同一张单推进到目标状态,库存/成本被过账两遍。
 *
 * 修法:所有状态流转必须走本类——
 *   UPDATE yc_vend_doc_head SET ... WHERE id=? AND doc_status=旧状态
 * 受影响行数=0 说明单据已被他人并发处理(先到先得),后来者抛异常、事务整体回滚,
 * 过账事件自然不会发出 → 同一张单只可能过账一次。
 *
 * 使用约定:调用方先在实体上把 doc_status 改成目标状态(以及其它要落库的字段),
 * 再把"改之前的旧状态"传进来做 WHERE 条件。全系统禁止再用 updateById 直改 doc_status。
 */
@Component
@RequiredArgsConstructor
public class DocStatusGuard {

    private final DocHeadMapper docHeadMapper;

    /**
     * 条件更新:实体非空字段全部落库(含新状态),WHERE 带旧状态守卫。
     *
     * @param head         已在内存中设好目标状态/其它字段的单据头
     * @param expectedFrom 流转前的旧状态(WHERE 条件)
     * @throws BizException 受影响行数=0,单据已被他人处理
     */
    public void updateGuarded(DocHead head, DocStatus expectedFrom) {
        int rows = docHeadMapper.update(head, new LambdaQueryWrapper<DocHead>()
                .eq(DocHead::getId, head.getId())
                .eq(DocHead::getDocStatus, expectedFrom));
        if (rows == 0) {
            throw new BizException(String.format(
                    "单据[%s]已被他人处理(状态已不是[%s]),请刷新后重试",
                    head.getDocNo(), expectedFrom.getLabel()));
        }
    }
}
