package top.aole.vend.common.web;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.aole.vend.common.result.R;
import top.aole.vend.modules.basedata.infrastructure.mapper.MachineMapper;
import top.aole.vend.modules.basedata.infrastructure.mapper.ProductMapper;
import top.aole.vend.modules.basedata.infrastructure.mapper.SupplierMapper;
import top.aole.vend.modules.doc.domain.entity.DocHead;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.mapper.DocHeadMapper;
import top.aole.vend.modules.money.mapper.AccountMapper;
import top.aole.vend.modules.money.service.SettleModeService;
import top.aole.vend.modules.stock.mapper.SaleRecordMapper;

/**
 * 新手指引 · 上手进度。context-path=/api,完整路径 GET /api/v1/guide/setup-status。
 *
 * 只读现有表的真实计数,驱动前端「开业向导」清单的完成/未完成打勾——
 * 不建表、不写死,数据从哪来一目了然(§4.17 stale 字段规避:实时算不落表)。
 */
@Api(tags = "系统")
@RestController
@RequestMapping("/v1/guide")
@RequiredArgsConstructor
public class GuideController {

    private final MachineMapper machineMapper;
    private final ProductMapper productMapper;
    private final SupplierMapper supplierMapper;
    private final AccountMapper accountMapper;
    private final DocHeadMapper docHeadMapper;
    private final SaleRecordMapper saleRecordMapper;
    private final SettleModeService settleModeService;

    @ApiOperation("上手进度:驱动新手指引开业向导清单(真实计数,不落表)")
    @GetMapping("/setup-status")
    public R<SetupStatus> setupStatus() {
        SetupStatus s = new SetupStatus();
        // 第1步 · 建档案(设置中心)
        s.setMachineCount(machineMapper.selectCount(null));
        s.setProductCount(productMapper.selectCount(null));
        s.setSupplierCount(supplierMapper.selectCount(null));
        s.setAccountCount(accountMapper.selectCount(null));
        String mode = settleModeService.currentMode();
        s.setSettleMode(mode);
        s.setSettleModeSet(mode != null && !"UNSET".equals(mode));
        // 第2步 · 期初导入(存在期初单 doc_type=期初 即视为已做)
        long openingDocs = docHeadMapper.selectCount(
                new LambdaQueryWrapper<DocHead>().eq(DocHead::getDocType, DocType.OPENING));
        s.setPrekitDone(openingDocs > 0);
        // 第3步 · 对平验收:期初完成且已有销售数据在跑(与老账对平是期初向导的收尾环节)
        long saleRows = saleRecordMapper.selectCount(null);
        s.setSalesFlowing(saleRows > 0);
        s.setReconciled(openingDocs > 0 && saleRows > 0);
        return R.ok(s);
    }

    @Data
    public static class SetupStatus {
        /** 第1步 · 建档案 */
        private Long machineCount;
        private Long productCount;
        private Long supplierCount;
        private Long accountCount;
        private String settleMode;
        private boolean settleModeSet;
        /** 第2步 · 期初导入是否已做 */
        private boolean prekitDone;
        /** 第3步 · 是否已有销售数据在流转 */
        private boolean salesFlowing;
        /** 第3步 · 对平验收(期初完成 + 有销售数据) */
        private boolean reconciled;
    }
}
