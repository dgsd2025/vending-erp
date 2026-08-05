package top.aole.vend.modules.basedata;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.application.MachineService;
import top.aole.vend.modules.basedata.application.ProductService;
import top.aole.vend.modules.basedata.application.ReplenishConfigService;
import top.aole.vend.modules.basedata.domain.entity.Machine;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.basedata.domain.entity.ReplenishConfig;
import top.aole.vend.modules.basedata.domain.entity.Slot;
import top.aole.vend.modules.basedata.interfaces.dto.Dtos;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 机器/货道/补货参数 service 真库单测。测试数据前缀 TSTM-,@AfterEach 物理清理。
 */
@SpringBootTest
class MachineSlotServiceTest {

    private static final String P = "TSTM-";

    @Autowired
    private MachineService machineService;
    @Autowired
    private ProductService productService;
    @Autowired
    private ReplenishConfigService configService;
    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE s FROM yc_vend_slot s JOIN yc_vend_machine m ON s.machine_id = m.id WHERE m.machine_code LIKE ?", P + "%");
        jdbc.update("DELETE rc FROM yc_vend_replenish_config rc JOIN yc_vend_product p ON rc.product_id = p.id WHERE p.sku_code LIKE ?", P + "%");
        jdbc.update("DELETE FROM yc_vend_op_log WHERE user_name='单测' AND target_type IN ('machine','slot','replenish_config')");
        jdbc.update("DELETE ol FROM yc_vend_op_log ol JOIN yc_vend_product p ON ol.target_id = p.id AND ol.target_type='product' WHERE p.sku_code LIKE ?", P + "%");
        jdbc.update("DELETE pl FROM yc_vend_price_log pl JOIN yc_vend_product p ON pl.product_id = p.id WHERE p.sku_code LIKE ?", P + "%");
        jdbc.update("DELETE FROM yc_vend_machine WHERE machine_code LIKE ?", P + "%");
        jdbc.update("DELETE FROM yc_vend_product WHERE sku_code LIKE ?", P + "%");
    }

    private Machine createMachine(String suffix) {
        Machine m = new Machine();
        m.setMachineCode(P + suffix);
        m.setMachineName("测试机-" + suffix);
        m.setDeviceId(P + "DEV-" + suffix);
        return machineService.create(m, "单测");
    }

    @Test
    void device_id_unique_and_status_flow() {
        Machine m1 = createMachine("001");
        assertEquals("在线", m1.getMachineStatus());

        // 后台设备ID 唯一
        Machine dup = new Machine();
        dup.setMachineCode(P + "002");
        dup.setMachineName("测试机-002");
        dup.setDeviceId(P + "DEV-001");
        BizException e = assertThrows(BizException.class, () -> machineService.create(dup, "单测"));
        assertTrue(e.getMessage().contains("设备ID已存在"));

        // 停用 = 状态流转,不是删除
        Machine stopped = machineService.changeStatus(m1.getId(), "停用", "单测");
        assertEquals("停用", stopped.getMachineStatus());
        assertNotNull(machineService.getById(m1.getId()));
    }

    @Test
    void slot_batch_init_is_idempotent_and_bind_sku() {
        Machine machine = createMachine("003");
        Dtos.SlotInitReq init = new Dtos.SlotInitReq();
        init.setSlotCount(10);
        init.setCapacity(new BigDecimal("6"));

        List<Slot> created = machineService.initSlots(machine.getId(), init, "单测");
        assertEquals(10, created.size());
        assertEquals("01", created.get(0).getSlotNo());
        assertEquals(10, machineService.getById(machine.getId()).getSlotCount(), "机器货道数要同步");

        // 再跑一次同样的初始化 → 已存在全部跳过,幂等
        List<Slot> secondRun = machineService.initSlots(machine.getId(), init, "单测");
        assertEquals(0, secondRun.size());
        assertEquals(10, machineService.listSlots(machine.getId()).size());

        // 货道绑 SKU + 改容量
        Product product = new Product();
        product.setSkuCode(P + "SKU1");
        product.setProductName("货道绑定测试品");
        product.setUnit("瓶");
        product.setBoxSpec(BigDecimal.ONE);
        Product saved = productService.create(product, "单测");

        Slot target = machineService.listSlots(machine.getId()).get(0);
        Dtos.SlotUpdateReq upd = new Dtos.SlotUpdateReq();
        upd.setProductId(saved.getId());
        upd.setCapacity(new BigDecimal("8"));
        Slot after = machineService.updateSlot(target.getId(), upd, "单测");
        assertEquals(saved.getId(), after.getProductId());
        assertEquals(0, new BigDecimal("8").compareTo(after.getCapacity()));

        // productId=0 → 解绑成空货道
        Dtos.SlotUpdateReq unbind = new Dtos.SlotUpdateReq();
        unbind.setProductId(0L);
        assertNull(machineService.updateSlot(target.getId(), unbind, "单测").getProductId());
    }

    @Test
    void replenish_config_upsert_writes_op_log_old_to_new() {
        Product product = new Product();
        product.setSkuCode(P + "SKU2");
        product.setProductName("参数覆盖测试品");
        product.setUnit("瓶");
        product.setBoxSpec(BigDecimal.ONE);
        Product saved = productService.create(product, "单测");

        // 新增 SKU 覆盖行
        ReplenishConfig cfg = new ReplenishConfig();
        cfg.setProductId(saved.getId());
        cfg.setCycleDays(7);
        cfg.setServiceLevel(new BigDecimal("0.9800"));
        cfg.setLeadTimeDays(new BigDecimal("2"));
        ReplenishConfig created = configService.save(cfg, "单测");
        assertEquals("SKU", created.getScopeType(), "scope 由后端推导");

        // upsert:同 scope 再保存 → 更新同一行,op_log 记录 旧值→新值
        ReplenishConfig cfg2 = new ReplenishConfig();
        cfg2.setProductId(saved.getId());
        cfg2.setCycleDays(10);
        cfg2.setServiceLevel(new BigDecimal("0.9500"));
        cfg2.setLeadTimeDays(new BigDecimal("2"));
        ReplenishConfig updated = configService.save(cfg2, "单测");
        assertEquals(created.getId(), updated.getId());
        assertEquals(10, updated.getCycleDays());

        Integer logCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM yc_vend_op_log WHERE target_type='replenish_config' AND target_id=? AND action='改参数' AND before_json IS NOT NULL AND after_json IS NOT NULL",
                Integer.class, created.getId());
        assertNotNull(logCount);
        assertEquals(1, logCount, "参数修改必须有 旧值→新值 的 op_log");

        // 非法参数拦截
        ReplenishConfig bad = new ReplenishConfig();
        bad.setCycleDays(-1);
        assertThrows(BizException.class, () -> configService.save(bad, "单测"));
    }
}
