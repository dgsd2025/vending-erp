package top.aole.vend.modules.imports.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import top.aole.vend.modules.basedata.domain.entity.BaseEntity;

/**
 * 导入批次(yc_vend_import_batch):导入中心是全系统数据入口。
 * 三通道 file_type = 出货明细 / 系统补货记录 / 商品列表(期初通道 M1-6 接)。
 * 原始文件归档 archive_path,整批可回滚(batch_status=已回滚)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("yc_vend_import_batch")
public class ImportBatch extends BaseEntity {

    public static final String TYPE_SALE = "出货明细";
    public static final String TYPE_REPLENISH = "系统补货记录";
    public static final String TYPE_PRODUCT_LIST = "商品列表";

    public static final String STATUS_PROCESSING = "处理中";
    public static final String STATUS_IMPORTED = "已导入";
    public static final String STATUS_ROLLED_BACK = "已回滚";

    private String batchNo;
    private String fileName;
    private String fileType;
    private String archivePath;
    /** 数据覆盖区间(如 2026-06 ~ 2026-07) */
    private String periodRange;
    private Integer rowTotal;
    private Integer rowOk;
    private Integer rowFail;
    private Integer rowDup;
    private String batchStatus;
    /** 列映射 JSON(模板自愈留位,接入点#9) */
    private String columnMapJson;
}
