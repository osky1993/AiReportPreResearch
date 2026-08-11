package com.treasury.nl2sql.report.pipeline;

import com.treasury.nl2sql.report.domain.AuditResult;
import com.treasury.nl2sql.report.domain.FactRecord;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ⑥ 证据审计算法本体（纯程序，无 LLM，可单测）。双检：
 *  检查1 checkDraft —— 替换前草稿：LLM 只允许写 {{fact_key}} 占位符，
 *    剥离占位符与白名单（日期/周标签/年份）后出现任何阿拉伯数字即违规（LLM 私写数字，构造上防转录错误）；
 *    占位符引用的 fact 必须存在且 PASSED。违规回灌重写，超限失败关闭。
 *  检查2 verifyRendered —— 程序替换后的终稿：抽取全部「数值[fact_xxx]」对，
 *    把展示串反解析回数值与 fact.value 比对（容差=展示精度的半个末位），
 *    并确认不存在无引用尾随的裸数字。一致率必须 100%（理论上只有渲染器 bug 会触发，是程序对自身的自证）。
 * 中文数词（"三成""两千万"）自 Phase02 起进入双检射程（ChineseNumeralDetector，双条件判定）；
 * 检测器刻意「宁漏报不误报」，射程外的残余中文数量表达仍由 prompt 禁用 + HITL 卡点2 人工兜底。
 */
public final class NumberAuditor {

