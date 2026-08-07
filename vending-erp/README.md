# 智慧园区售卖机 ERP

前后端骨架(M1-0)。技术栈:Spring Boot 2.7 + MyBatis-Plus + Flyway + MySQL 8 / Vue 3 + Vite 5 + Element Plus。

## 本地起步(三条命令)

```bash
# 1. 起数据库(MySQL 8 · 127.0.0.1:3308 · root/vend123 · 库 vend_dev)
docker compose up -d

# 2. 起后端(端口 8081 · context-path /api;国内网络用阿里云镜像加 -s settings.xml)
#    注意:本机 brew 默认 openjdk 是 26,须指到 JDK 17(JDK 23+ 默认关注解处理,且 SB2.7 不保证兼容)
cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -s settings.xml spring-boot:run

# 3. 起前端(Vite dev server · /api 代理到 8081)
cd frontend && pnpm install && pnpm dev
```

验证:`curl http://127.0.0.1:8081/api/v1/health` 应返回 `{"code":200,...}`。

## 目录

- `backend/` — Spring Boot 2.7.18(JDK 17 可跑,字节码 release=8 保持 ole 部署兼容),包名 `top.aole.vend`,DDD 分层 `modules/<module>/{interfaces,application,domain,infrastructure}`
- `frontend/` — Vue 3.4 + Vite 5 + TS + Element Plus 2.6 + Pinia + UnoCSS
- `backend/src/main/resources/db/migration/` — Flyway 迁移脚本(V1.0.0__placeholder.sql 占位,后续替换真 DDL)

## 约定

- 统一返回体 `R{code,message,data}`,code=200 成功
- 接口文档 Knife4j:http://127.0.0.1:8081/api/doc.html
- SSO 留位(`sso.enabled=false`),AI 网关开发期全 mock(`MockLlmService`),不发真请求
- LLM key 等敏感配置走 ENV,见 `backend/.env.example` / `frontend/.env.example`
