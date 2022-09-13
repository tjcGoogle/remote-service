package com.bestv.remote.context;

import lombok.*;

/**
 * 远程服务信息上下文
 *
 * @author taojiacheng
 */
@Data
@ToString
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ServerContext {

    /**
     * beanName
     */
    private String serviceName;

    /**
     * 目标服务名 DB、PS、EPG、WAG..... 等
     * 仅在 trace_log 中使用
     */
    private String targetServerName;

    /**
     * url
     */
    private String baseUrl;

    /**
     * 请求超时时间
     */
    private int connectTimeout;

    /**
     * 读取超时配置
     */
    private int socketTimeOut;

    /**
     * 最大空闲连接
     */
    private int maxIdleConnections;

    /**
     * 空闲连接存活时间 (s)
     */
    private long keepAliveDuration;

}
