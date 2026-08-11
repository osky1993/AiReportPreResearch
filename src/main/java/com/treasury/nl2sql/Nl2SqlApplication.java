package com.treasury.nl2sql;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 应用启动入口：承载项目运行的统一 Spring Boot 引导点。
 * <p>
 * 该类不承载业务逻辑，仅负责将 JVM 主方法注册到 Spring 上下文，
 * 并作为底座查询引擎与报告流水线两条链路的同一套运行时边界。
 * <p>
 * 启动失败通常来自配置失配（数据库连接、必填 LLM 配置、端口占用或 Bean 循环依赖）；
 * 相关问题应优先从 {@code application*.yml} 与启动日志中定位。
 */
@SpringBootApplication
public class Nl2SqlApplication {
    /**
     * 启动项目上下文与 HTTP 服务。
     *
     * @param args 命令行参数，交由 {@link SpringApplication#run(Class, String...)} 透传。
     */
    public static void main(String[] args) {
        SpringApplication.run(Nl2SqlApplication.class, args);
    }
}
