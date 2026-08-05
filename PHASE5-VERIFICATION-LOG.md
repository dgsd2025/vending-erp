# PHASE5-VERIFICATION-LOG

> 每功能完成即真测(§4.21):每条验收留真实命令输出证据。

## M1-0 脚手架(2026-08-06)

### ① docker compose up -d 后 MySQL 健康 — 通过

```
$ cd vending-erp && docker compose up -d
 Container vend-mysql Created
 Container vend-mysql Started
$ docker inspect --format '{{.State.Health.Status}}' vend-mysql
healthy
$ docker exec vend-mysql mysql -uroot -pvend123 -e "SHOW DATABASES;" | grep vend_dev
vend_dev
```

### ② 后端起起来,health 返回 200,Flyway 建出 flyway_schema_history — 通过

构建(JDK 17 + 阿里云镜像;jar 45MB):

```
$ cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -s settings.xml -DskipTests package
[INFO] BUILD SUCCESS
[INFO] Total time:  01:29 min
$ ls target/*.jar
target/vending-erp-backend-0.1.0-SNAPSHOT.jar   (44990198 bytes)
```

启动 + curl(java -jar 方式,等价于 spring-boot:run):

```
2026-08-06 01:11:27.337  INFO ... o.f.c.internal.license.VersionPrinter    : Flyway Community Edition 8.5.13 by Redgate
2026-08-06 01:11:27.450  INFO ... o.f.core.internal.command.DbMigrate      : Migrating schema `vend_dev` to version "1.0.0 - placeholder"
2026-08-06 01:11:27.467  INFO ... o.f.core.internal.command.DbMigrate      : Successfully applied 1 migration
2026-08-06 01:11:27.556  INFO ... o.s.b.w.e.undertow.UndertowWebServer     : Undertow started on port(s) 8081 (http)
2026-08-06 01:11:27.623  INFO ... top.aole.vend.VendApplication            : Started VendApplication in 1.79 seconds

$ curl -s -w "\nHTTP %{http_code}\n" http://127.0.0.1:8081/api/v1/health
{"code":200,"message":"success","data":"vending-erp backend alive"}
HTTP 200

$ curl -s -o /dev/null -w "doc.html HTTP %{http_code}\n" http://127.0.0.1:8081/api/doc.html
doc.html HTTP 200   (Knife4j 文档页,SpringFoxCompatConfig 兼容补丁生效,无 NPE)
```

Flyway 表验证(SQL 直查 DB):

```
$ docker exec vend-mysql mysql -uroot -pvend123 vend_dev -e "SHOW TABLES; SELECT installed_rank,version,description,success FROM flyway_schema_history;"
Tables_in_vend_dev
flyway_schema_history
installed_rank  version  description  success
1               1.0.0    placeholder  1
```

### ③ 前端 pnpm install + pnpm build — 通过

```
$ cd frontend && pnpm install
.../node_modules/vue-demi postinstall: Done
.../esbuild@0.21.5/node_modules/esbuild postinstall: Done
Done in 991ms using pnpm v11.20.0

$ pnpm build
dist/assets/Dashboard-CsZSLFk5.js    49.96 kB │ gzip:  19.43 kB
dist/assets/index-BAO8FxzC.js     1,041.03 kB │ gzip: 345.10 kB
✓ built in 2.54s
```

### ④ 收尾:后端已停,MySQL 容器保留 — 通过

```
$ kill $(cat /tmp/vend-backend.pid)
$ curl -s -m 3 http://127.0.0.1:8081/api/v1/health || echo "backend stopped"
backend stopped
$ docker inspect --format 'mysql: {{.State.Health.Status}}' vend-mysql
mysql: healthy
```

### 遇到的坑(防复发)

1. **Maven 首跑挂死**:首次 `mvn package` 网络请求挂起十几分钟无进展(CPU 4 秒),杀进程重跑即恢复正常下载。
2. **Homebrew 默认 JDK 是 26**:`mvn` 拿的是 `/opt/homebrew/opt/openjdk`(26),JDK 23+ 默认关闭注解处理 → Lombok 全失效(cannot find symbol: getCode/log/构造器)。修法双保险:pom 里 maven-compiler-plugin 显式 `annotationProcessorPaths` 声明 Lombok + 构建命令 `JAVA_HOME=$(/usr/libexec/java_home -v 17)`。
3. **pnpm 11 构建脚本审批**:esbuild/vue-demi 的 postinstall 被拦(Ignored build scripts),`package.json` 的 `pnpm.onlyBuiltDependencies` 已不生效,要在 `pnpm-workspace.yaml` 写 `allowBuilds: {esbuild: true, vue-demi: true}`。
4. **vite `@` 别名**:tsconfig 的 paths 只管类型,Rollup 构建要在 `vite.config.ts` 里配 `resolve.alias` 才能解析 `@/views/*`。

## M1-1 · 全量 DDL 真库验证(2026-08-06)

- 操作:V1.0.0__placeholder.sql → V1.0.0__init.sql(39 表全量 DDL);DROP/CREATE vend_dev 后由 Flyway 重新迁移
- 证据:`SELECT COUNT(*) ... table_name LIKE 'yc_vend_%'` → **39**;`flyway_schema_history` → `1.0.0 | init | success=1`;/api/v1/health → `{"code":200,...}`
- 坑:老 placeholder 残留 target/classes 导致 "Found more than one migration with version 1.0.0",`rm -rf target/classes/db` 后通过

## M1-6 · 移动加权成本引擎 + 报表 + 期初导入向导(2026-08-06)

- 集成测试 `ReportCostEngineTest` 13/13 绿(vend_test_report 库);全仓回归 `mvn test` **73/73 绿**
- **端到端对平**(vend_e2e_m16 全新库,期初向导三步吃老 Excel 原文件,`verification/scripts/m16_e2e.py` 可复跑):
  采购 27838.54 / 销售 25113.50 分毫不差;总毛利 **9021.83 vs 基准 9058.42(-0.40% <1% ✅)**;
  6月 935.86(-0.15%)/ 7月 8085.97(-0.43%)均 <2% ✅;差异全部归因(SP068 无采购史显「—」+ 一码多品拆池)
- 前端 build ✓ · 浏览器真走 库存页/报表页/期初向导 console 0 错
- 证据全文见 `verification/M1-6.md`

## M1-3 · 导入中心三通道(2026-08-06)

- 范围:`modules/imports/`(两步式上传预览→确认入账 · 三通道 · 批次/行错/整批回滚/重处理待绑定/改价侦测)+ `views/Imports.vue` + V1.0.1 迁移(sale_record 补 alias_barcode_raw)
- 证据(全文见 `verification/M1-3.md`):
  - 集成测试 `ImportServiceTest` 14/14 绿(vend_test_imports 独立库);全量回归 `mvn test` **48/48 绿**
  - 真实数据全量导入:5135 行经 API 导入 13.1s,`SUM(amount_received)=25113.50` 与冲刺0基准对平,0 待绑定;重复导入第二次 `rowDup=5135` 零新增
  - 前端 `pnpm build` ✓;浏览器真流程(真文件上传→预览列映射全✓→确认→批次历史/待绑定抽屉)console 0 错
- 坑:真实数据 913 个订单一单多行会撞 uk(order_no,order_type) → 文件内出现次序加确定性后缀 `#k`;出货明细无商品编号列 → 条码为主/名称兜底;明细实际 3 台设备(数据字典写 2 台是漏数)
