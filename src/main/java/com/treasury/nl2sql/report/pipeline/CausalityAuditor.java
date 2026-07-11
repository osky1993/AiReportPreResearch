package com.treasury.nl2sql.report.pipeline;

import com.treasury.nl2sql.report.domain.ClaimRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ⑥ 第三类检查：因果措辞审计（Phase05 契约3，纯程序死代码，照 NumberAuditor 范式）。
 * 三条规则，**不依赖任何 prompt**：
 *  1) 正文因果措辞（导致/由于/因为/引发/使得/造成/归因于）只许出现在含事件证据引用（EVT-n）的
 *     段落内——把关联说成因果的构造性防线；
 *  2) 正文出现的 EVT-n 引用必须 ⊆ 本 run claims 的事件证据全集（禁编造事件引用）；
 *  3) claim 材料层：hypothesis 级 narrative 必须含缓和措辞（可能/或与/待验证/有待/疑似）；
 *     无事件证据的 claim（observed/associated）narrative 禁因果措辞，observed 无事件时须含「待查」语义。
 * 违规并入 ⑥ 检查1 的回灌重写循环（claim 材料违规在 AttributionStep 服务端把关已拦一道，此为兜底）。
 */
public final class CausalityAuditor {

    private static final Pattern CAUSAL = Pattern.compile("导致|由于|因为|引发|使得|造成|归因于");
    private static final Pattern HEDGE = Pattern.compile("可能|或与|待验证|有待|疑似|初步");
    private static final Pattern PENDING = Pattern.compile("待查|待核实|未见关联事件");
    private static final Pattern EVT_REF = Pattern.compile("EVT-(\\d+)");

    private CausalityAuditor() {}

    /** 正文扫描（草稿与终稿同规则；占位符替换不改措辞，检查1 循环内拦截即覆盖终稿）。 */
    public static List<String> checkText(String text, List<ClaimRecord> claims) {
        List<String> violations = new ArrayList<>();
        Set<String> allowedRefs = new java.util.HashSet<>();
        for (ClaimRecord c : claims) {
            if (c.evidenceRefs() != null) allowedRefs.addAll(c.evidenceRefs());
        }
        // 规则 2：正文 EVT 引用必须在证据全集内
        Matcher m = EVT_REF.matcher(text);
        while (m.find()) {
            if (!allowedRefs.contains(m.group())) {
                violations.add("正文引用了证据集之外的事件「" + m.group() + "」——事件引用只许来自程序候选并经归因挑选");
            }
        }
        // 规则 1：因果措辞段落必须携带事件证据引用
        for (String paragraph : text.split("\n")) {
            Matcher c = CAUSAL.matcher(paragraph);
            if (c.find() && !EVT_REF.matcher(paragraph).find()) {
                violations.add("段落使用因果措辞「" + c.group() + "」但无事件证据引用（EVT-n）——只许陈述关联，"
                        + "不许把关联说成因果（上下文: …" + context(paragraph, c.start()) + "…）");
            }
        }
        return violations;
    }

    /** claim 材料层校验（AttributionStep 把关后、⑥ 死代码兜底再验一遍）。 */
    public static List<String> checkClaims(List<ClaimRecord> claims) {
        List<String> violations = new ArrayList<>();
        for (ClaimRecord c : claims) {
            String n = c.narrative() == null ? "" : c.narrative();
            if (ClaimRecord.LEVEL_CONFIRMED.equals(c.attributionLevel()) && c.confirmedBy() == null) {
                violations.add("claim " + c.claimId() + " 为 confirmed 但无人工确认记录——该等级仅卡点2 勾选可达");
            }
            if (ClaimRecord.LEVEL_HYPOTHESIS.equals(c.attributionLevel()) && !HEDGE.matcher(n).find()) {
                violations.add("claim " + c.claimId() + "（hypothesis）叙述缺少缓和措辞（可能/或与/待验证…）");
            }
            if (!c.hasEventEvidence()) {
                Matcher causal = CAUSAL.matcher(n);
                if (causal.find()) {
                    violations.add("claim " + c.claimId() + " 无事件证据却使用因果措辞「" + causal.group() + "」");
                }
                if (ClaimRecord.LEVEL_OBSERVED.equals(c.attributionLevel()) && !PENDING.matcher(n).find()) {
                    violations.add("claim " + c.claimId() + "（observed，无事件证据）叙述必须含「待查」语义"
                            + "——无事件记录时只说待查，不编原因");
                }
            }
        }
        return violations;
    }

    private static String context(String s, int at) {
        int a = Math.max(0, at - 15);
        int b = Math.min(s.length(), at + 20);
        return s.substring(a, b).replaceAll("\\s+", " ");
    }
}
