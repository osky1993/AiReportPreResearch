package com.treasury.nl2sql.report.asset;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 指标元数据（description/category，Gate 2）序列化兼容性单测：
 * 展示层元数据是可选增量——旧资产（库中无这两字段的 body_json）回读必须为 null 且回写不产生新字段
 * （版本行不可变，绝不能因为反序列化-再序列化就让旧版本 body 漂移）；新资产 roundtrip 保真。
 */
class MetricMetadataCompatTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static final String OLD_FORMAT = """
        {"metricId":"cny_total_balance","name":"人民币活跃账户余额合计","unit":"CNY",
         "timeBound":false,"comparable":false,"valueColumn":"total_balance",
         "nullPolicy":"BLOCK","qualityChecks":["NON_NEGATIVE"]}""";

    /** 旧格式 body_json（无 description/category）回读为 null，不炸、不影响既有字段。 */
    @Test
    void oldFormatDeserializesWithNullMetadata() throws Exception {
        MetricDefinition m = mapper.readValue(OLD_FORMAT, MetricDefinition.class);
        assertNull(m.description());
        assertNull(m.category());
        assertEquals("cny_total_balance", m.metricId());
        assertEquals("BLOCK", m.nullPolicy());
    }

    /** 旧格式回读再序列化：NON_NULL 保证不凭空多出 description/category 键（旧版本 body 不漂移）。 */
    @Test
    void oldFormatRoundtripDoesNotIntroduceNewKeys() throws Exception {
        MetricDefinition m = mapper.readValue(OLD_FORMAT, MetricDefinition.class);
        String out = mapper.writeValueAsString(m);
        assertFalse(out.contains("description"));
        assertFalse(out.contains("category"));
    }

    /** 新格式带元数据 roundtrip 保真。 */
    @Test
    void newFormatRoundtripKeepsMetadata() throws Exception {
        String json = """
            {"metricId":"m1","name":"指标","unit":"CNY","timeBound":true,"comparable":true,
             "valueColumn":"v","nullPolicy":"ZERO",
             "description":"报告期内成功交易金额折人民币加总","category":"交易与收支"}""";
        MetricDefinition m = mapper.readValue(json, MetricDefinition.class);
        assertEquals("报告期内成功交易金额折人民币加总", m.description());
        assertEquals("交易与收支", m.category());
        String out = mapper.writeValueAsString(m);
        MetricDefinition back = mapper.readValue(out, MetricDefinition.class);
        assertEquals(m.description(), back.description());
        assertEquals(m.category(), back.category());
    }

    /** 旧签名兼容构造器（12 参）：description/category 置 null，既有调用方零改动。 */
    @Test
    void legacyConstructorDefaultsMetadataToNull() {
        MetricDefinition m = new MetricDefinition("m1", "指标", "CNY", true, false, "v", "ZERO",
                List.of(), null, null, null, null);
        assertNull(m.description());
        assertNull(m.category());
    }

    /** 种子文件守卫：17 条种子指标全部补齐 description 与 category（惠及新环境）。 */
    @Test
    void seedMetricsAllCarryMetadata() throws Exception {
        MetricDefinition[] seeds;
        try (var in = new ClassPathResource("report/metrics.json").getInputStream()) {
            seeds = mapper.readValue(in, MetricDefinition[].class);
        }
        assertTrue(seeds.length >= 17);
        for (MetricDefinition m : seeds) {
            assertNotNull(m.description(), m.metricId() + " 缺 description");
            assertFalse(m.description().isBlank(), m.metricId() + " description 为空白");
            assertNotNull(m.category(), m.metricId() + " 缺 category");
        }
    }
}
