package top.aole.vend.regression;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import top.aole.vend.modules.basedata.domain.entity.Machine;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.doc.domain.entity.DocHead;
import top.aole.vend.modules.doc.domain.enums.DocStatus;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.mapper.DocHeadMapper;
import top.aole.vend.modules.doc.service.DocService;
import top.aole.vend.modules.imports.domain.entity.ImportBatch;
import top.aole.vend.modules.imports.dto.ImportDtos;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 穿行场景8:新机首次铺货(审计结论:错——手工铺货单+后台补货记录导入 → 同一次上架双扣仓库;
 * P0-4 修复:转移单唯一生产者=导入;首铺流程=去后台执行补货→次日导入自动生成铺货单;
 * 手工单一律预挂单,导入冲抵,不双扣)。
 *
 * M1 已落地并全测(端到端,导入真链路)。
 */
class Scenario08NewMachineFirstLoadTest extends RegressionSupport {

    @Autowired
    private DocHeadMapper docHeadMapper;

    private static final String DAY = "2026-07-20";

    @Test
    @DisplayName("首铺正道:后台执行补货→导入自动生成铺货转移单(doc_source=导入,已确认),机器库存=后台快照")
    void firstLoadGeneratedByImportOnly() throws Exception {
        Machine m = machine("新装的8号机");
        Product p = product("首铺东鹏", "RG695801", null);
        alias("RG695801", "东鹏特饮首铺装", p.getId());
        stockWarehouse(p.getId(), "30");

        ImportDtos.CommitResp resp = importFile(ImportBatch.TYPE_REPLENISH, repFile(new Object[][]{
                {m.getDeviceId(), 1, "东鹏特饮首铺装", "RG695801", 0, 10, 10, "小邱", DAY + " 15:00:00"},
        }));
        assertEquals(1, resp.getDocsCreated(), "导入自动生成铺货转移单");
        assertEquals(1, resp.getSnapshots(), "补货后库存落机器快照锚点");

        DocHead doc = docHeadMapper.selectList(new LambdaQueryWrapper<DocHead>()
                .eq(DocHead::getImportBatchId, resp.getBatchId())).get(0);
        assertEquals(DocType.TRANSFER_OUT, doc.getDocType());
        assertEquals(DocService.SOURCE_IMPORT, doc.getDocSource(), "唯一生产者=导入(P0-4)");
        assertEquals(DocStatus.CONFIRMED, doc.getDocStatus());

        assertEquals(0, stockService.getWarehouseStock(p.getId()).compareTo(new BigDecimal("20")));
        assertEquals(0, stockService.getMachineStock(m.getId(), p.getId()).compareTo(new BigDecimal("10")));
    }

    @Test
    @DisplayName("双扣拆弹(错案修复):手工首铺单=预挂单,导入同日冲抵→仓库只扣一次,机器账只认导入")
    void manualFirstLoadOffsetNoDoubleDeduction() throws Exception {
        Machine m = machine("新装的9号机");
        Product p = product("首铺和其正", "RG695802", null);
        alias("RG695802", "和其正首铺装", p.getId());
        stockWarehouse(p.getId(), "30");

        // 小邱不知道新规矩,先手工录了一张铺货单 10 → 预挂单(只锁仓库)
        Long manualId = docService.createDoc(req(DocType.TRANSFER_OUT, m.getId(),
                DocService.SOURCE_MANUAL, LocalDate.parse(DAY), new Object[]{p.getId(), "10", "2.5"}), OP);
        docService.submit(manualId, OP);
        docService.confirm(manualId, OP, false, null);
        assertEquals(DocStatus.PRE_PENDING, docHeadMapper.selectById(manualId).getDocStatus());
        assertEquals(0, stockService.getWarehouseStock(p.getId()).compareTo(new BigDecimal("20")));

        // 次日导入后台补货记录(同机器+SKU+当日)→ 冲抵预挂单
        ImportDtos.CommitResp resp = importFile(ImportBatch.TYPE_REPLENISH, repFile(new Object[][]{
                {m.getDeviceId(), 1, "和其正首铺装", "RG695802", 0, 10, 10, "小邱", DAY + " 16:00:00"},
        }));
        assertEquals(1, resp.getMatchedPrePending(), "预挂单被导入单冲抵");

        DocHead manual = docHeadMapper.selectById(manualId);
        assertEquals(DocStatus.VOID, manual.getDocStatus());
        assertNotNull(manual.getMatchedDocId());
        // 关键断言(原审计错案):同一次上架不双扣 → 仓库 30−10=20 而不是 10
        assertEquals(0, stockService.getWarehouseStock(p.getId()).compareTo(new BigDecimal("20")),
                "预挂单锁定已释放,只剩导入单一笔扣账");
        assertEquals(0, stockService.getMachineStock(m.getId(), p.getId()).compareTo(new BigDecimal("10")));
    }

    @Test
    @DisplayName("首铺导入幂等:同一补货记录文件重复导入 → 防重跳过,不重复生成铺货单/不重复扣仓库")
    void firstLoadReimportIdempotent() throws Exception {
        Machine m = machine("新装的10号机");
        Product p = product("首铺红牛", "RG695803", null);
        alias("RG695803", "红牛首铺装", p.getId());
        stockWarehouse(p.getId(), "24");

        byte[] file = repFile(new Object[][]{
                {m.getDeviceId(), 2, "红牛首铺装", "RG695803", 0, 12, 12, "小邱", DAY + " 17:00:00"},
        });
        ImportDtos.CommitResp first = importFile(ImportBatch.TYPE_REPLENISH, file);
        assertEquals(1, first.getDocsCreated());
        ImportDtos.CommitResp second = importFile(ImportBatch.TYPE_REPLENISH, file);
        assertEquals(0, second.getDocsCreated(), "同机器+商品+补货时间戳防重");
        assertEquals(1, second.getRowDup());
        assertEquals(0, stockService.getWarehouseStock(p.getId()).compareTo(new BigDecimal("12")),
                "仓库只扣一次 24−12=12");
    }
}
