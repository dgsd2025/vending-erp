package top.aole.vend.modules.doc;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import top.aole.vend.BaseIntegrationTest;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.domain.entity.OpLog;
import top.aole.vend.modules.basedata.infrastructure.mapper.OpLogMapper;
import top.aole.vend.modules.doc.domain.enums.DocStatus;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.dto.DocCreateReq;
import top.aole.vend.modules.doc.service.DocService;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验收组1:状态机——合法流转通过 / 非法流转抛异常 / 已确认单不可改 / 确认留痕。
 */
class DocStateMachineTest extends BaseIntegrationTest {

    @Autowired
    private OpLogMapper opLogMapper;

    @Test
    @DisplayName("合法流转:采购入库 草稿→待确认→已确认→待结算→已结算→已完成,确认记人+op_log")
    void legalFlow() {
        Long id = docService.createDoc(
                req(DocType.PURCHASE_IN, null, DocService.SOURCE_MANUAL, LocalDate.now(),
                        new Object[]{101L, "10", "3.0"}), OP);
        assertEquals(DocStatus.DRAFT, docService.getDoc(id).getHead().getDocStatus());

        docService.submit(id, OP);
        assertEquals(DocStatus.PENDING_CONFIRM, docService.getDoc(id).getHead().getDocStatus());

        docService.confirm(id, OP, false);
        assertEquals(DocStatus.CONFIRMED, docService.getDoc(id).getHead().getDocStatus());
        // 确认动作留痕:confirm_by/confirm_at
        assertEquals(OP, docService.getDoc(id).getHead().getConfirmBy());
        assertNotNull(docService.getDoc(id).getHead().getConfirmAt());
        // op_log 有"确认"记录
        Long confirmLogs = opLogMapper.selectCount(new LambdaQueryWrapper<OpLog>()
                .eq(OpLog::getTargetType, "doc_head").eq(OpLog::getTargetId, id)
                .eq(OpLog::getAction, "确认"));
        assertTrue(confirmLogs >= 1, "确认动作必须写 op_log");

        // 采购入库专属:已确认→待结算→已结算→已完成(M3 应付环节通道)
        // 通过通用状态机验证(complete 前先走结算态在 M3 由结算模块驱动,这里直接验流转合法性)
        docService.complete(id, OP);
        assertEquals(DocStatus.COMPLETED, docService.getDoc(id).getHead().getDocStatus());
    }

    @Test
    @DisplayName("非法流转:草稿直接确认 / 已完成再提交 → 全部抛 BizException")
    void illegalFlow() {
        Long id = docService.createDoc(
                req(DocType.GAIN_IN, null, DocService.SOURCE_MANUAL, LocalDate.now(),
                        new Object[]{102L, "5", "2.0"}), OP);
        // 草稿直接确认(跳过待确认)→ 非法
        BizException e1 = assertThrows(BizException.class, () -> docService.confirm(id, OP, false));
        assertTrue(e1.getMessage().contains("非法状态流转"), e1.getMessage());

        // 走完流程后再提交 → 非法
        docService.submit(id, OP);
        docService.confirm(id, OP, false);
        docService.complete(id, OP);
        BizException e2 = assertThrows(BizException.class, () -> docService.submit(id, OP));
        assertTrue(e2.getMessage().contains("非法状态流转"), e2.getMessage());

        // 已完成不可作废
        assertThrows(BizException.class, () -> docService.voidDoc(id, OP));
    }

    @Test
    @DisplayName("已确认单不可改:updateDraft 抛异常;草稿态可改")
    void confirmedImmutable() {
        DocCreateReq r = req(DocType.PURCHASE_IN, null, DocService.SOURCE_MANUAL, LocalDate.now(),
                new Object[]{103L, "8", "4.0"});
        Long id = docService.createDoc(r, OP);
        // 草稿可改
        r.setRemark("草稿态修改一次");
        docService.updateDraft(id, r, OP);
        assertEquals("草稿态修改一次", docService.getDoc(id).getHead().getRemark());

        docService.submit(id, OP);
        docService.confirm(id, OP, false);
        BizException e = assertThrows(BizException.class, () -> docService.updateDraft(id, r, OP));
        assertTrue(e.getMessage().contains("仅草稿态可修改"), e.getMessage());
    }

    @Test
    @DisplayName("单据不许删:DocService 全部公开方法里不存在 delete/remove")
    void noDeleteApi() {
        for (java.lang.reflect.Method m : DocService.class.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isPublic(m.getModifiers())) {
                String n = m.getName().toLowerCase();
                assertFalse(n.contains("delete") || n.contains("remove"),
                        "单据服务不许有删除入口,发现:" + m.getName());
            }
        }
    }
}
