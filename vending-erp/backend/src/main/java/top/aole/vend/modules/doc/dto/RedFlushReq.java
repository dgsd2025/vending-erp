package top.aole.vend.modules.doc.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 红冲执行入参(P0-1):原因强制;锁账期红冲需老板越权标记(占位)+备注即原因。
 */
@Data
public class RedFlushReq {

    @NotBlank(message = "红冲必须填写原因")
    private String reason;

    /** 老板越权(占位,SSO 后按真实角色):原单入账月已锁时必须为 true 才放行 */
    private boolean bossOverride;
}
