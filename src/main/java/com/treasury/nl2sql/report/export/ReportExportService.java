package com.treasury.nl2sql.report.export;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.treasury.nl2sql.report.asset.ReportAssetService;
import com.treasury.nl2sql.report.asset.ReportTemplateDef;
import com.treasury.nl2sql.report.domain.ChartRecord;
import com.treasury.nl2sql.report.domain.FactRecord;
import com.treasury.nl2sql.report.domain.ReportRun;
import com.treasury.nl2sql.report.domain.RunStatus;
import com.treasury.nl2sql.report.store.ReportFactRepository;
import com.treasury.nl2sql.report.store.ReportRunRepository;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 已签发报告导出（PDF/Docx，纯程序零 LLM）。路线 A + 数据表兜底 + 路线 C 演进位：
 * <ul>
 *   <li>服务端只信库：正文（report_md）、事实、图表 option 一律取自库中 PUBLISHED 终稿，
 *       客户端只上送图表 PNG 像素（base64/2MB/可解码三重校验，chartId 必须在本 run 图表清单内）；</li>
 *   <li>每张图必附数据表（数值只取 fact 的 display_value，见 {@link ChartTableBuilder}），
 *       某图无图片则降级为纯数据表——将来路线 C（服务端确定性 SVG 渲染器）在本服务内部补图即可，端点契约不变；</li>
 *   <li>失败关闭：非 PUBLISHED、fact 映射缺失、图片非法、PDF 无中文字体——一律抛出 → 400 明确报错。</li>
 * </ul>
 */
@Service
public class ReportExportService {

    /** 客户端上送的单张图（imageBase64 允许带 data:image/png;base64, 前缀）。 */
    public record ChartImage(String chartId, String imageBase64) {}

    public record ExportFile(String filename, String contentType, byte[] bytes) {}

    private record Png(byte[] bytes, int width, int height) {}

    /** 文档线性模型：markdown 块与「章末图表（图可空 + 数据表必有）」的顺序流。 */
    private sealed interface DocItem permits MdItem, ChartItem {}
    private record MdItem(ReportMdRenderer.Block block) implements DocItem {}
    private record ChartItem(ChartRecord chart, Png png, List<ChartTableBuilder.Row> rows) implements DocItem {}

    private static final long IMAGE_MAX_BYTES = 2 * 1024 * 1024;
    private static final List<String> FONT_CANDIDATES = List.of(
            "/System/Library/Fonts/Supplemental/Arial Unicode.ttf",
            "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.otf",
            "/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.otf");
    private static final String DOCX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ReportRunRepository runRepo;
    private final ReportFactRepository factRepo;
    private final ReportAssetService assets;
    private final ObjectMapper mapper;
    private final String fontPath;

    public ReportExportService(ReportRunRepository runRepo, ReportFactRepository factRepo,
                               ReportAssetService assets, ObjectMapper mapper,
                               @Value("${report.export.font-path:}") String fontPath) {
        this.runRepo = runRepo;
        this.factRepo = factRepo;
        this.assets = assets;
        this.mapper = mapper;
        this.fontPath = fontPath == null ? "" : fontPath.trim();
    }

