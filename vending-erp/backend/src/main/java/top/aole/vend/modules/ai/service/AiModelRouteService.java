package top.aole.vend.modules.ai.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.vend.common.config.LlmProperties;
import top.aole.vend.modules.ai.domain.AiScenes;
import top.aole.vend.modules.basedata.application.OpLogService;
import top.aole.vend.modules.money.domain.entity.ConfigEntry;
import top.aole.vend.modules.money.mapper.ConfigEntryMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 多模型路由表(接入点 → 模型名):
 * 每个接入点在设置中心可独立换模型(config key = ai.model.<scene>),
 * 覆盖顺序:config 覆盖 > ENV llm.model > §12.2 默认值。改动写 op_log。
 * 铁律:模型可选不写死(全局 §4.7 / 七律#7)。
 */
@Service
@RequiredArgsConstructor
public class AiModelRouteService {

    public static final String CONFIG_PREFIX = "ai.model.";

    private final ConfigEntryMapper configMapper;
    private final LlmProperties llmProperties;
    private final OpLogService opLogService;

    /** 生效模型名(路由表解析;不关心网关是否已配置——mock 与否由 LlmGateway 判) */
    public String resolve(AiScenes scene) {
        String override = configuredModel(scene);
        if (StrUtil.isNotBlank(override)) {
            return override;
        }
        if (StrUtil.isNotBlank(llmProperties.getModel())) {
            return llmProperties.getModel();
        }
        return scene.getDefaultModel();
    }

    /** config 表里的覆盖值(无覆盖返回 null) */
    public String configuredModel(AiScenes scene) {
        ConfigEntry entry = find(scene);
        return entry == null ? null : entry.getConfigValue();
    }

    /** 设置(空值=清除覆盖,回落默认);改动写 op_log */
    @Transactional(rollbackFor = Exception.class)
    public void setModel(AiScenes scene, String model, String operator) {
        ConfigEntry entry = find(scene);
        String before = entry == null ? null : entry.getConfigValue();
        String after = StrUtil.trimToNull(model);
        if (entry == null) {
            if (after == null) {
                return; // 本来就没覆盖
            }
            entry = new ConfigEntry();
            entry.setConfigKey(CONFIG_PREFIX + scene.getKey());
            entry.setConfigValue(after);
            entry.setRemark("AI 接入点「" + scene.getLabel() + "」模型路由(§12.2)");
            configMapper.insert(entry);
        } else if (after == null) {
            // 清覆盖回默认:删行(updateById 默认跳过 null 字段,写不进 null,只能删)
            configMapper.deleteById(entry.getId());
        } else {
            entry.setConfigValue(after);
            configMapper.updateById(entry);
        }
        opLogService.record(operator, "设置AI模型路由", "config", entry.getId(),
                scene.getKey() + "=" + before, scene.getKey() + "=" + after);
    }

    /** 一次取全表覆盖值(设置中心列表用,省 12 次查库) */
    public Map<String, String> allOverrides() {
        Map<String, String> map = new LinkedHashMap<>();
        configMapper.selectList(new LambdaQueryWrapper<ConfigEntry>()
                        .likeRight(ConfigEntry::getConfigKey, CONFIG_PREFIX))
                .forEach(e -> map.put(e.getConfigKey().substring(CONFIG_PREFIX.length()), e.getConfigValue()));
        return map;
    }

    private ConfigEntry find(AiScenes scene) {
        return configMapper.selectOne(new LambdaQueryWrapper<ConfigEntry>()
                .eq(ConfigEntry::getConfigKey, CONFIG_PREFIX + scene.getKey()).last("LIMIT 1"));
    }
}
