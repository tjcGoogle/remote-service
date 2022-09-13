package com.bestv.remote.context;

import com.bestv.remote.interfaces.BlockHandler;
import com.bestv.remote.interfaces.FallbackHandler;
import lombok.*;
import org.springframework.http.HttpMethod;

/**
 * 请求方法上下文对象
 *
 * @author taojiacheng
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class MethodContext {

    /**
     * 请求url
     */
    private String uri;

    /**
     * 请求方法
     */
    private HttpMethod httpMethod;

    /**
     * 返回对象类型
     */
    private Class<?> returnType;


    /**
     * 重试次数
     */
    private int retryTimes;

    /**
     * 重试时间间隔 (s)
     */
    private int[] retryIntervals;

    /**
     * 重试异常
     * 默认为Throwable
     */
    private Class<? extends Throwable>[] retryFor;

    /**
     * 重试间隔时间
     */
    private int retryInterval;


    /**
     * 降级方法
     */
    @SuppressWarnings("rawtypes")
    private Class<? extends FallbackHandler> fallback;

    /**
     * 降级异常
     */
    private Class<? extends Throwable>[] fallbackFor;


    /**
     * 是否进行了降级
     */
    private Boolean hasFallback = false;


    /**
     * sentinel resource
     */
    private String sentinelResource;


    /**
     * 熔断处理
     */
    @SuppressWarnings("rawtypes")
    private Class<? extends BlockHandler> blockHandler;


    /**
     * finalUrl
     */
    private String finalUrl;
}
