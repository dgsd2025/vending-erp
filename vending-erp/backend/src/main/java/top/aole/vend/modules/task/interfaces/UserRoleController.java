package top.aole.vend.modules.task.interfaces;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.common.result.R;
import top.aole.vend.modules.basedata.application.OpLogService;
import top.aole.vend.modules.basedata.interfaces.Operators;
import top.aole.vend.modules.task.domain.entity.UserRole;
import top.aole.vend.modules.task.mapper.UserRoleMapper;

import java.util.List;

/**
 * 人员角色映射 CRUD(SSO 前手工维护:人名字符串+角色枚举)。完整路径 /api/v1/task/user-roles。
 * 停用=断入口,历史 op_log 永久保留(mockup p12:员工离职→主系统停号即断入口)。
 */
@Api(tags = "任务日历 · 人员与角色")
@RestController
@RequestMapping("/v1/task/user-roles")
@RequiredArgsConstructor
public class UserRoleController {

    private final UserRoleMapper userRoleMapper;
    private final OpLogService opLogService;

    @ApiOperation("列表(全部映射,含停用)")
    @GetMapping
    public R<List<UserRole>> list() {
        return R.ok(userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                .orderByAsc(UserRole::getId)));
    }

    @ApiOperation("新增映射(人名+角色)")
    @PostMapping
    public R<UserRole> create(@RequestBody UserRole req,
                              @RequestHeader(value = Operators.HEADER, required = false) String userName) {
        if (req.getUserName() == null || req.getUserName().trim().isEmpty()) {
            throw new BizException("人名不能为空");
        }
        if (req.getRoleCode() == null || req.getRoleCode().trim().isEmpty()) {
            throw new BizException("角色不能为空");
        }
        Long dup = userRoleMapper.selectCount(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getUserName, req.getUserName().trim())
                .eq(UserRole::getRoleCode, req.getRoleCode()));
        if (dup != null && dup > 0) {
            throw new BizException("该人员已有此角色映射");
        }
        UserRole ur = new UserRole();
        // SSO 前占位:唯一键含 user_id,没真实 SSO ID 时按人名生成稳定伪 ID(同名同 ID,异名不撞);接 SSO 后换真实 ID
        Long uid = req.getUserId();
        if (uid == null || uid == 0L) {
            uid = (long) Math.abs(req.getUserName().trim().hashCode());
        }
        ur.setUserId(uid);
        ur.setUserName(req.getUserName().trim());
        ur.setRoleCode(req.getRoleCode().trim());
        userRoleMapper.insert(ur);
        opLogService.record(Operators.resolve(userName), "新建", "user_role", ur.getId(), null, ur);
        return R.ok(ur);
    }

    @ApiOperation("启用/停用(停用即断入口,历史留痕保留,不物理删)")
    @PutMapping("/{id}/status")
    public R<UserRole> toggle(@PathVariable Long id, @RequestParam Integer target,
                              @RequestHeader(value = Operators.HEADER, required = false) String userName) {
        UserRole ur = userRoleMapper.selectById(id);
        if (ur == null) {
            throw new BizException("映射不存在:" + id);
        }
        Integer before = ur.getStatus();
        ur.setStatus(target != null && target == 1 ? 1 : 0);
        userRoleMapper.updateById(ur);
        opLogService.record(Operators.resolve(userName), ur.getStatus() == 1 ? "启用" : "停用",
                "user_role", ur.getId(), "status=" + before, "status=" + ur.getStatus());
        return R.ok(ur);
    }
}
