package com.treasury.nl2sql.report.asset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.report.asset.TemplateAdminService.ValidationFailedException;
import com.treasury.nl2sql.report.pipeline.MqlTrialExecutor;
import com.treasury.nl2sql.report.pipeline.PolicyException;
import com.treasury.nl2sql.report.store.AssetRow;
import com.treasury.nl2sql.report.store.MetricAssetRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 指标保存链五重校验与状态流转单测（P5-T3；mock 仓库/注册表、stub 试执行器，零 DB/LLM）。
 * details.location 承载校验类别，前端按类归红字。
 */
class MetricAdminServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final MetricAssetRepository repo = mock(MetricAssetRepository.class);
    private final ReportAssetService assets = mock(ReportAssetService.class);
    private final MqlTrialExecutor trial = mock(MqlTrialExecutor.class);
    private final MetricAdminService service = new MetricAdminService(repo, assets, trial, mapper);

    private JsonNode json(String s) {
        try {
            return mapper.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 合法期间指标（模板含占位符）。 */
    private MetricDefinition periodMetric() {
        return new MetricDefinition("invest_out_amount", "本期投资支出金额", "CNY", true, true,
                "amt", "ZERO", List.of("NON_NEGATIVE"), null, null,
                json("""
                    {"table":"cash_transaction","filter":[
                      {"field":"category","op":"=","value":"INVEST"},
                      {"field":"txn_date","op":">=","value":"{{period_start}}"},
                      {"field":"txn_date","op":"<=","value":"{{period_end}}"}],
                     "metrics":[{"op":"sum","field":"amount","alias":"amt"}]}"""),
                null);
    }

    private void stubHappyTrial() {
        when(trial.validate(any())).thenReturn(List.of());
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("amt", 200.00);
        when(trial.execute(any())).thenReturn(List.of(row));
    }

    private List<String> checksOf(ValidationFailedException e) {
        return e.errors().stream().map(TemplateValidator.ValidationError::location).toList();
    }

    @Test
    void structureViolationsAreCollected() {
        MetricDefinition bad = new MetricDefinition("Bad Id!", " ", null, false, true,
                null, "MAYBE", List.of("POSITIVE"), null, null, null,
                new MetricDefinition.Derived("subtract", "a", "b"));
        ValidationFailedException e = assertThrows(ValidationFailedException.class,
                () -> service.save(bad, null, "demo"));
        List<String> checks = checksOf(e);
        assertTrue(checks.stream().allMatch("STRUCTURE"::equals));
        // metricId/name/unit/valueColumn/nullPolicy/qualityChecks/derived/mqlTemplate/comparable⇒timeBound
        assertTrue(e.errors().size() >= 8, "逐条收集而非首错即停，实际: " + e.errors());
    }

    @Test
    void duplicateMetricIdRejected() {
        when(repo.existsById("invest_out_amount")).thenReturn(true);
        ValidationFailedException e = assertThrows(ValidationFailedException.class,
                () -> service.save(periodMetric(), null, "demo"));
        assertTrue(e.errors().get(0).message().contains("已存在"));
    }

    @Test
    void timeBoundTrueRequiresPlaceholder() {
        MetricDefinition m = new MetricDefinition("m_x1", "x", "CNY", true, false, "amt", "ZERO", null, null, null,
                json("""
                    {"table":"cash_transaction","filter":[{"field":"txn_date","op":">=","value":"2026-06-22"}],
                     "metrics":[{"op":"sum","field":"amount","alias":"amt"}]}"""), null);
        ValidationFailedException e = assertThrows(ValidationFailedException.class,
                () -> service.save(m, null, "demo"));
        assertEquals(List.of("PLACEHOLDER"), checksOf(e));
        assertTrue(e.errors().get(0).message().contains("死日期"));
    }

    @Test
    void timeBoundFalseForbidsPlaceholder() {
        MetricDefinition m = new MetricDefinition("m_x2", "x", "CNY", false, false, "amt", "ZERO", null, null, null,
                json("""
                    {"table":"cash_transaction","filter":[{"field":"txn_date","op":">=","value":"{{period_start}}"}],
                     "metrics":[{"op":"sum","field":"amount","alias":"amt"}]}"""), null);
        ValidationFailedException e = assertThrows(ValidationFailedException.class,
                () -> service.save(m, null, "demo"));
        assertEquals(List.of("PLACEHOLDER"), checksOf(e));
    }

    @Test
    void mqlValidationErrorsAreClassified() {
        when(trial.validate(any())).thenReturn(List.of("表不存在: ghost_table"));
        ValidationFailedException e = assertThrows(ValidationFailedException.class,
                () -> service.save(periodMetric(), null, "demo"));
        assertEquals(List.of("MQL_VALIDATION"), checksOf(e));
    }

    @Test
    void trialExecutionFailureIsClassified() {
        when(trial.validate(any())).thenReturn(List.of());
        when(trial.execute(any())).thenThrow(new PolicyException("试执行失败: Unknown column"));
        ValidationFailedException e = assertThrows(ValidationFailedException.class,
                () -> service.save(periodMetric(), null, "demo"));
        assertEquals(List.of("TRIAL_EXECUTION"), checksOf(e));
    }

    @Test
    void multiRowResultRejected() {
        when(trial.validate(any())).thenReturn(List.of());
        when(trial.execute(any())).thenReturn(List.of(Map.of("amt", 1), Map.of("amt", 2)));
        ValidationFailedException e = assertThrows(ValidationFailedException.class,
                () -> service.save(periodMetric(), null, "demo"));
        assertEquals(List.of("RESULT_SHAPE"), checksOf(e));
        assertTrue(e.errors().get(0).message().contains("2 行"));
    }

    @Test
    void missingValueColumnRejected() {
        when(trial.validate(any())).thenReturn(List.of());
        when(trial.execute(any())).thenReturn(List.of(Map.of("other_col", 1)));
        ValidationFailedException e = assertThrows(ValidationFailedException.class,
                () -> service.save(periodMetric(), null, "demo"));
        assertEquals(List.of("RESULT_SHAPE"), checksOf(e));
        assertTrue(e.errors().get(0).message().contains("amt"));
    }

    @Test
    void happyPathInsertsDraftWithTryQuestionRemark() {
        stubHappyTrial();
        when(repo.insertNewVersion(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString())).thenReturn(1);
        MetricAdminService.SaveResult r = service.save(periodMetric(), "本期投资支出总金额", "wizard");
        assertEquals("DRAFT", r.status());
        assertEquals(1, r.version());
        verify(repo).insertNewVersion(eq("invest_out_amount"), eq("本期投资支出金额"), anyString(),
                eq("DRAFT"), eq("MANUAL"), eq("wizard"), contains("本期投资支出总金额"));
    }

    @Test
    void publishGuardsAndReloads() {
        when(repo.findByIdAndVersion("m_a", 1)).thenReturn(Optional.of(
                new AssetRow(1, "m_a", 1, "a", "{}", "DRAFT", "MANUAL", "u", LocalDateTime.now(), null)));
        when(repo.findByAssetId("m_a")).thenReturn(List.of(
                new AssetRow(1, "m_a", 1, "a", "{}", "DRAFT", "MANUAL", "u", LocalDateTime.now(), null)));
        service.publish("m_a", 1);
        verify(repo).updateStatus("m_a", 1, "PUBLISHED");
        verify(assets).reload();

        // 非 DRAFT 不可发布
        when(repo.findByIdAndVersion("m_a", 2)).thenReturn(Optional.of(
                new AssetRow(2, "m_a", 2, "a", "{}", "DEPRECATED", "MANUAL", "u", LocalDateTime.now(), null)));
        assertThrows(IllegalArgumentException.class, () -> service.publish("m_a", 2));
    }

    @Test
    void deprecatePublishedMetricIsBlockedWhenReferenced() {
        when(repo.findByIdAndVersion("m_used", 1)).thenReturn(Optional.of(
                new AssetRow(1, "m_used", 1, "u", "{}", "PUBLISHED", "SEED", "seed", LocalDateTime.now(), null)));
        when(assets.templatesReferencing("m_used")).thenReturn(List.of("treasury-weekly"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.deprecate("m_used", 1));
        assertTrue(e.getMessage().contains("treasury-weekly"));
        verify(repo, never()).updateStatus(anyString(), anyInt(), anyString());

        // 未被引用的 PUBLISHED 可下架并 reload
        when(repo.findByIdAndVersion("m_free", 1)).thenReturn(Optional.of(
                new AssetRow(3, "m_free", 1, "f", "{}", "PUBLISHED", "MANUAL", "u", LocalDateTime.now(), null)));
        when(assets.templatesReferencing("m_free")).thenReturn(List.of());
        service.deprecate("m_free", 1);
        verify(repo).updateStatus("m_free", 1, "DEPRECATED");
        verify(assets, times(1)).reload();
    }
}
