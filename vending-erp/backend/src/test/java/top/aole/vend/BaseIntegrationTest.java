package top.aole.vend;

import cn.hutool.core.util.IdUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import top.aole.vend.modules.doc.dto.DocCreateReq;
import top.aole.vend.modules.doc.dto.DocItemReq;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.service.DocService;
import top.aole.vend.modules.stock.domain.entity.SaleRecord;
import top.aole.vend.modules.stock.mapper.SaleRecordMapper;
import top.aole.vend.modules.stock.service.StockService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 集成测试基类:纯 @SpringBootTest(NONE),不绑任何端口;vend_test 库隔离;
 * 每个用例事务回滚,互不污染。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Transactional
public abstract class BaseIntegrationTest {

    /** 测试操作人 */
    protected static final Long OP = 9L;

    @Autowired
    protected DocService docService;
    @Autowired
    protected StockService stockService;
    @Autowired
    protected SaleRecordMapper saleRecordMapper;

    /** 组一张单据入参(P1-5 后 DTO 已无 docSource 字段;source 参数由 confirmedDoc 走受信通道传入) */
    protected DocCreateReq req(DocType type, Long machineId, String source, LocalDate bizDate,
                               Object[]... items) {
        DocCreateReq r = new DocCreateReq();
        r.setDocType(type);
        r.setMachineId(machineId);
        r.setBizDate(bizDate);
        List<DocItemReq> list = new ArrayList<>();
        for (Object[] it : items) {
            DocItemReq i = new DocItemReq();
            i.setProductId((Long) it[0]);
            i.setQty(new BigDecimal(it[1].toString()));
            i.setUnitPrice(it.length > 2 ? new BigDecimal(it[2].toString()) : BigDecimal.ONE);
            list.add(i);
        }
        r.setItems(list);
        return r;
    }

    /** 建单→提交→确认,返回单据 ID */
    protected Long confirmedDoc(DocType type, Long machineId, String source, LocalDate bizDate,
                                boolean exempt, LocalDateTime bizTime, Object[]... items) {
        // 走受信通道显式指定来源(P1-5:公开 createDoc 已强制手工,测试造"导入"单需走这里)
        Long id = docService.createDocWithSource(req(type, machineId, source, bizDate, items), OP, source);
        docService.submit(id, OP);
        docService.confirm(id, OP, exempt, bizTime);
        return id;
    }

    /** 给仓库垫底:确认一张采购入库单 */
    protected Long stockWarehouse(Long productId, String qty) {
        return confirmedDoc(DocType.PURCHASE_IN, null, DocService.SOURCE_MANUAL,
                LocalDate.now(), false, null, new Object[]{productId, qty, "3.5"});
    }

    /** 造一条销售记录(导入中心 M1-3 的最小替身,直接落表) */
    protected void insertSale(Long machineId, Long productId, String qty, String orderType,
                              LocalDateTime bizTime) {
        SaleRecord s = new SaleRecord();
        s.setOrderNo("T-" + IdUtil.getSnowflakeNextIdStr());
        s.setMachineId(machineId);
        s.setProductId(productId);
        s.setQty(new BigDecimal(qty));
        s.setAmountReceived(BigDecimal.ONE);
        s.setOrderType(orderType);
        s.setBizTime(bizTime);
        s.setBizPeriod(bizTime.format(DateTimeFormatter.ofPattern("yyyy-MM")));
        s.setBookPeriod(s.getBizPeriod());
        saleRecordMapper.insert(s);
    }
}
