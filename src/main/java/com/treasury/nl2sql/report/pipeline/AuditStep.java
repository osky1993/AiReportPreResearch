package com.treasury.nl2sql.report.pipeline;

import com.treasury.nl2sql.report.domain.AuditResult;
import com.treasury.nl2sql.report.domain.FactRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * ⑥ 证据审计编排：检查1（草稿禁数）违规 → 回灌 LLM 重写（≤ report.max-rewrite-rounds），
 * 超限失败关闭；通过后程序替换占位符，再做检查2（渲染回读核对）。
 * 报告数字一致率 100% 是唯一的发布硬门禁。
 */
@Component
public class AuditStep {

    private static final Logger log = LoggerFactory.getLogger(AuditStep.class);

    private final WriteStep writeStep;
    private final int maxRewriteRounds;

    public AuditStep(WriteStep writeStep, @Value("${report.max-rewrite-rounds:2}") int maxRewriteRounds) {
        this.writeStep = writeStep;
        this.maxRewriteRounds = maxRewriteRounds;
    }

    /** @param reportMd 替换后的终稿（数值带 [fact_xxx] 引用） */
    public record AuditOutcome(String reportMd, AuditResult audit) {}

    /** 纯逻辑单测入口（无归因）。 */
    public AuditOutcome run(WriteStep.Draft draft, Map<String, FactRecord> factsByKey) {
        return run(draft, factsByKey, List.of());
    }

    /** @param claims 归因记录（Phase05：因果措辞审计并入检查1 回灌循环；材料层违规=程序 bug 直接失败关闭） */
    public AuditOutcome run(WriteStep.Draft draft, Map<String, FactRecord> factsByKey,
                            List<com.treasury.nl2sql.report.domain.ClaimRecord> claims) {
        // claim 材料层（narrative/等级/证据形态）在 AttributionStep 已把关——此处死代码兜底，违规即程序缺陷
        List<String> claimViolations = CausalityAuditor.checkClaims(claims);
        if (!claimViolations.isEmpty()) {
            throw new PolicyException("归因材料未通过死代码兜底检查（把关层缺陷）: "
                    + String.join("；", claimViolations));
        }
        String md = draft.reportMd();
        int round = 0;
        List<String> violations = draftViolations(md, factsByKey, claims);
        while (!violations.isEmpty() && round < maxRewriteRounds) {
            round++;
            log.info("[AUDIT] 草稿检查违规 {} 处，回灌重写第 {} 轮: {}", violations.size(), round, violations);
            md = writeStep.rewrite(draft.conversation(), violations);
            violations = draftViolations(md, factsByKey, claims);
        }
        if (!violations.isEmpty()) {
            throw new PolicyException("证据审计未通过（重写 " + round + " 轮后仍有 " + violations.size()
                    + " 处违规）: " + String.join("；", violations.subList(0, Math.min(3, violations.size()))));
        }

        String rendered = NumberAuditor.dedupeUnitAfterRef(NumberAuditor.substitute(md, factsByKey));
        AuditResult audit = NumberAuditor.verifyRendered(rendered, factsByKey, round);
        if (!audit.passed()) {
            // 理论上只有渲染器 bug 会走到这里——检查2 是程序对自身的自证，同样失败关闭
            throw new PolicyException("渲染后回读一致率未达 100%（" + audit.matchedNumbers() + "/"
                    + audit.totalNumbers() + "）: "
                    + String.join("；", audit.violations().subList(0, Math.min(3, audit.violations().size()))));
        }
        // 因果措辞对终稿再验一遍（占位符替换不改措辞，理论必过——程序自证）
        List<String> causal = CausalityAuditor.checkText(rendered, claims);
        if (!causal.isEmpty()) {
            throw new PolicyException("终稿因果措辞核对未通过: " + String.join("；", causal));
        }
        log.info("[AUDIT] 通过：核对数字 {} 个，一致率 100%，重写 {} 轮", audit.totalNumbers(), round);
        return new AuditOutcome(rendered, audit);
    }

    /** 检查1 违规集 = 禁数扫描（NumberAuditor）∪ 因果措辞审计（CausalityAuditor，Phase05）。 */
    private static List<String> draftViolations(String md, Map<String, FactRecord> factsByKey,
                                                List<com.treasury.nl2sql.report.domain.ClaimRecord> claims) {
        List<String> violations = new java.util.ArrayList<>(NumberAuditor.checkDraft(md, factsByKey));
        violations.addAll(CausalityAuditor.checkText(md, claims));
        return violations;
    }
}
