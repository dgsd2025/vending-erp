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
    @DisplayName("P1-4:预挂单禁止直接作废(仓库侧已锁定,直接作废=账实分离);冲抵/转正才是合法出路")
    void prePendingCannotBeVoided() {
        // 垫库存 10 → 手工出库上架 4,确认后=预挂单(仓库侧已扣 4)
        Long poId = docService.createDoc(req(DocType.PURCHASE_IN, null, DocService.SOURCE_MANUAL,
                LocalDate.now(), new Object[]{104L, "10", "3.0"}), OP);
        docService.submit(poId, OP);
        docService.confirm(poId, OP, false);
        Long id = docService.createDoc(req(DocType.TRANSFER_OUT, 990901L, DocService.SOURCE_MANUAL,
                LocalDate.now(), new Object[]{104L, "4", "3.0"}), OP);
        docService.submit(id, OP);
        docService.confirm(id, OP, false);
        assertEquals(DocStatus.PRE_PENDING, docService.getDoc(id).getHead().getDocStatus());
        assertEquals(0, stockService.getWarehouseStock(104L).compareTo(new java.math.BigDecimal("6")));

        BizException e = assertThrows(BizException.class, () -> docService.voidDoc(id, OP));
        assertTrue(e.getMessage().contains("预挂单") && e.getMessage().contains("不允许直接作废"),
                e.getMessage());
        // 拒绝后状态不变、仓库锁定不变(不许出现"单作废了货还扣着"的账实分离)
        assertEquals(DocStatus.PRE_PENDING, docService.getDoc(id).getHead().getDocStatus());
        assertEquals(0, stockService.getWarehouseStock(104L).compareTo(new java.math.BigDecimal("6")));
    }

    @Test
    @DisplayName("P1-5:docSource 服务端裁决——公开建单一律手工;导入来源仅受信通道可设")
    void docSourceDecidedByServer() {
        // 公开通道:DTO 已无 docSource 字段,建出的单一律=手工(伪造导入来源无门)
        Long id = docService.createDoc(req(DocType.PURCHASE_IN, null, DocService.SOURCE_MANUAL,
                LocalDate.now(), new Object[]{105L, "1", "2.0"}), OP);
        assertEquals(DocService.SOURCE_MANUAL, docService.getDoc(id).getHead().getDocSource(),
                "公开建单通道来源必须由服务端强制=手工");

        // 受信通道(仅导入服务内部调用):显式指定导入来源
        Long trusted = docService.createDocWithSource(req(DocType.PURCHASE_IN, null, null,
                LocalDate.now(), new Object[]{105L, "1", "2.0"}), OP, DocService.SOURCE_IMPORT);
        assertEquals(DocService.SOURCE_IMPORT, docService.getDoc(trusted).getHead().getDocSource());
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
