# 售卖机 ERP · 上线 runbook(ole 平台)

> 目标:把售卖机 ERP 作为 ole 平台新产品 `vend` 上线到阿里云 ECS。
> 部署包已备好(本目录);标 🙋 的步骤**必须你本人做**(我不代输密码/不代付款/不代备案),标 🤖 的是授权后我能替你做的。

> **✅ 决策已定(2026-08-07)**:①数据库=**自带 mysql:8.0 容器**(不走共享 RDS,数据物理隔离);②**先合 main 再从 main 部署**;③结算模式上线 **UNSET**(设置中心再切)。
> **⚠ 两处 runbook 早期笔误已修**:后端实际读 `DB_URL/DB_USERNAME/DB_PASSWORD`(非 `SPRING_DATASOURCE_*`);Caddy 反代目标是容器网络内的 `vend-web:80`(非宿主 `127.0.0.1:8085`)。以 docker-compose.prod.yml / .env.prod.example / caddy-site.conf 现内容为准。

## 关键参数(ole 规范)

| 项 | 值 |
|---|---|
| 产品 slug | `vend` |
| ECS | `121.40.120.226`(4C8G Ubuntu22,图南等产品**共享生产机**) |
| 部署目录 | `/data/apps/vend/` |
| 宿主端口 | `8085`(仅本机,中央 Caddy 反代) |
| 域名 | `vend.aoleplat.com`(备案后启用) |
| 数据库 | 建议独立库 `vend_prod`(共享 RDS 实例)· 表前缀 `yc_vend_` · 历史表 `flyway_schema_history_vend` |

## ⚠ 三个只有你能做的前置

1. 🙋 **确认备案**:阿里云备案管理查 `aoleplat.com` 是否已通过。未过则上线后只能用 IP+端口访问。
2. 🙋 **建生产库 + 授权**:在 RDS 上 `CREATE DATABASE vend_prod ...` + 给 `aole_uat` 授权(SQL 见 flyway-prod-checklist.md)。
3. 🙋 **DNS**:阿里云 DNS 加一条 A 记录(见 dns-records.md)。

## 上线步骤

### 第 0 步 · 决策(建议先拍板)
- 🙋 独立库 `vend_prod` 还是共享 `aole_uat`?(推荐独立,物理隔离)
- 🙋 结算模式:上线先 UNSET,还是已核实可直接定 PLATFORM/DIRECT?
- 🙋 是否走 ole 正规路径(GitLab CI 自动部署)还是手动 SSH 部署?
  - **ole 正规路径**:用 `ole-new-project` 建 GitLab 双仓库 → 推代码 → main 分支 CI 自动部署。最规范,但需 GitLab 权限,且本项目现在是本地 git,要先迁到 ole GitLab。
  - **手动 SSH 部署**:直接把代码 rsync 上 ECS `docker compose up`。快,但绕过 CI,且动的是共享生产机。

### 第 1 步 · 准备 .env(🙋 你填密码)
```bash
cd /data/apps/vend
cp .env.prod.example .env && chmod 600 .env
# 编辑 .env 填 RDS 密码等(AI 不代填)
```

### 第 2 步 · 部署 app(🤖 授权后我做 / 或你照做)
```bash
# 在 ECS /data/apps/vend/ 下(代码已 rsync 上来)
docker compose -f deploy/docker-compose.prod.yml build --no-cache
docker compose -f deploy/docker-compose.prod.yml up -d
# 后端首启 Flyway 自动建 40 表(见 flyway-prod-checklist.md 验证)
docker logs vend-server --tail 50   # 看到 Undertow started on 8081 + flyway 1.0.17 success
```
⚠ 若 8-jre 运行报错(个别依赖需 Java11+),把 backend.Dockerfile 运行阶段基础镜像换 `eclipse-temurin:17-jre` 重 build。

### 第 3 步 · 接中央 Caddy(🤖/🙋)
```bash
ln -sf /data/apps/vend/deploy/caddy-site.conf /data/caddy/sites.d/vend.conf
docker exec central-caddy caddy reload --config /etc/caddy/Caddyfile   # 或平台约定的 reload 方式
```

### 第 4 步 · 验收(🤖 我可远程真测)
- 备案前:`curl http://121.40.120.226:8085/api/v1/health` → `{"code":200,...}`;浏览器开 `http://121.40.120.226:8085/`
- 备案后:`https://vend.aoleplat.com` 走 V0-V5 真测(健康/登录壳/关键页/SQL 对账)
- SQL 验证:`vend_prod` 40 表齐、flyway 1.0.17 success

### 第 4.5 步 · 接平台门户 SSO(2026-08-19 · eco.vvaix.com 免登直达,可选)
1. 门户管理后台注册子系统:名称「智慧园区售卖机 ERP」、系统 URL `https://vend.vvaix.com`、**回调地址 `https://vend.vvaix.com/api/v1/sso/callback`**、ssoEnabled=1 → 拿到 `app_id` / `client_secret`(记密码管理器,不入仓不进对话)
2. `.env` 填:`SSO_ENABLED=true`、`SSO_PORTAL_BASE_URL=http://host.docker.internal:18151/api`(容器内访问宿主门户)、`SSO_APP_ID`、`SSO_CLIENT_SECRET`、`SSO_PORTAL_JWT_ISSUER=aole-portal,yunshan-portal`;`docker compose ... up -d` 重建 vend-server
3. Flyway 首启自动补 `V1.0.100`(`yc_vend_auth_user.portal_uid` + 唯一索引,幂等可重跑)
4. 验收 **路径 A**:开 `https://vend.vvaix.com/login` → 点「用平台账号登录」→ 跳 eco.vvaix.com;**路径 B**:门户工作台点本系统卡片 → `/api/v1/sso/callback?auth_code=..` → 302 `/sso/callback#token=..` → 自动落 `/dashboard`,右上角显示门户姓名;网络面板 `/api/auth/me` 200 无 401
5. 首登口径:按 `portal_uid` 找账号,找不到自动建号(用户名=手机号/`portal_<uid>`,姓名=门户姓名,角色=`REGISTER_DEFAULT_ROLE` 最低角色;仅当账号表为空才给「老板」)。要提权到老板/财务:目前没有改角色的界面,由负责人在库里改 `yc_vend_auth_user.role`(改完重新登录生效)

### 第 5 步 · 期初数据(🙋 决策 + 🤖 执行)
生产库是空的。上线要不要把老 Excel 历史数据用「期初导入向导」灌进去?还是从当天空账起步?——这是业务决策。

## 我做不了、必须你做的(再强调)

| 事项 | 为什么 |
|---|---|
| 登录阿里云控制台加 DNS / 查备案 / 任何控制台操作 | 我不代输账号密码登录(红线);且无 AccessKey 走 CLI |
| 买新 ECS / 新域名 / RDS 扩容 | 花钱操作,不代下单付款 |
| 工信部备案 | 实名 + 10-20 工作日政府流程 |
| 填 .env 里的真实密码 | 不经手明文密码 |

## 我强烈建议先做的

1. **先验收 + 合 main**:项目现在在 `dev/m4-bi` 分支、未验收。往共享生产机上线未验收代码有风险——建议先本地过一遍、merge,再上线。
2. **轮换泄露密钥**:`API Key.md` 里明文躺着有效的 Anthropic / Kimi key,尽快作废重发。
3. **别和图南抢资源**:4C8G 共享机再挂一套 Spring Boot + nginx,注意内存;必要时给 JVM 限 `-Xmx512m`。
