/**
 * 平台门户（aole-portal · eco.vvaix.com 生态管理平台）SSO 模块——「门户点卡片 → 免登落地」。
 *
 * <pre>
 * 门户 302 → GET /api/v1/sso/callback?auth_code&app_id&tenantId   (interfaces.SsoController，AuthGateFilter 放行)
 *   → application.SsoLoginService：exchange → RS256 验签(aud/iss 多值/exp) → tenant 双源校验 → 按 portal_uid 找/建 auth_user
 *   → AuthTokenService 签本系统 Bearer token → 302 前端 /sso/callback#token=..（前端 setSession 后强刷 /）
 * </pre>
 *
 * 开关：SSO_ENABLED=false（默认）时 SsoController 不挂载。配置见 {@link top.aole.vend.modules.sso.config.SsoProperties}。
 * 规范权威：~/.claude/skills/ole-portal-sso/SKILL.md（11 条硬约束）。
 */
package top.aole.vend.modules.sso;
