package com.treasury.nl2sql.report.pipeline;

import com.treasury.nl2sql.report.domain.ClaimRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ⑥ 因果一致性审计对抗单测（纯逻辑）。验证文本与事实级 claim 在“事件证据”约束下的红线行为：
 * 1) 无事实支撑的因果措辞必须拦截；
 * 2) 未申明事件引用不能伪造事件号；
 * 3) claim.level 与措辞/证据关系必须一致；
 * 4) 合法文本不受白名单规则误伤。
 */
class CausalityAuditorTest {

    private static ClaimRecord claim(String id, String level, List<String> refs, String narrative) {
        return new ClaimRecord(id, "fact_002_anom", level, refs, narrative, null, null);
    }

    private static final List<ClaimRecord> CLAIMS = List.of(
            claim("cl_001", "hypothesis", List.of("fact_002_anom", "EVT-1"),
                    "本期交易总额显著上升（{{fact_002_anom}}），可能与华东大客户季度回款集中到账有关，待验证。"));

    // ---------- 对抗语料（逐条命中） ----------

    /**
     * 输入：不带 EVT 引用的因果句式。
     * 预期：每条匹配都会返回异常项，防止“拍脑袋”因果替代事实。
     */
    @Test
    void causalWordingWithoutEventEvidenceIsCaught() {
        String[] payloads = {
                "交易额上升是由于市场回暖。",
                "净流入增加，因为客户提前付款。",
                "本周波动主要由汇率变化导致。",
                "季节性因素引发了交易量激增。",
                "系统升级使得交易延迟下降。",
                "大额支出造成了头寸紧张。",
                "本次异动可归因于结算周期调整。",
        };
        for (String p : payloads) {
            assertFalse(CausalityAuditor.checkText(p, CLAIMS).isEmpty(), "应拦截无事件证据的因果措辞: " + p);
        }
    }

    /**
     * 输入：引用不存在事件编号、或文本层虚构事件。
     * 预期：按事件白名单与 fact/EVT 映射规则拦截，阻断二次造假。
     */
    @Test
    void fabricatedEventReferencesAreCaught() {
        // EVT-9 不在任何 claim 的证据集内——编造事件引用
        assertFalse(CausalityAuditor.checkText("异动可能与系统故障（证据：EVT-9）有关。", CLAIMS).isEmpty());
        assertFalse(CausalityAuditor.checkText("由于 EVT-42 的政策调整，交易大增。", CLAIMS).isEmpty());
    }

    /**
     * 输入：claim 的 level 与措辞、事实引用组合不一致（hypothesis/observed/confirmed）。
     * 预期：多维度违规逐条收集，且允许复合违规一次性返回。
     */
    @Test
    void claimLevelViolationsAreCaught() {
        // hypothesis 缺缓和措辞
        assertFalse(CausalityAuditor.checkClaims(List.of(
                claim("cl_x1", "hypothesis", List.of("EVT-1"), "交易上升与大客户回款直接相关。"))).isEmpty());
        // 无事件证据却用因果措辞
        assertFalse(CausalityAuditor.checkClaims(List.of(
                claim("cl_x2", "associated", List.of("fact_002_anom", "fact_002_anom_cny_contrib"),
                        "上升由 CNY 贡献导致。"))).isEmpty());
        // observed 无事件不含「待查」语义（编了个含糊说法）
        assertFalse(CausalityAuditor.checkClaims(List.of(
                claim("cl_x3", "observed", List.of("fact_002_anom"), "指标出现明显上升，市场情绪积极。"))).isEmpty());
        // confirmed 无人工确认记录
        assertFalse(CausalityAuditor.checkClaims(List.of(
                claim("cl_x4", "confirmed", List.of("EVT-1"), "可能与回款有关，待验证。"))).isEmpty());
        // 组合注入：hypothesis + 因果断言 + 编造引用（多处违规都要报）
        List<String> multi = CausalityAuditor.checkClaims(List.of(
                claim("cl_x5", "hypothesis", List.of("fact_002_anom"), "由于监管新政导致激增。")));
        assertTrue(multi.size() >= 2, "多处违规逐条收集: " + multi);
    }

    // ---------- 合法语料（零误伤） ----------

    /**
     * 输入：合法 claim 与带 evidence 的文本。
     * 预期：不应产生误伤，保障人工可用文本通过。
     */
    @Test
    void legitimateTextPasses() {
        String[] ok = {
                "本期交易总额{{fact_002}}，环比{{fact_002_wow}}，较上期明显上升。",
                "【假设·待验证】本期异动（{{fact_002_anom}}）可能与华东大客户季度回款集中到账有关（证据：EVT-1）。",
                "由于华东大客户季度回款集中到账（证据：EVT-1），流入显著高于常态——该解释为假设，待验证。",
                "CNY 维度贡献了变化的大头[fact_002_anom_cny_contrib]，占比{{fact_002_anom_cny_contrib_share}}。",
                "变动原因待查，未见关联事件记录。",
                "资金结构以定期存款为主，整体头寸保持充裕。",
                "较上月与去年同期均上升，表述区分环比与同比。",
        };
        for (String p : ok) {
            assertTrue(CausalityAuditor.checkText(p, CLAIMS).isEmpty(), "合法语料被误伤: " + p);
        }
    }

    /**
     * 输入：合法 claim 的组合。
     * 预期：只在确证/观察/假设规则满足时通过，输出空列表。
     */
    @Test
    void legitimateClaimsPass() {
        assertTrue(CausalityAuditor.checkClaims(List.of(
                claim("cl_ok1", "hypothesis", List.of("fact_002_anom", "EVT-1"),
                        "异动可能与华东大客户季度回款集中到账有关，待验证。"),
                claim("cl_ok2", "observed", List.of("fact_002_anom"),
                        "指标出现异动（{{fact_002_anom}}），变动原因待查，未见关联事件记录。"),
                claim("cl_ok3", "associated", List.of("fact_002_anom", "fact_002_anom_cny_contrib"),
                        "异动与 CNY 维度的贡献变化相关联（{{fact_002_anom_cny_contrib_share}}）。"),
                new ClaimRecord("cl_ok4", "fact_002_anom", "confirmed", List.of("EVT-1"),
                        "已确认与大客户回款有关。", "approver-a", java.time.LocalDateTime.now())
        )).isEmpty());
    }
}
