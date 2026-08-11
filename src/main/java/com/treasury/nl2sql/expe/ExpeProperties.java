package com.treasury.nl2sql.expe;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Prompt 过载与边际效用验证实验（expe/LLM-Prompt过载与边际效用验证实验方案.md）的独立配置。
 * 实验与智能问数/智能报告相互独立，不接入其数据与流程；LLM 供应商三元组（base-url/model/api-key）
 * 复用 llm.* 配置，此处只放实验自身的路径与执行参数。
 *
 * <p>全部字段均有默认值且以可运行值启动，常见误配场景为超量并发导致
 * QPS/配额击穿或磁盘路径不可写，均以显式字段区分：
 * <ul>
 *   <li>路径类参数控制任务落盘位置与是否持久化实验痕迹。</li>
 *   <li>并发类参数控制生成与 judge 的压测强度，避免把底层 LLM 供应商当压测靶机。</li>
 *   <li>judge 与主模型共用 key 仅做降级容错，不建议用于生产评估。</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "expe")
public class ExpeProperties {

    /** 公共冻结数据包路径（相对工作目录），拼接到每份提示词末尾 */
    private String dataJsonPath = "expe/DATA_JSON.json";

    /** 任务与生成结果的落盘根目录（相对工作目录，已 gitignore） */
    private String runsDir = "expe/runs";

    /** 单次生成的 HTTP 超时（长 Prompt + 长文生成，须显著大于底座 llm.timeout-seconds） */
    private int timeoutSeconds = 300;

    /** 任务编排线程数：可同时处于执行态的生成任务数（任务内迭代再经 llm-concurrency 共享池扇出） */
    private int workerThreads = 4;

    /** 生成调用全局并发上限（所有任务共享一个调用池）；调大前确认 LLM 供应商 QPS 配额，限流会污染实验结果 */
    private int llmConcurrency = 8;

    /** judge 调用全局并发上限（所有评估任务共享）；受 DashScope QPM 配额约束 */
    private int judgeConcurrency = 6;

    /** 评估任务落盘根目录（相对工作目录，已 gitignore） */
    private String evalsDir = "expe/evals";

    /** 默认原子规则清单（创建评估时不上传规则文件即用它） */
    private String rulesPath = "expe/RULES_TEMPLATE.json";

    private final Judge judge = new Judge();

    /**
     * AI judge（评审模型）配置。judge 模型必须 ≠ 被测模型（llm.model）——被测模型自评不可信。
     * api-key 默认回落 embedding.api-key（DashScope 通用 key，可同时调 qwen LLM）。
     */
    public static class Judge {
        private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode";
        private String model = "qwen3.7-max";
        private String apiKey = "";
        private int timeoutSeconds = 180;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    }

    /**
     * 获取实验统一数据包路径（JSON）。该文件作为所有提示词共享的外部素材基准，
     * 用于防止同一轮任务中不同生成分流引用不同事实文本导致的对照偏差。
     */
    public String getDataJsonPath() { return dataJsonPath; }

    /**
     * 取得输入冻结数据包路径。常用于每次加载统一 prompt 材料，避免同批任务引用的外部素材漂移。
     */
    public void setDataJsonPath(String dataJsonPath) { this.dataJsonPath = dataJsonPath; }

    /** 返回实验任务工作目录，按运行实例会落盘 input/output 元数据与中间文件。 */
    public String getRunsDir() { return runsDir; }
    public void setRunsDir(String runsDir) { this.runsDir = runsDir; }

    /** 单任务默认超时秒数。超时会让该任务进入失败并触发重试调度（由上层 orchestrator 管控）。 */
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    /** 任务执行线程池规模。值越大吞吐越高，但会竞争 CPU、网络及存储通道。 */
    public int getWorkerThreads() { return workerThreads; }
    public void setWorkerThreads(int workerThreads) { this.workerThreads = workerThreads; }

    /** 生成模型调用并发上限：同一时刻所有任务共享同一调用池，超阈值会引发 backoff。 */
    public int getLlmConcurrency() { return llmConcurrency; }
    public void setLlmConcurrency(int llmConcurrency) { this.llmConcurrency = llmConcurrency; }

    /** 评估模型调用并发上限。judge 主要受 QPM 约束，建议小于或等于 llmConcurrency。 */
    public int getJudgeConcurrency() { return judgeConcurrency; }
    public void setJudgeConcurrency(int judgeConcurrency) { this.judgeConcurrency = judgeConcurrency; }

    /** 评估结果与报告默认落盘目录；失败样本保留可追溯日志。 */
    public String getEvalsDir() { return evalsDir; }
    public void setEvalsDir(String evalsDir) { this.evalsDir = evalsDir; }

    /** 默认规则文件路径。创建任务时未提供规则文件时按此文件加载。 */
    public String getRulesPath() { return rulesPath; }
    public void setRulesPath(String rulesPath) { this.rulesPath = rulesPath; }

    /** 获取 judge 子配置（base-url/model/api-key/timeout）。 */
    public Judge getJudge() { return judge; }
}