    public ExportFile export(long runId, String format, List<ChartImage> images) {
        ReportRun run = runRepo.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("运行不存在: " + runId));
        if (!RunStatus.PUBLISHED.name().equals(run.status())) {
            throw new IllegalStateException("当前状态 " + run.status() + " 不允许「导出」——仅已签发（PUBLISHED）报告可导出");
        }
        List<ChartRecord> charts = parseCharts(run.chartsJson());
        Map<String, Png> pngs = validateImages(images, charts);
        Map<String, FactRecord> factsByKey = factRepo.findByRun(runId).stream()
                .collect(Collectors.toMap(FactRecord::factKey, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        String templateName = assets.template(run.templateId())
                .map(ReportTemplateDef::name).orElse(run.templateId());
        List<DocItem> items = assemble(run, charts, pngs, factsByKey);
        String title = templateName + "（" + run.periodLabel() + "）";
        String meta = "运行编号 run#" + run.runId()
                + " ｜ 签发人 " + run.publishApprovedBy()
                + " ｜ 签发时间 " + (run.publishApprovedAt() == null ? "-" : TS.format(run.publishApprovedAt()))
                + " ｜ 正文数值均带 [fact_xxx] 证据引用，图表数据表与审计同源";
        String base = templateName + "-" + run.periodLabel();
        return switch (String.valueOf(format).toLowerCase()) {
            case "pdf" -> new ExportFile(base + ".pdf", "application/pdf", renderPdf(title, meta, items));
            case "docx" -> new ExportFile(base + ".docx", DOCX_CONTENT_TYPE, renderDocx(title, meta, items));
            default -> throw new IllegalArgumentException("不支持的导出格式: " + format + "（仅 pdf / docx）");
        };
    }

    // ---------- 装配：正文块序 + 图表按 chapterId 挂到对应章末 ----------

    private List<DocItem> assemble(ReportRun run, List<ChartRecord> charts,
                                   Map<String, Png> pngs, Map<String, FactRecord> factsByKey) {
        Map<String, String> chapterIdByTitle = chapterTitles(run.outlineJson());
        Map<String, List<ChartRecord>> byChapter = new LinkedHashMap<>();
        for (ChartRecord c : charts) byChapter.computeIfAbsent(c.chapterId(), k -> new ArrayList<>()).add(c);

        List<DocItem> items = new ArrayList<>();
        Set<String> placed = new HashSet<>();
        String currentChapter = null;
        for (ReportMdRenderer.Block b : ReportMdRenderer.parse(run.reportMd())) {
            if (b instanceof ReportMdRenderer.Heading h) {
                appendCharts(items, byChapter.get(currentChapter), pngs, factsByKey, placed);
                currentChapter = chapterIdByTitle.get(h.text());
            }
            items.add(new MdItem(b));
        }
        appendCharts(items, byChapter.get(currentChapter), pngs, factsByKey, placed);
        List<ChartRecord> rest = charts.stream().filter(c -> !placed.contains(c.chartId())).toList();
        if (!rest.isEmpty()) {
            items.add(new MdItem(new ReportMdRenderer.Heading("附图")));
            appendCharts(items, rest, pngs, factsByKey, placed);
        }
        return items;
    }

    private static void appendCharts(List<DocItem> items, List<ChartRecord> charts, Map<String, Png> pngs,
                                     Map<String, FactRecord> factsByKey, Set<String> placed) {
        if (charts == null) return;
        for (ChartRecord c : charts) {
            if (!placed.add(c.chartId())) continue;
            items.add(new ChartItem(c, pngs.get(c.chartId()), ChartTableBuilder.build(c, factsByKey)));
        }
    }

    /** outline 快照的章 title → chapterId（终稿 ## 标题与 outline title 严格一致，作图表挂靠键）。 */
    private Map<String, String> chapterTitles(String outlineJson) {
        Map<String, String> byTitle = new LinkedHashMap<>();
        if (outlineJson == null || outlineJson.isBlank()) return byTitle;
        try {
            for (JsonNode ch : mapper.readTree(outlineJson).path("chapters")) {
                byTitle.put(ch.path("title").asText(), ch.path("chapterId").asText());
            }
        } catch (Exception e) {
            throw new IllegalStateException("outline 快照解析失败，导出失败关闭: " + e.getMessage());
        }
        return byTitle;
    }

    private List<ChartRecord> parseCharts(String chartsJson) {
        if (chartsJson == null || chartsJson.isBlank()) return List.of();
        try {
            return mapper.readValue(chartsJson, new TypeReference<List<ChartRecord>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("charts_json 解析失败，导出失败关闭: " + e.getMessage());
        }
    }

    // ---------- 客户端图片校验（不可信输入，失败关闭） ----------

    private static Map<String, Png> validateImages(List<ChartImage> images, List<ChartRecord> charts) {
        if (images == null || images.isEmpty()) return Map.of();
        Set<String> known = charts.stream().map(ChartRecord::chartId).collect(Collectors.toSet());
        Map<String, Png> out = new LinkedHashMap<>();
        for (ChartImage img : images) {
            if (img == null || img.chartId() == null || !known.contains(img.chartId())) {
                throw new IllegalArgumentException("上送图片的 chartId 不在本 run 图表清单内: "
                        + (img == null ? "null" : img.chartId()));
            }
            String b64 = String.valueOf(img.imageBase64());
            if (b64.startsWith("data:")) {
                int comma = b64.indexOf(',');
                if (comma < 0) throw new IllegalArgumentException("图片 data URI 非法: " + img.chartId());
                b64 = b64.substring(comma + 1);
            }
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(b64);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("图片 base64 解码失败: " + img.chartId());
            }
            if (bytes.length > IMAGE_MAX_BYTES) {
                throw new IllegalArgumentException("图片超过 2MB 上限: " + img.chartId());
            }
            boolean pngMagic = bytes.length > 8 && (bytes[0] & 0xFF) == 0x89
                    && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G';
            BufferedImage bi = null;
            if (pngMagic) {
                try {
                    bi = ImageIO.read(new ByteArrayInputStream(bytes));
                } catch (IOException ignored) {
                    // bi 保持 null → 下面统一拒绝
                }
            }
            if (bi == null) throw new IllegalArgumentException("图片不是合法 PNG: " + img.chartId());
            out.put(img.chartId(), new Png(bytes, bi.getWidth(), bi.getHeight()));
        }
        return out;
    }

    // ---------- PDF：结构块 → XHTML → openhtmltopdf ----------

    private byte[] renderPdf(String title, String meta, List<DocItem> items) {
        File font = resolveFont();
        StringBuilder body = new StringBuilder();
        body.append("<h1>").append(esc(title)).append("</h1>");
        body.append("<div class=\"meta\">").append(esc(meta)).append("</div>");
        for (DocItem item : items) {
            if (item instanceof MdItem md) {
                appendBlockHtml(body, md.block());
            } else if (item instanceof ChartItem chart) {
                appendChartHtml(body, chart);
            }
        }
        String html = "<html><head><style>" + PDF_CSS + "</style></head><body>" + body + "</body></html>";
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.useFont(font, "cjk");
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("PDF 生成失败: " + e.getMessage(), e);
        }
    }

