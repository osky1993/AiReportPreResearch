package com.treasury.nl2sql.report.export;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 终稿 markdown 结构解析单测：标题/列表/管道表格/段落 + 行内粗体与 [fact] 引用切分。 */
class ReportMdRendererTest {

    @Test
    void parsesHeadingBulletsTableAndPara() {
        String md = """
                ## 一、核心结论

                本周交易总额385.14 万元[fact_002]，**明显上升**。

                - 活跃账户5 户[fact_005]
                - 冻结账户1 户[fact_006]

                | 币种 | 金额 |
                |------|------|
                | CNY | 385.00 万元[fact_017_cny] |

                合计一句话。
                """;
        List<ReportMdRenderer.Block> blocks = ReportMdRenderer.parse(md);
        assertEquals(5, blocks.size());
        assertEquals("一、核心结论", ((ReportMdRenderer.Heading) blocks.get(0)).text());
        assertInstanceOf(ReportMdRenderer.Para.class, blocks.get(1));
        ReportMdRenderer.Bullets bullets = (ReportMdRenderer.Bullets) blocks.get(2);
        assertEquals(2, bullets.items().size());
        ReportMdRenderer.Table table = (ReportMdRenderer.Table) blocks.get(3);
        assertEquals(2, table.rows().size(), "分隔行 |---| 不进表体");
        assertEquals(List.of("币种", "金额"), table.rows().get(0));
        assertEquals("385.00 万元[fact_017_cny]", table.rows().get(1).get(1));
        assertInstanceOf(ReportMdRenderer.Para.class, blocks.get(4));
    }

    @Test
    void singleHashHeadingAndNullMdAreHandled() {
        assertEquals("标题", ((ReportMdRenderer.Heading) ReportMdRenderer.parse("# 标题").get(0)).text());
        assertTrue(ReportMdRenderer.parse(null).isEmpty());
    }

    @Test
    void segmentsSplitBoldAndFactRefs() {
        List<ReportMdRenderer.Seg> segs =
                ReportMdRenderer.segments("总额385.14 万元[fact_002]，**环比+142.2%[fact_002_wow]**收尾");
        assertEquals(List.of("总额385.14 万元", "[fact_002]", "，", "环比+142.2%", "[fact_002_wow]", "收尾"),
                segs.stream().map(ReportMdRenderer.Seg::text).toList());
        assertFalse(segs.get(0).factRef());
        assertTrue(segs.get(1).factRef());
        assertTrue(segs.get(3).bold());
        assertTrue(segs.get(4).bold());
        assertTrue(segs.get(4).factRef());
        assertFalse(segs.get(5).bold());
    }

    @Test
    void plainTextIsSingleSegment() {
        List<ReportMdRenderer.Seg> segs = ReportMdRenderer.segments("本期各项监控指标未见显著异动");
        assertEquals(1, segs.size());
        assertFalse(segs.get(0).factRef());
        assertFalse(segs.get(0).bold());
    }
}
