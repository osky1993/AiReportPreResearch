package com.treasury.nl2sql.schema;

import com.treasury.nl2sql.schema.SchemaService.ForeignKey;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SchemaService 外键闭包单测（纯逻辑）。
 * 覆盖多跳扩展、反向回溯、0 跳行为与环路去重，保障 schema 上下文边界稳定用于查询路由与安全收敛。
 */
class SchemaServiceTest {

    // 链：a -> b -> c -> d（c->d 之外另有分支 b<->e 仅演示双向）
    private static final List<ForeignKey> FKS = List.of(
            new ForeignKey("a", "b_id", "b", "id"),
            new ForeignKey("b", "c_id", "c", "id"),
            new ForeignKey("c", "d_id", "d", "id"));

    /**
     * 输入：深度 1 的闭包扩展。
     * 预期：只包含起点与一跳邻居，避免过扩导致无关表误入白名单。
     */
    @Test
    void oneHop_includesDirectNeighborOnly() {
        Set<String> r = SchemaService.fkClosure(FKS, List.of("a"), 1);
        assertEquals(Set.of("a", "b"), r);
        assertFalse(r.contains("c"), "一跳不应到 c");
    }

    /**
     * 输入：深度 2。
     * 预期：扩展到第三节点但不越界，验证 hop 语义。
     */
    @Test
    void twoHops_reachThirdTable() {
        Set<String> r = SchemaService.fkClosure(FKS, List.of("a"), 2);
        assertTrue(r.containsAll(Set.of("a", "b", "c")), "两跳应含 a/b/c");
        assertFalse(r.contains("d"), "两跳不应到 d");
    }

    @Test
    void enoughHops_reachWholeChain() {
        Set<String> r = SchemaService.fkClosure(FKS, List.of("a"), 5);
        assertEquals(Set.of("a", "b", "c", "d"), r);
    }

    /**
     * 输入：depth=0，起点为 a；
     * 预期：只返回起点，避免把闭包误用于无跳数场景。
     */
    @Test
    void zeroHops_returnsSeedsOnly() {
        assertEquals(Set.of("a"), SchemaService.fkClosure(FKS, List.of("a"), 0));
    }

    /**
     * 输入：反向链起点 d，深度 3。
     * 预期：回溯沿外键反向抓取，得到完整闭包。
     */
    @Test
    void reverseDirection_traversedToo() {
        // 从 d 反向回溯：d -> c -> b -> a
        Set<String> r = SchemaService.fkClosure(FKS, List.of("d"), 3);
        assertEquals(Set.of("a", "b", "c", "d"), r);
    }

    /**
     * 输入：有环的外键图。
     * 预期：set 去重防止无限循环，返回有限集合。
     */
    @Test
    void cycle_doesNotLoopForever() {
        List<ForeignKey> cyclic = List.of(
                new ForeignKey("a", "b_id", "b", "id"),
                new ForeignKey("b", "a_id", "a", "id")); // a<->b 成环
        Set<String> r = SchemaService.fkClosure(cyclic, List.of("a"), 10);
        assertEquals(Set.of("a", "b"), r);
    }

    /**
     * 输入：时间类型关键字表；
     * 预期：识别常见时间类型并排除非时间列，保障 schema link/validator 对时序类型的边界一致。
     */
    @Test
    void temporalType_classification() {
        assertTrue(SchemaService.isTemporalType("date"));
        assertTrue(SchemaService.isTemporalType("datetime"));
        assertTrue(SchemaService.isTemporalType("timestamp"));
        assertTrue(SchemaService.isTemporalType("time"));
        assertFalse(SchemaService.isTemporalType("varchar"));
        assertFalse(SchemaService.isTemporalType("decimal"));
        assertFalse(SchemaService.isTemporalType(null));
    }
}
