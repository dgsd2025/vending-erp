package top.aole.vend.modules.basedata.application;

import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.aole.vend.modules.basedata.domain.entity.OpLog;
import top.aole.vend.modules.basedata.infrastructure.mapper.OpLogMapper;

import java.time.LocalDateTime;

/**
 * 操作日志落库:所有写操作统一从这里记一条(谁、几点、改了什么:旧值→新值)。
 * 经手人暂用 header X-User-Name 占位(user_id=0),SSO 接入后替换为真实用户。
 */
@Service
@RequiredArgsConstructor
public class OpLogService {

    private final OpLogMapper opLogMapper;

    /**
     * 记一条操作日志。before/after 传实体或 Map,内部转 JSON;null 表示无(如新建无 before)。
     */
    public void record(String userName, String action, String targetType, Long targetId,
                       Object before, Object after) {
        OpLog log = new OpLog();
        log.setUserId(0L);
        log.setUserName(userName);
        log.setAction(action);
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        log.setBeforeJson(before == null ? null : JSONUtil.toJsonStr(before));
        log.setAfterJson(after == null ? null : JSONUtil.toJsonStr(after));
        log.setOpTime(LocalDateTime.now());
        opLogMapper.insert(log);
    }

    /**
     * userId 版重载(单据中心/库存引擎用,M1-5):before/after 已是 JSON 字符串,原样落库不再二次序列化。
     * SSO 接入后 userId 为真实 SSO 用户 ID。
     */
    public void recordJson(Long userId, String action, String targetType, Long targetId,
                           String beforeJson, String afterJson) {
        OpLog log = new OpLog();
        log.setUserId(userId == null ? 0L : userId);
        log.setAction(action);
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        log.setBeforeJson(beforeJson);
        log.setAfterJson(afterJson);
        log.setOpTime(LocalDateTime.now());
        opLogMapper.insert(log);
    }
}
