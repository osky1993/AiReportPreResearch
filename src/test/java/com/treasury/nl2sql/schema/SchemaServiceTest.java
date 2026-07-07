package com.treasury.nl2sql.schema;

import com.treasury.nl2sql.schema.SchemaService.ForeignKey;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** LINK item 3：外键闭包多跳。纯逻辑，无需 DB。 */
class SchemaServiceTest {

    // 链：a -> b -> c -> d（c->d 之外另有分支 b<->e 仅演示双向）
    private static final List<ForeignKey> FKS = List.of(
            new ForeignKey("a", "b_id", "b", "id"),
            new ForeignKey("b", "c_id", "c", "id"),
            new ForeignKey("c", "d_id", "d", "id"));

    @Test
    void oneHop_includesDirectNeighborOnly() {
        Set<String> r = SchemaService.fkClosure(FKS, List.of("a"), 1);
        assertEquals(Set.of("a", "b"), r);
        assertFalse(r.contains("c"), "一跳不应到 c");
    }

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

    @Test
    void zeroHops_returnsSeedsOnly() {
        assertEquals(Set.of("a"), SchemaService.fkClosure(FKS, List.of("a"), 0));
    }

    @Test
    void reverseDirection_traversedToo() {
        // 从 d 反向回溯：d -> c -> b -> a
        Set<String> r = SchemaService.fkClosure(FKS, List.of("d"), 3);
        assertEquals(Set.of("a", "b", "c", "d"), r);
    }

    @Test
    void cycle_doesNotLoopForever() {
        List<ForeignKey> cyclic = List.of(
                new ForeignKey("a", "b_id", "b", "id"),
                new ForeignKey("b", "a_id", "a", "id")); // a<->b 成环
        Set<String> r = SchemaService.fkClosure(cyclic, List.of("a"), 10);
        assertEquals(Set.of("a", "b"), r);
    }

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
