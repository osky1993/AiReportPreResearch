package com.treasury.nl2sql.report.export;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 终稿 markdown → 结构块（导出层专用，纯静态可单测）。
 * 语法与前端 report.html 的 mdToHtml 同构（## 标题 / - 列表 / 段落 / **粗体** / [fact_xxx] 引用），
 * 另支持管道表格（| a | b |，⑤ 会按章节 guidance 产出，前端未处理但导出必须成表）。
 */
public final class ReportMdRenderer {

    private ReportMdRenderer() {}

    public sealed interface Block permits Heading, Bullets, Table, Para {}
    /** 章标题（## 或 #；文本与 outline 章 title 一致，导出时用于图表挂靠）。 */
    public record Heading(String text) implements Block {}
    public record Bullets(List<String> items) implements Block {}
    /** rows[0] 为表头行。 */
    public record Table(List<List<String>> rows) implements Block {}
    public record Para(String text) implements Block {}

    /** 行内片段：factRef=true 的片段是 [fact_xxx] 引用，导出时弱化排版（小号灰色上标）。 */
    public record Seg(String text, boolean bold, boolean factRef) {}

    public static List<Block> parse(String md) {
        List<Block> blocks = new ArrayList<>();
        List<String> bullets = null;
        List<List<String>> table = null;
        for (String raw : String.valueOf(md == null ? "" : md).split("\n", -1)) {
            String t = raw.trim();
            boolean isBullet = t.startsWith("- ");
            boolean isTableRow = t.length() > 1 && t.startsWith("|") && t.endsWith("|");
            if (bullets != null && !isBullet) { blocks.add(new Bullets(bullets)); bullets = null; }
            if (table != null && !isTableRow) { blocks.add(new Table(table)); table = null; }
            if (t.isEmpty()) continue;
            if (t.startsWith("## ")) blocks.add(new Heading(t.substring(3).trim()));
            else if (t.startsWith("# ")) blocks.add(new Heading(t.substring(2).trim()));
            else if (isBullet) {
                if (bullets == null) bullets = new ArrayList<>();
                bullets.add(t.substring(2).trim());
            } else if (isTableRow) {
                List<String> cells = splitCells(t);
                if (isSeparatorRow(cells)) continue;
                if (table == null) table = new ArrayList<>();
                table.add(cells);
            } else blocks.add(new Para(t));
        }
        if (bullets != null) blocks.add(new Bullets(bullets));
        if (table != null) blocks.add(new Table(table));
        return blocks;
    }

    private static List<String> splitCells(String row) {
        String inner = row.substring(1, row.length() - 1);
        return Arrays.stream(inner.split("\\|", -1)).map(String::trim).toList();
    }

    /** |------|:---:| 之类的表头分隔行。 */
    private static boolean isSeparatorRow(List<String> cells) {
        return !cells.isEmpty() && cells.stream().allMatch(c -> c.matches(":?-{2,}:?"));
    }

    private static final Pattern FACT_REF = Pattern.compile("\\[(fact_[A-Za-z0-9_]+)\\]");
    private static final Pattern BOLD = Pattern.compile("\\*\\*([^*]+)\\*\\*");

    /** 与前端 inline() 同构：先切 **粗体** 再切 [fact] 引用。 */
    public static List<Seg> segments(String text) {
        List<Seg> out = new ArrayList<>();
        Matcher b = BOLD.matcher(text);
        int last = 0;
        while (b.find()) {
            factSegs(text.substring(last, b.start()), false, out);
            factSegs(b.group(1), true, out);
            last = b.end();
        }
        factSegs(text.substring(last), false, out);
        return out;
    }

    private static void factSegs(String text, boolean bold, List<Seg> out) {
        Matcher m = FACT_REF.matcher(text);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) out.add(new Seg(text.substring(last, m.start()), bold, false));
            out.add(new Seg("[" + m.group(1) + "]", bold, true));
            last = m.end();
        }
        if (last < text.length()) out.add(new Seg(text.substring(last), bold, false));
    }
}
