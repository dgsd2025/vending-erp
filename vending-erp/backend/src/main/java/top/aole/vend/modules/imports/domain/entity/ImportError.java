package top.aole.vend.modules.imports.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import top.aole.vend.modules.basedata.domain.entity.BaseEntity;

/**
 * 导入行级错误(yc_vend_import_error):失败行留痕(行号/原因/原始内容),支持修复后重导。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("yc_vend_import_error")
public class ImportError extends BaseEntity {

    public static final String TYPE_ALIAS_UNBOUND = "别名未绑定";
    public static final String TYPE_FORMAT = "格式错误";
    public static final String TYPE_MACHINE_MISSING = "机器不存在";
    public static final String TYPE_DUPLICATE = "重复";

    private Long batchId;
    private Integer rowNo;
    private String rawContent;
    private String errorType;
    private String errorMsg;
    private String resolveStatus;
}
