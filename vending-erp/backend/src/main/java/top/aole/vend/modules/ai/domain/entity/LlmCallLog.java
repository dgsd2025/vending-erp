package top.aole.vend.modules.ai.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI 调用记录(yc_vend_llm_call_log):透明四件套(推理过程/完整输出/置信分/原始数据)统一落库。
 * 铁律:LLM 永不直接算库存和补货量,只做解释;每个 AI 结论旁必挂 🔬 过程入口指向本表。
 */
@Data
@TableName("yc_vend_llm_call_log")
public class LlmCallLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;

    /** 接入点场景:补货解释/别名归集/凭证识别… */
    private String scene;

    /** 模型名(可配置不写死;mock 阶段=mock) */
    private String model;

    /** Prompt 模板指纹(版本追踪) */
    private String promptFingerprint;

    /** 幂等键(业务key+日期,24h 窗口) */
    private String idempotentKey;

    /** 是否缓存命中 */
    private Integer cacheHit;

    /** 输入摘要/原始数据引用(四件套之原始数据) */
    private String inputDigest;

    /** 推理过程(四件套) */
    private String reasoning;

    /** 完整输出(四件套) */
    private String outputText;

    /** 置信分(四件套;规则置信=数据完备度) */
    private BigDecimal confidence;

    private Integer tokensIn;

    private Integer tokensOut;

    private BigDecimal costAmount;

    private Integer durationMs;

    /** 状态:成功/失败/降级 */
    private String callStatus;

    private Long createUser;

    private Long createDept;

    private LocalDateTime createTime;

    private Long updateUser;

    private LocalDateTime updateTime;

    private Integer status;

    private Integer isDeleted;
}