    private static void appendBlockHtml(StringBuilder sb, ReportMdRenderer.Block block) {
        if (block instanceof ReportMdRenderer.Heading h) {
            sb.append("<h2>").append(inlineHtml(h.text())).append("</h2>");
        } else if (block instanceof ReportMdRenderer.Para p) {
            sb.append("<p>").append(inlineHtml(p.text())).append("</p>");
        } else if (block instanceof ReportMdRenderer.Bullets b) {
            sb.append("<ul>");
            for (String it : b.items()) sb.append("<li>").append(inlineHtml(it)).append("</li>");
            sb.append("</ul>");
        } else if (block instanceof ReportMdRenderer.Table t) {
            sb.append("<table>");
            for (int i = 0; i < t.rows().size(); i++) {
                String tag = i == 0 ? "th" : "td";
                sb.append("<tr>");
                for (String cell : t.rows().get(i)) {
                    sb.append("<").append(tag).append(">").append(inlineHtml(cell)).append("</").append(tag).append(">");
                }
                sb.append("</tr>");
            }
            sb.append("</table>");
        }
    }

    private static void appendChartHtml(StringBuilder sb, ChartItem item) {
        sb.append("<div class=\"chart\">");
        sb.append("<div class=\"charttitle\">").append(esc(item.chart().title())).append("</div>");
        if (item.png() != null) {
            sb.append("<img src=\"data:image/png;base64,")
              .append(Base64.getEncoder().encodeToString(item.png().bytes())).append("\"/>");
        }
        sb.append("<table class=\"charttbl\"><tr><th>项目</th><th>数值</th><th>事实引用</th></tr>");
        for (ChartTableBuilder.Row row : item.rows()) {
            sb.append("<tr><td>").append(esc(row.label()))
              .append("</td><td>").append(esc(row.displayValue()))
              .append("</td><td class=\"fref\">[").append(esc(row.factKey())).append("]</td></tr>");
        }
        sb.append("</table></div>");
    }

    private static String inlineHtml(String text) {
        StringBuilder sb = new StringBuilder();
        for (ReportMdRenderer.Seg seg : ReportMdRenderer.segments(text)) {
            String s = esc(seg.text());
            if (seg.factRef()) s = "<span class=\"fref\">" + s + "</span>";
            if (seg.bold()) s = "<b>" + s + "</b>";
            sb.append(s);
        }
        return sb.toString();
    }

