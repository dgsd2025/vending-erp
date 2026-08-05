package top.aole.vend.modules.basedata.interfaces;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import top.aole.vend.common.result.R;
import top.aole.vend.modules.basedata.application.MachineService;
import top.aole.vend.modules.basedata.domain.entity.Machine;
import top.aole.vend.modules.basedata.domain.entity.Slot;
import top.aole.vend.modules.basedata.interfaces.dto.Dtos;

import javax.validation.Valid;
import java.util.List;

/**
 * 机器与货道接口。后台设备ID唯一;停用保留历史,无 DELETE。
 */
@Api(tags = "基础档案 · 机器与货道")
@RestController
@RequestMapping("/v1/basedata")
@RequiredArgsConstructor
public class MachineController {

    private final MachineService machineService;

    @ApiOperation("机器分页列表")
    @GetMapping("/machines")
    public R<Page<Machine>> page(@RequestParam(defaultValue = "1") long current,
                                 @RequestParam(defaultValue = "20") long size,
                                 @RequestParam(required = false) String keyword,
                                 @RequestParam(required = false) String machineStatus) {
        return R.ok(machineService.page(current, size, keyword, machineStatus));
    }

    @ApiOperation("机器详情")
    @GetMapping("/machines/{id}")
    public R<Machine> detail(@PathVariable Long id) {
        return R.ok(machineService.getById(id));
    }

    @ApiOperation("新建机器(后台设备ID唯一)")
    @PostMapping("/machines")
    public R<Machine> create(@RequestBody Machine machine,
                             @RequestHeader(value = Operators.HEADER, required = false) String userName) {
        return R.ok(machineService.create(machine, Operators.resolve(userName)));
    }

    @ApiOperation("编辑机器")
    @PutMapping("/machines/{id}")
    public R<Machine> update(@PathVariable Long id, @RequestBody Machine machine,
                             @RequestHeader(value = Operators.HEADER, required = false) String userName) {
        return R.ok(machineService.update(id, machine, Operators.resolve(userName)));
    }

    @ApiOperation("机器状态流转:在线/故障/停用")
    @PutMapping("/machines/{id}/status")
    public R<Machine> changeStatus(@PathVariable Long id, @Valid @RequestBody Dtos.StatusReq req,
                                   @RequestHeader(value = Operators.HEADER, required = false) String userName) {
        return R.ok(machineService.changeStatus(id, req.getTargetStatus(), Operators.resolve(userName)));
    }

    @ApiOperation("按机器列货道")
    @GetMapping("/machines/{id}/slots")
    public R<List<Slot>> listSlots(@PathVariable Long id) {
        return R.ok(machineService.listSlots(id));
    }

    @ApiOperation("批量初始化货道(机器×货道号,已存在跳过)")
    @PostMapping("/machines/{id}/slots/init")
    public R<List<Slot>> initSlots(@PathVariable Long id, @RequestBody Dtos.SlotInitReq req,
                                   @RequestHeader(value = Operators.HEADER, required = false) String userName) {
        return R.ok(machineService.initSlots(id, req, Operators.resolve(userName)));
    }

    @ApiOperation("货道编辑:绑SKU(0=解绑)/容量/状态")
    @PutMapping("/slots/{slotId}")
    public R<Slot> updateSlot(@PathVariable Long slotId, @RequestBody Dtos.SlotUpdateReq req,
                              @RequestHeader(value = Operators.HEADER, required = false) String userName) {
        return R.ok(machineService.updateSlot(slotId, req, Operators.resolve(userName)));
    }
}
