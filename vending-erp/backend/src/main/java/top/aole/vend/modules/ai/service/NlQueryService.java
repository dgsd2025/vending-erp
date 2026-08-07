package top.aole.vend.modules.ai.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import top.aole.vend.common.config.LlmProperties;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.ai.domain.AiScenes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 接入点#6:BI 自然语言查数(实验性)。
 *
 * 安全铁律(三重闸,顺序不可颠倒):
 * 1. 只读白名单视图:SQL 的表名只允许 v_ai_month_gross / v_ai_machine_daily_sales /
 *    v_ai_stock_current / v_ai_loss / v_ai_cash_summary(V1.0.16 建),碰任何业务表直接拒。
 * 2. 只允许 SELECT + 强制 LIMIT:禁 insert/update/delete/drop/;多语句/注释/子查询里的写操作一律拒。
 * 3. mock 版:NL → 预置 5 个常见问题模板匹配(关键词打分),不外呼;真 key 版走 LLM text2SQL,
 *    生成的 SQL 仍要过闸1/闸2 才执行(LLM 生成也不信任)。
 */
@Service
@RequiredArgsConstructor
public class NlQueryService {

    /** 白名单视图(闸1) */
    public static final List<String> WHITELIST_VIEWS = Arrays.asList(
            "v_ai_month_gross", "v_ai_machine_daily_sales", "v_ai_stock_current", "v_ai_loss", "v_ai_cash_summary");

