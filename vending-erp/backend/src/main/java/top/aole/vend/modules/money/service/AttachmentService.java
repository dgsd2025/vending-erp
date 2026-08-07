package top.aole.vend.modules.money.service;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.application.OpLogService;
import top.aole.vend.modules.money.domain.entity.Attachment;
import top.aole.vend.modules.money.domain.enums.AttachmentType;
import top.aole.vend.modules.money.dto.MoneyDtos;
import top.aole.vend.modules.money.mapper.AttachmentMapper;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 凭证附件通用件(M3-1):上传/关联单据/列表/预览。
 *
 * 存储纪律(复用导入中心 P0-B):归档文件名一律服务端生成
 * (attachments/yyyyMM/ATT-uuid.扩展名),**绝不拼接客户端原始文件名**;
 * 原始名剥掉路径后仅存 file_name 字段供展示。扩展名白名单校验(按小写)。
 *
 * 后续票直接用:M3-2 付款单"无转账截图不能进已付款"、M3-4 结算单"无凭证不能进已结算"
 * → 调 {@link #countOf(String, Long)} 断言 > 0 即可。
 */
@Service
@RequiredArgsConstructor
public class AttachmentService {

    /** 关联对象类型白名单(与表注释一致) */
    private static final List<String> REF_TYPES = Arrays.asList(
            "doc_head", "payment", "settlement", "claim", "deduction", "expense", "stocktake");

    /** 扩展名白名单:截图/照片 + 平台账单导出(xlsx/csv)+ 发票 pdf */
    private static final List<String> EXT_WHITELIST = Arrays.asList(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "pdf", "xlsx", "xls", "csv");

    private static final Map<String, String> CONTENT_TYPES = new HashMap<>();

    static {
        CONTENT_TYPES.put("jpg", "image/jpeg");
        CONTENT_TYPES.put("jpeg", "image/jpeg");
        CONTENT_TYPES.put("png", "image/png");
        CONTENT_TYPES.put("webp", "image/webp");
        CONTENT_TYPES.put("gif", "image/gif");
        CONTENT_TYPES.put("bmp", "image/bmp");
        CONTENT_TYPES.put("pdf", "application/pdf");
        CONTENT_TYPES.put("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        CONTENT_TYPES.put("xls", "application/vnd.ms-excel");
        CONTENT_TYPES.put("csv", "text/csv");
    }

    private final AttachmentMapper attachmentMapper;
    private final OpLogService opLogService;

    /** 凭证存储根目录(与导入归档同级;测试用 target 下临时目录) */
    @Value("${vend.attach.storage-dir:../storage/attachments}")
    private String storageDir;

    /** 上传并关联单据。文件名由服务端生成,客户端名(可能含 ../)只截净名存展示字段。 */
    @Transactional(rollbackFor = Exception.class)
    public Long upload(String refType, Long refId, String attType,
                       String clientFileName, byte[] content, Long userId, String operator) {
        if (!REF_TYPES.contains(refType)) {
            throw new BizException("不支持的关联对象类型:" + refType + "(可选:" + String.join("/", REF_TYPES) + ")");
        }
        if (refId == null || refId <= 0) {
            throw new BizException("凭证必须关联具体单据(refId)");
        }
        AttachmentType type = AttachmentType.ofLabel(attType);
        if (content == null || content.length == 0) {
            throw new BizException("上传文件为空");
        }
        if (content.length > 20 * 1024 * 1024) {
            throw new BizException("凭证文件超过 20MB 上限");
        }
        // P0-B 路径穿越防护:客户端名只用于取扩展名 + 剥路径后存展示字段
        String safeName = FileUtil.getName(StrUtil.nullToEmpty(clientFileName));
        String ext = FileUtil.extName(safeName).toLowerCase(Locale.ROOT);
        if (!EXT_WHITELIST.contains(ext)) {
            throw new BizException("不支持的文件类型「" + (ext.isEmpty() ? "(无扩展名)" : ext)
                    + "」,可选:" + String.join("/", EXT_WHITELIST));
        }
        // 服务端生成归档名:attachments/yyyyMM/ATT-uuid.ext(绝不含客户端输入)
        String month = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
        String storedName = "ATT-" + IdUtil.fastSimpleUUID() + "." + ext;
        File target = new File(new File(storageDir, month), storedName);
        FileUtil.writeBytes(content, target);

        Attachment att = new Attachment();
        att.setRefType(refType);
        att.setRefId(refId);
        att.setAttType(type.getLabel());
        att.setFilePath(target.getAbsolutePath());
        att.setFileName(safeName);
        att.setUploadBy(userId);
        att.setCreateUser(userId);
        attachmentMapper.insert(att);
        opLogService.record(operator, "上传凭证", "attachment", att.getId(), null,
                refType + ":" + refId + " " + type.getLabel() + " " + safeName);
        return att.getId();
    }

    /** 某单据的凭证列表(后续票"无凭证不能进已结算"的取数口) */
    public List<MoneyDtos.AttachmentRow> listOf(String refType, Long refId) {
        List<MoneyDtos.AttachmentRow> rows = new ArrayList<>();
        for (Attachment a : attachmentMapper.selectList(new LambdaQueryWrapper<Attachment>()
                .eq(Attachment::getRefType, refType).eq(Attachment::getRefId, refId)
                .orderByDesc(Attachment::getId))) {
            MoneyDtos.AttachmentRow row = new MoneyDtos.AttachmentRow();
            row.setId(a.getId());
            row.setRefType(a.getRefType());
            row.setRefId(a.getRefId());
            row.setAttType(a.getAttType());
            row.setFileName(a.getFileName());
            row.setCreateTime(a.getCreateTime());
            row.setUploadBy(a.getUploadBy());
            rows.add(row);
        }
        return rows;
    }

    /** 某单据凭证数(硬门禁断言口:M3-2/M3-4 "无凭证不能进已付款/已结算") */
    public long countOf(String refType, Long refId) {
        return attachmentMapper.selectCount(new LambdaQueryWrapper<Attachment>()
                .eq(Attachment::getRefType, refType).eq(Attachment::getRefId, refId));
    }

    /** 预览:返回文件字节 + contentType(controller 流式输出) */
    public Preview load(Long id) throws IOException {
        Attachment att = attachmentMapper.selectById(id);
        if (att == null) {
            throw new BizException("凭证不存在:id=" + id);
        }
        File file = new File(att.getFilePath());
        if (!file.exists()) {
            throw new BizException("凭证文件已丢失(归档被移动?):" + att.getFileName());
        }
        Preview preview = new Preview();
        preview.attachment = att;
        preview.content = FileUtil.readBytes(file);
        String ext = FileUtil.extName(file.getName()).toLowerCase(Locale.ROOT);
        preview.contentType = CONTENT_TYPES.getOrDefault(ext, "application/octet-stream");
        return preview;
    }

    public static class Preview {
        public Attachment attachment;
        public byte[] content;
        public String contentType;
    }
}
