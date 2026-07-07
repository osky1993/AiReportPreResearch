package com.treasury.nl2sql.report.pipeline;

/**
 * 失败关闭（fail-closed）：业务性停止信号——指标歧义、映射不到、校验失败、
 * 数据质量失败、证据不充分等。编排器捕获后置 BLOCKED 并带 [POLICY] 前缀转人工，
 * 与意外异常（[EXCEPTION]）区分。绝不猜测补全。
 */
public class PolicyException extends RuntimeException {
    public PolicyException(String message) {
        super(message);
    }
}