    private static final int MAX_LIMIT = 200;
    private static final Pattern FORBIDDEN = Pattern.compile(
            "\\b(insert|update|delete|drop|alter|create|truncate|replace|grant|call|merge|into)\\b",
            Pattern.CASE_INSENSITIVE);
    /** 识别 SQL 里出现的表名(FROM/JOIN 后的标识符) */
    private static final Pattern TABLE_REF = Pattern.compile(
            "(?:from|join)\\s+([a-zA-Z_][a-zA-Z0-9_]*)", Pattern.CASE_INSENSITIVE);
    /**
     * 抓 FROM 子句主体(from 到下一个 SQL 子句关键字或 join/结尾前的部分)。
     * 逗号连表(SELECT * FROM a, b)是隐式交叉连接:TABLE_REF 只认 from/join 后的第一个表,
     * 逗号后的表既无 from 也无 join → 正则漏检 → 绕过白名单。本模式专门把 FROM 子句抠出来查逗号。
     */
    private static final Pattern FROM_CLAUSE = Pattern.compile(
            "\\bfrom\\b(.*?)(?=\\bwhere\\b|\\bgroup\\b|\\bhaving\\b|\\border\\b|\\blimit\\b|\\bunion\\b|\\bjoin\\b|$)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final JdbcTemplate jdbcTemplate;
    private final LlmGateway llmGateway;
    private final LlmProperties llmProperties;

    /** 预置模板(mock text2SQL):关键词命中即用其 SQL */
    @Value
    static class Template {
        List<String> keywords;
        String question;
        String sql;
    }

    private static final List<Template> TEMPLATES = Arrays.asList(
            new Template(Arrays.asList("毛利", "赚", "利润"), "各机器本月毛利排行",
                    "SELECT 机器, SUM(毛利) AS 毛利 FROM v_ai_month_gross GROUP BY 机器 ORDER BY 毛利 DESC"),
            new Template(Arrays.asList("日销", "每天", "卖了多少", "销量"), "近期机器日销",
                    "SELECT 日期, 机器, 销量件数, 实收金额 FROM v_ai_machine_daily_sales ORDER BY 日期 DESC"),
            new Template(Arrays.asList("库存", "还剩", "结存", "现货"), "当前库存现状",
                    "SELECT 位置, 机器, 商品, 结存数量, 成本金额 FROM v_ai_stock_current ORDER BY 成本金额 DESC"),
            new Template(Arrays.asList("损耗", "丢", "亏", "报损", "盘亏"), "损耗按原因汇总",
                    "SELECT 月份, 原因, 损耗件数, 损耗金额 FROM v_ai_loss ORDER BY 月份 DESC, 损耗金额 DESC"),
            new Template(Arrays.asList("现金", "流水", "收支", "花了", "账户"), "资金流水按类别汇总",
                    "SELECT 月份, 方向, 类别, 金额, 笔数 FROM v_ai_cash_summary ORDER BY 月份 DESC")
    );

    /** 查数结果 */
    @Value
    public static class QueryResult {
        String question;
        String matchedTemplate;
        String sql;
        List<Map<String, Object>> rows;
        Long llmCallId;
        boolean cacheHit;
        String mode;
    }

    /**
     * 自然语言查数主入口。
     *
     * @param nl 用户问题
     */
    public QueryResult ask(String nl, boolean force) {
        if (StrUtil.isBlank(nl)) {
            throw new BizException("请先输入要查什么");
        }
        // NL → SQL(mock:模板匹配;真 key:LLM text2SQL,生成后仍过闸)
        Match match = matchBest(nl);
        Template tpl = match.getTpl();
        String sql;
        String matched;
        if (tpl != null) {
            sql = tpl.getSql();
            matched = tpl.getQuestion();
        } else {
            // 没命中模板:mock 兜底给毛利表(真 key 版这里换成 LLM 生成 SQL)
            sql = TEMPLATES.get(0).getSql();
            matched = "未精确匹配,默认给「各机器本月毛利」(真网关接入后由 LLM 按问题生成 SQL)";
        }

        String safeSql = enforceSafety(sql); // 闸1+闸2,LLM 生成也照过
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(safeSql);

        // 置信分真算:命中模板的关键词占比(matchRatio)映射到 0.50~0.90;未命中给低基准 0.30。
        // matchRatio = 命中关键词数 / 该模板关键词总数,越贴合问题越高。
        BigDecimal confidence = tpl != null
                ? new BigDecimal(0.50 + 0.40 * match.getRatio()).setScale(2, RoundingMode.HALF_UP)
                : new BigDecimal("0.30");

        String draft = String.format("[MOCK] 按「%s」查得 %d 行结果。%s", matched, rows.size(),
                llmProperties.isConfigured() ? "" : "(实验性:mock 用预置模板匹配,真网关接入后支持任意提问)");
        LlmGateway.GatewayResult gw = llmGateway.invoke(LlmGateway.LlmTask.builder()
                .scene(AiScenes.NLQUERY)
                .bizKey("q:" + nl.trim())
                .prompt("你是售卖机 BI 查数助手,只能查只读白名单视图。用户问:" + nl
                        + "\n草稿:" + draft)
                .reasoning("NL→SQL:mock 模板匹配「" + matched + "」;执行前过安全闸(仅 SELECT 白名单视图+LIMIT)。SQL=" + safeSql)
                .inputDigest(new JSONObject().set("question", nl).set("sql", safeSql)
                        .set("rowCount", rows.size()).set("matchRatio", match.getRatio()).toString())
                .confidence(confidence)
                // 真算:置信=模板匹配度(命中关键词占比);未命中走兜底表则低置信
                .confidenceSource("computed")
                .promptFingerprint("nlquery-v1")
                .fallbackText(draft)
                .build(), force);

        return new QueryResult(nl, matched, safeSql, rows, gw.getCall().getId(), gw.isCacheHit(),
                llmProperties.isConfigured() ? "LLM text2SQL" : "mock模板匹配");
    }

    /** 提问建议(前端问数框下方 chips) */
    public List<String> suggestions() {
        List<String> list = new ArrayList<>();
        for (Template t : TEMPLATES) {
            list.add(t.getQuestion());
        }
        return list;
    }

    // ============================== 安全闸 ==============================

    /**
     * 闸1(白名单视图)+ 闸2(只 SELECT + 强制 LIMIT)。任何生成的 SQL(含 LLM 生成)执行前必过。
     * public 便于集成测试直接打点:非法表名/非 SELECT 都要抛 BizException。
     */
    public String enforceSafety(String rawSql) {
        if (StrUtil.isBlank(rawSql)) {
            throw new BizException("空 SQL");
        }
        String sql = rawSql.trim().replaceAll(";+\\s*$", ""); // 去尾分号
        String lower = sql.toLowerCase();
        // 单语句:去尾分号后不允许再有分号(防多语句注入)
        if (sql.contains(";")) {
            throw new BizException("拒绝执行:不允许多条语句");
        }
        if (lower.contains("--") || lower.contains("/*")) {
            throw new BizException("拒绝执行:不允许 SQL 注释");
        }
        if (!lower.startsWith("select")) {
            throw new BizException("拒绝执行:只允许 SELECT 查询");
        }
        if (FORBIDDEN.matcher(lower).find()) {
            throw new BizException("拒绝执行:检测到写操作关键字");
        }
        // 闸1.0(P1-1 补漏):禁止逗号连表——FROM 子句里出现逗号 = 隐式多表交叉连接,
        // 只允许「单表 或 显式 JOIN 白名单视图」。逗号后的表正则数不到,会绕过白名单,直接拒。
        java.util.regex.Matcher fromM = FROM_CLAUSE.matcher(lower);
        while (fromM.find()) {
            if (fromM.group(1).contains(",")) {
                throw new BizException("拒绝执行:不允许逗号连表(隐式多表),只允许单表或显式 JOIN 白名单视图");
            }
        }
        // 闸1:所有 FROM/JOIN 的表名必须在白名单
        java.util.regex.Matcher m = TABLE_REF.matcher(lower);
        boolean sawTable = false;
        while (m.find()) {
            sawTable = true;
            String table = m.group(1);
            if (!WHITELIST_VIEWS.contains(table)) {
                throw new BizException("拒绝执行:表「" + table + "」不在只读白名单视图内(只允许:"
                        + String.join("/", WHITELIST_VIEWS) + ")");
            }
        }
        if (!sawTable) {
            throw new BizException("拒绝执行:未识别到白名单视图");
        }
        // 闸2:强制 LIMIT
        if (!lower.contains("limit")) {
            sql = sql + " LIMIT " + MAX_LIMIT;
        }
        return sql;
    }

    /** 模板匹配结果:命中的模板 + 匹配度(命中关键词/该模板关键词总数);未命中 tpl=null,ratio=0 */
    @Value
    static class Match {
        Template tpl;
        double ratio;
    }

    private Match matchBest(String nl) {
        String q = nl.toLowerCase();
        Template best = null;
        int bestHits = 0;
        int bestKw = 1;
        for (Template t : TEMPLATES) {
            int hits = 0;
            for (String kw : t.getKeywords()) {
                if (q.contains(kw)) {
                    hits++;
                }
            }
            if (hits > bestHits) {
                bestHits = hits;
                best = t;
                bestKw = t.getKeywords().size();
            }
        }
        return bestHits > 0 ? new Match(best, (double) bestHits / bestKw) : new Match(null, 0);
    }
}