    private NumberAuditor() {}

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*(fact_[A-Za-z0-9_]+)\\s*\\}\\}");
    /** 白名单：ISO 日期、ISO 周标签、裸周（W26）、第N周、YYYY年——周期表述不是指标值。 */
    private static final Pattern WHITELIST = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}|\\d{4}-W\\d{2}|(?<![\\w-])W\\d{1,2}|第\\s*\\d{1,2}\\s*周|\\d{4}\\s*年");
    private static final Pattern BARE_NUMBER = Pattern.compile("[-+]?\\d[\\d,]*(?:\\.\\d+)?\\s*(?:%|万亿|亿|万)?");
    /** 终稿里的「数值[fact_xxx]」对（由程序替换产生，格式已知；[A-Z]{3} 为外币码如 USD）。 */
    private static final Pattern RENDERED = Pattern.compile(
            "([-+]?[\\d,]+(?:\\.\\d+)?)\\s*(%|亿元|万元|元|笔|个|户|单|项|[A-Z]{3})?\\[(fact_[A-Za-z0-9_]+)\\]");

    /** 检查1：草稿禁数扫描 + 占位符引用校验。返回违规清单（空=通过）。 */
    public static List<String> checkDraft(String draft, Map<String, FactRecord> facts) {
        List<String> violations = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(draft);
        while (m.find()) {
            String key = m.group(1);
            FactRecord fact = facts.get(key);
            if (fact == null) {
                violations.add("引用了不存在的事实: {{" + key + "}}");
            } else if (!FactRecord.QUALITY_PASSED.equals(fact.qualityStatus())) {
                violations.add("引用了未通过质量校验的事实: {{" + key + "}}");
            }
        }
        // 先去掉占位符再去白名单，保证 {{fact_xxx}} 内的数字不会误报为裸数字违例。
        String stripped = WHITELIST.matcher(PLACEHOLDER.matcher(draft).replaceAll(" "))
                .replaceAll(" ");
        Matcher n = BARE_NUMBER.matcher(stripped);
        while (n.find()) {
            violations.add("正文出现了未经事实引用的裸数字「" + n.group().trim() + "」（上下文: …"
                    + context(stripped, n.start(), n.end()) + "…）；数值一律用 {{fact_key}} 占位符表达");
        }
        scanChineseNumerals(stripped, violations);
        return violations;
    }

    /** 中文数量表达扫描（检查1/检查2 共用）：数值以任何书写形态出现都必须走 {{fact_key}} 占位符。 */
    private static void scanChineseNumerals(String text, List<String> violations) {
        for (ChineseNumeralDetector.Hit hit : ChineseNumeralDetector.detect(text)) {
            violations.add("正文出现了中文数量表达「" + hit.text() + "」（上下文: …"
                    + context(text, hit.start(), hit.end()) + "…）；数值一律用 {{fact_key}} 占位符表达，禁用中文数词");
        }
    }

    /** 程序替换：{{fact_key}} → 「display_value[fact_key]」。数值由程序写入，转录错误在构造上不可能发生。 */
    public static String substitute(String draft, Map<String, FactRecord> facts) {
        Matcher m = PLACEHOLDER.matcher(draft);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            FactRecord fact = facts.get(m.group(1));
            if (fact == null) {
                throw new IllegalStateException("替换阶段遇到未知事实引用（检查1 应已拦截）: " + m.group(1));
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(fact.displayValue() + "[" + fact.factKey() + "]"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 量词去重构型：display_value 尾随单位 + [fact_xxx] 引用 + 紧跟的同一单位字（LLM 在占位符后重复量词）。 */
    private static final Pattern DUP_UNIT = Pattern.compile(
            "(亿元|万元|元|笔|个|户|单|项)(\\[fact_[A-Za-z0-9_]+\\])\\1");

    /**
     * 行文后处理（P2-T5，替换后、检查2 前）：去重占位符引用后重复的量词——
     * 「4 笔[fact_x]笔」→「4 笔[fact_x]」。只处理「引用后紧跟同一单位字」一种构型，
     * 数值与引用零改动；改坏了也有检查2 兜底（fail-closed 优于 silent-wrong）。
     */
    public static String dedupeUnitAfterRef(String rendered) {
        return DUP_UNIT.matcher(rendered).replaceAll("$1$2");
    }

    /** 检查2：终稿回读核对。一致率必须 100%，明细写入 audit_json 作为发布证据。 */
    public static AuditResult verifyRendered(String rendered, Map<String, FactRecord> facts, int rewriteRounds) {
        List<AuditResult.NumberCheck> details = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        Matcher m = RENDERED.matcher(rendered);
        // 逐条校验 rendered: fact引用必须存在且金额回算误差不超过展示单位半个最小刻度。
        StringBuilder residualBuf = new StringBuilder();
        int last = 0;
        while (m.find()) {
            residualBuf.append(rendered, last, m.start());
            last = m.end();
            String numText = m.group(1);
            String unitText = m.group(2) == null ? "" : m.group(2);
            String key = m.group(3);
            FactRecord fact = facts.get(key);
            if (fact == null) {
                violations.add("终稿引用了不存在的事实: [" + key + "]");
                details.add(new AuditResult.NumberCheck(m.group(), key, null, null, false));
                continue;
            }
            Parsed parsed = parseBack(numText, unitText, fact.unit());
            boolean ok = parsed != null
                    && fact.value().subtract(parsed.value()).abs().compareTo(parsed.tolerance()) <= 0;
            if (!ok) {
                violations.add("文中数字「" + m.group() + "」与事实 " + key + " 的值 " + fact.value() + " 不一致");
            }
            details.add(new AuditResult.NumberCheck(m.group(), key, fact.value(),
                    parsed == null ? null : parsed.value(), ok));
        }
        residualBuf.append(rendered, last, rendered.length());

        // 残余文本里不允许再有裸数字（无 [fact_xxx] 尾随）——阿拉伯与中文数量表达同罪
        String residual = WHITELIST.matcher(residualBuf.toString()).replaceAll(" ");
        Matcher n = BARE_NUMBER.matcher(residual);
        int bare = 0;
        while (n.find()) {
            bare++;
            violations.add("终稿存在未经事实引用的裸数字「" + n.group().trim() + "」（上下文: …"
                    + context(residual, n.start(), n.end()) + "…）");
        }
        int chineseBefore = violations.size();
        scanChineseNumerals(residual, violations);
        bare += violations.size() - chineseBefore;

        int matched = (int) details.stream().filter(AuditResult.NumberCheck::ok).count();
        int total = details.size() + bare;
        boolean passed = violations.isEmpty();
        return new AuditResult(passed, total, matched, rewriteRounds, violations, details);
    }

    /**
     * 解析终稿中单个「数值[fact_xxx]」的反解析结果。
     * value 为回算后的标准值，tolerance 为单位约束下的容差（与展示位数相关）。
     */
    private record Parsed(BigDecimal value, BigDecimal tolerance) {}

    /** 展示串反解析回 fact 数值空间：千分位/万元×10000/百分号/符号；单位与 fact.unit 不相容 → null（判不一致）。 */
    private static Parsed parseBack(String numText, String unitText, String factUnit) {
        BigDecimal raw;
        try {
            raw = new BigDecimal(numText.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
        if ("percent".equals(factUnit)) {
            return "%".equals(unitText) ? new Parsed(raw, new BigDecimal("0.05")) : null;
        }
        if ("CNY".equals(factUnit)) {
            if ("万元".equals(unitText)) {
                return new Parsed(raw.multiply(BigDecimal.valueOf(10000)), new BigDecimal("50"));
            }
            if ("元".equals(unitText)) {
                return new Parsed(raw, new BigDecimal("0.005"));
            }
            return null;
        }
        // gk 国库域：fact 值即以亿元计（无量级换算），两位小数展示 → 容差半个末位
        if ("亿元".equals(factUnit)) {
            return "亿元".equals(unitText) ? new Parsed(raw, new BigDecimal("0.005")) : null;
        }
        // 外币码（USD/EUR 等）：单位须一致，金额展示 2 位小数 → 容差半个末位
        if (factUnit != null && factUnit.matches("[A-Z]{3}")) {
            return factUnit.equals(unitText) ? new Parsed(raw, new BigDecimal("0.005")) : null;
        }
        // 计数类：单位须与 fact 单位一致（笔/个/户/单/项）
        if (!unitText.equals(factUnit)) {
            return null;
        }
        return new Parsed(raw, new BigDecimal("0.5"));
    }

    /**
     * 取违规段落附近上下文片段（字符级）用于可读性更好的问题报告。
     * 刻意保留最多 15 字前后，兼顾上下文充分性与日志长度可控。
     */
    private static String context(String s, int from, int to) {
        int a = Math.max(0, from - 15);
        int b = Math.min(s.length(), to + 15);
        return s.substring(a, b).replaceAll("\\s+", " ");
    }
}