    private static String esc(String s) {
        return String.valueOf(s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static final String PDF_CSS = """
            @page { size: A4; margin: 18mm 16mm; }
            body { font-family: 'cjk'; font-size: 11px; color: #222; line-height: 1.7; }
            h1 { font-size: 17px; text-align: center; margin: 0 0 4px 0; }
            .meta { font-size: 8px; color: #777; text-align: center; margin-bottom: 14px; }
            h2 { font-size: 13px; margin: 16px 0 6px 0; padding-bottom: 3px; border-bottom: 1px solid #ccc; }
            p { margin: 6px 0; text-align: justify; }
            ul { margin: 6px 0; padding-left: 18px; }
            li { margin: 3px 0; }
            table { border-collapse: collapse; width: 100%; margin: 8px 0; }
            th, td { border: 1px solid #bbb; padding: 4px 8px; font-size: 10px; text-align: left; }
            th { background: #f2f4f7; }
            .fref { font-size: 7px; color: #999; vertical-align: super; }
            .chart { margin: 10px 0; page-break-inside: avoid; }
            .charttitle { font-size: 11px; font-weight: bold; margin-bottom: 4px; }
            .chart img { width: 100%; }
            .charttbl th, .charttbl td { font-size: 9px; padding: 3px 6px; }
            """;

    /** PDF 中文字体：配置优先，否则按候选路径探测；全无 → 明确报错（Docx 不受影响）。 */
    private File resolveFont() {
        if (!fontPath.isEmpty()) {
            File f = new File(fontPath);
            if (f.isFile()) return f;
            throw new IllegalStateException("配置的 PDF 字体文件不存在: " + fontPath);
        }
        for (String candidate : FONT_CANDIDATES) {
            File f = new File(candidate);
            if (f.isFile()) return f;
        }
        throw new IllegalStateException("PDF 导出需要中文字体：请配置 report.export.font-path 指向 .ttf/.otf 文件（Docx 导出不受影响）");
    }

    // ---------- Docx：结构块 → POI XWPF ----------

    private byte[] renderDocx(String title, String meta, List<DocItem> items) {
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph tp = doc.createParagraph();
            tp.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun tr = tp.createRun();
            tr.setText(title);
            tr.setBold(true);
            tr.setFontSize(16);
            XWPFParagraph mp = doc.createParagraph();
            mp.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun mr = mp.createRun();
            mr.setText(meta);
            mr.setFontSize(8);
            mr.setColor("777777");
            for (DocItem item : items) {
                if (item instanceof MdItem md) {
                    appendBlockDocx(doc, md.block());
                } else if (item instanceof ChartItem chart) {
                    appendChartDocx(doc, chart);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Docx 生成失败: " + e.getMessage(), e);
        }
    }

    private static void appendBlockDocx(XWPFDocument doc, ReportMdRenderer.Block block) {
        if (block instanceof ReportMdRenderer.Heading h) {
            XWPFParagraph p = doc.createParagraph();
            p.setSpacingBefore(240);
            XWPFRun r = p.createRun();
            r.setText(h.text());
            r.setBold(true);
            r.setFontSize(13);
        } else if (block instanceof ReportMdRenderer.Para para) {
            addSegs(doc.createParagraph(), para.text(), null, 11, false);
        } else if (block instanceof ReportMdRenderer.Bullets b) {
            for (String it : b.items()) addSegs(doc.createParagraph(), it, "• ", 11, false);
        } else if (block instanceof ReportMdRenderer.Table t) {
            docxTable(doc, t.rows(), 10);
        }
    }

    private static void appendChartDocx(XWPFDocument doc, ChartItem item) {
        XWPFParagraph tp = doc.createParagraph();
        tp.setSpacingBefore(160);
        XWPFRun tr = tp.createRun();
        tr.setText(item.chart().title());
        tr.setBold(true);
        tr.setFontSize(11);
        if (item.png() != null) {
            try {
                // 前端 pixelRatio=2 截图 → 版心宽约 430pt，按比例缩放
                double wPt = Math.min(430.0, item.png().width() * 0.375);
                double hPt = wPt * item.png().height() / item.png().width();
                doc.createParagraph().createRun().addPicture(new ByteArrayInputStream(item.png().bytes()),
                        XWPFDocument.PICTURE_TYPE_PNG, item.chart().chartId() + ".png",
                        Units.toEMU(wPt), Units.toEMU(hPt));
            } catch (Exception e) {
                throw new IllegalStateException("图表图片写入 Docx 失败: " + item.chart().chartId(), e);
            }
        }
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("项目", "数值", "事实引用"));
        for (ChartTableBuilder.Row row : item.rows()) {
            rows.add(List.of(row.label(), row.displayValue(), "[" + row.factKey() + "]"));
        }
        docxTable(doc, rows, 9);
    }

    /** Docx 段落行内片段：[fact] 引用弱化为小号灰色上标 run。 */
    private static void addSegs(XWPFParagraph p, String text, String prefix, int fontSize, boolean boldAll) {
        if (prefix != null) {
            XWPFRun pr = p.createRun();
            pr.setText(prefix);
            pr.setFontSize(fontSize);
            pr.setBold(boldAll);
        }
        for (ReportMdRenderer.Seg seg : ReportMdRenderer.segments(text)) {
            XWPFRun r = p.createRun();
            r.setText(seg.text());
            r.setBold(boldAll || seg.bold());
            if (seg.factRef()) {
                r.setFontSize(7);
                r.setColor("999999");
                r.setSubscript(org.apache.poi.xwpf.usermodel.VerticalAlign.SUPERSCRIPT);
            } else {
                r.setFontSize(fontSize);
            }
        }
    }

    private static void docxTable(XWPFDocument doc, List<List<String>> rows, int fontSize) {
        if (rows.isEmpty()) return;
        int cols = rows.get(0).size();
        XWPFTable table = doc.createTable(rows.size(), cols);
        table.setWidth("100%");
        for (int i = 0; i < rows.size(); i++) {
            XWPFTableRow row = table.getRow(i);
            List<String> cells = rows.get(i);
            for (int j = 0; j < cols; j++) {
                XWPFTableCell cell = row.getCell(j);
                addSegs(cell.getParagraphs().get(0), j < cells.size() ? cells.get(j) : "",
                        null, fontSize, i == 0);
            }
        }
        doc.createParagraph();
    }
}
