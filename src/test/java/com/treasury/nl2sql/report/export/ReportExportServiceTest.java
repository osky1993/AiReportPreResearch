package com.treasury.nl2sql.report.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.report.asset.ReportAssetService;
import com.treasury.nl2sql.report.domain.FactRecord;
import com.treasury.nl2sql.report.domain.ReportRun;
import com.treasury.nl2sql.report.store.ReportFactRepository;
import com.treasury.nl2sql.report.store.ReportRunRepository;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 导出服务单测（mock 仓库，无 DB/LLM）：
 * - 仅允许 PUBLISHED + 已签发状态导出
 * - 支持 docx/pdf，校验文件魔数与关键 payload
 * - 客户端 image 数据来源仅允许合法 base64+PNG 且 chartId 在清单内
 * - 任何 fact 绑定缺失按 fail-closed 中断
 */
class ReportExportServiceTest {

    private static final String MD = """
            ## 一、核心结论

            本周交易总额385.14 万元[fact_002]，环比+142.2%[fact_002_wow]。

            ## 五、按币种拆解

            | 币种 | 金额 |
            |------|------|
            | CNY | 385.00 万元[fact_017_cny] |
            """;
    private static final String OUTLINE = """
            {"chapters":[{"chapterId":"summary","title":"一、核心结论"},
                         {"chapterId":"by_currency","title":"五、按币种拆解"}]}
            """;
    private static final String CHARTS = """
            [{"chartId":"trend","chapterId":"summary","type":"line","title":"近两周趋势",
              "optionJson":"{}","boundFactKeys":["fact_c01_s1","fact_002"]}]
            """;

    private final ReportRunRepository runRepo = mock(ReportRunRepository.class);
    private final ReportFactRepository factRepo = mock(ReportFactRepository.class);
    private final ReportAssetService assets = mock(ReportAssetService.class);

    private ReportExportService service(String fontPath) {
        when(assets.template(anyString())).thenReturn(Optional.empty());
        return new ReportExportService(runRepo, factRepo, assets, new ObjectMapper(), fontPath);
    }

    private static ReportRun run(String status, String chartsJson) {
        return new ReportRun(37, "生成本周资金周报", "treasury-weekly", 7, null, "2026-W26",
                null, null, null, null, null, null, status, "DONE",
                OUTLINE, chartsJson, MD, "{\"passed\":true}", null,
                "demo", LocalDateTime.now(), "demo", LocalDateTime.of(2026, 7, 11, 10, 0),
                LocalDateTime.now(), LocalDateTime.now());
    }

    private static FactRecord fact(String key, String display, String period) {
        return new FactRecord(key, "m1", 1, "指标", "ch", FactRecord.TYPE_BASE,
                new BigDecimal("1"), "CNY", display, period,
                null, null, null, null, null, null, FactRecord.QUALITY_PASSED, null);
    }

    private void stubHappyPath() {
        when(runRepo.findById(anyLong())).thenReturn(Optional.of(run("PUBLISHED", CHARTS)));
        when(factRepo.findByRun(anyLong())).thenReturn(List.of(
                fact("fact_002", "385.14 万元", "2026-W26"),
                fact("fact_002_wow", "+142.2%", "2026-W26"),
                fact("fact_017_cny", "385.00 万元", "2026-W26"),
                fact("fact_c01_s1", "159.01 万元", "2026-W25")));
    }

    // ---------- 守卫 ----------

