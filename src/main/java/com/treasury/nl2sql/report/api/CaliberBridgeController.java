package com.treasury.nl2sql.report.api;

import com.treasury.nl2sql.store.CaliberAsset;
import com.treasury.nl2sql.store.CaliberRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 口径资产 → 指标向导的升格桥（P2-T8，说明书 12.2「口径资产升格」入口落地）。
 * 端点放在报告层（而非底座 QueryController）：底座代码/端点零改动是硬约束，
 * 报告层复用底座 CaliberRepository 只读列出 ACTIVE 口径，供首页「升格为指标 →」
 * 跳转 metric-wizard.html?mql=<base64> 直入向导第 ③ 步（参数化）。
 */
@RestController
@RequestMapping("/api/report")
public class CaliberBridgeController {

    private final CaliberRepository calibers;

    public CaliberBridgeController(CaliberRepository calibers) {
        this.calibers = calibers;
    }

    /** 全部 ACTIVE 口径资产（人工核验过的「问法 → 已校验 MQL」，按沉淀时间倒序）。 */
    @GetMapping("/calibers")
    public List<CaliberAsset> listActive() {
        return calibers.findAllActive().stream()
                .sorted((a, b) -> Long.compare(b.id(), a.id()))
                .toList();
    }
}