    /**
     * 验证导出入口仅接受已签发状态，未签发状态应 fail-closed 并给出明确中文提示。
     */
    @Test
    void nonPublishedRunIsRejected() {
        when(runRepo.findById(anyLong())).thenReturn(Optional.of(run("AWAITING_PUBLISH_APPROVAL", null)));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service("").export(37, "pdf", null));
        assertTrue(e.getMessage().contains("仅已签发"));
    }

    /**
     * 验证 run 不存在与导出格式不支持两类边界都返回 400 风格异常，避免静默降级。
     */
    @Test
    void unknownRunAndUnknownFormatAreRejected() {
        when(runRepo.findById(anyLong())).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service("").export(99, "pdf", null));
        stubHappyPath();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service("").export(37, "html", null));
        assertTrue(e.getMessage().contains("不支持的导出格式"));
    }

    // ---------- Docx ----------

    /**
     * 验证纯 Markdown 导出到 docx 时，生成 OOXML 文件包含目录信息、事实引用与表格透出。
     */
    @Test
    void docxWithoutImagesFallsBackToDataTableAndIsValidOoxml() throws Exception {
        stubHappyPath();
        ReportExportService.ExportFile file = service("").export(37, "docx", null);
        assertEquals("treasury-weekly-2026-W26.docx", file.filename());
        assertEquals('P', file.bytes()[0]);
        assertEquals('K', file.bytes()[1]);
        String documentXml = zipEntry(file.bytes(), "word/document.xml");
        assertNotNull(documentXml, "OOXML 必须含 word/document.xml");
        assertTrue(documentXml.contains("核心结论"));
        assertTrue(documentXml.contains("fact_c01_s1"), "无图时图表必须降级为数据表（含事实引用列）");
        assertTrue(documentXml.contains("385.00 万元"), "管道表格单元格进 Docx 表");
    }

    /**
     * 验证合法 base64 PNG 可作为图表 payload 写入 word/media，防止图片丢失导致排版回退。
     */
    @Test
    void docxEmbedsValidPngImage() throws Exception {
        stubHappyPath();
        String dataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(tinyPng());
        ReportExportService.ExportFile file = service("").export(37, "docx",
                List.of(new ReportExportService.ChartImage("trend", dataUri)));
        assertTrue(zipHasEntryPrefix(file.bytes(), "word/media/"), "上送 PNG 应嵌入 word/media/");
    }

    // ---------- PDF ----------

    /**
     * 验证环境可用中文字体时，pdf 导出返回 PDF 魔数与正确 contentType。
     */
    @Test
    void pdfBytesStartWithMagicWhenFontAvailable() {
        assumeTrue(new File("/System/Library/Fonts/Supplemental/Arial Unicode.ttf").isFile(),
                "无可用中文字体的环境跳过 PDF 渲染用例");
        stubHappyPath();
        ReportExportService.ExportFile file = service("").export(37, "pdf", null);
        assertEquals("%PDF", new String(file.bytes(), 0, 4, StandardCharsets.US_ASCII));
        assertEquals("application/pdf", file.contentType());
    }

    /**
     * 验证字体配置错误应阻断 PDF 渲染，避免输出空白文件或错误字形。
     */
    @Test
    void pdfWithoutAnyFontFailsWithClearMessage() {
        stubHappyPath();
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service("/nonexistent/font.ttf").export(37, "pdf", null));
        assertTrue(e.getMessage().contains("字体"));
    }

    // ---------- 客户端图片校验（不可信输入） ----------

    /**
     * 验证客户端提交图片只允许合法 PNG dataUri，非法 base64、非 PNG 和越权 chartId 均 reject。
     */
    @Test
    void invalidImagesAreRejected() {
        stubHappyPath();
        // 非法 base64
        assertThrows(IllegalArgumentException.class, () -> service("").export(37, "docx",
                List.of(new ReportExportService.ChartImage("trend", "!!!not-base64!!!"))));
        // 合法 base64 但不是 PNG
        String notPng = Base64.getEncoder().encodeToString("hello".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> service("").export(37, "docx",
                List.of(new ReportExportService.ChartImage("trend", notPng))));
        // chartId 不在本 run 图表清单内
        String png = Base64.getEncoder().encodeToString(tinyPng());
        assertThrows(IllegalArgumentException.class, () -> service("").export(37, "docx",
                List.of(new ReportExportService.ChartImage("evil-chart", png))));
    }

    // ---------- 失败关闭 ----------

    /**
     * 验证绑定事实缺失场景直接 fail-closed，防止生成含 [fact_xxx] 未解析空洞的文档。
     */
    @Test
    void missingBoundFactFailsClosed() {
        when(runRepo.findById(anyLong())).thenReturn(Optional.of(run("PUBLISHED", CHARTS)));
        when(factRepo.findByRun(anyLong())).thenReturn(List.of(fact("fact_002", "385.14 万元", "2026-W26")));
        assertThrows(IllegalStateException.class, () -> service("").export(37, "docx", null));
    }

    // ---------- 工具 ----------

    private static byte[] tinyPng() {
        try {
            BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String zipEntry(byte[] zip, String name) throws Exception {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                if (name.equals(e.getName())) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }

    private static boolean zipHasEntryPrefix(byte[] zip, String prefix) throws Exception {
        List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) names.add(e.getName());
        }
        return names.stream().anyMatch(n -> n.startsWith(prefix));
    }
}
