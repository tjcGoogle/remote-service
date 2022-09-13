package com.bestv.remote.annotation;

import org.springframework.stereotype.Component;

import java.lang.annotation.*;

/**
 * 封装远程调用服务
 *
 * @author taojiacheng
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
@Component
public @interface RemoteService {

    /**
     * spring beanName
     * <p>
     * 默认取类名
     */
    String serviceId() default "";


    /**
     * 目标服务名
     * trace_log 使用
     */
    String targetServerName() default "";

    /**
     * 远程服务url地址
     * <p>
     * 如果希望从配置文件读取, 则使用 "${配置文件中的key}"
     * <p>
     * 如果希望在调用时动态指定，则在调用处指定{ @RemoteParam(type = ParamType.BASE_URL) }
     */
    String baseUrl() default "";

    /**
     * 连接超时时间
     * 单位 毫秒
     * 默认 300 ms
     * <p>
     * 如果希望从配置文件读取, 则使用 "${配置文件中的key}"
     */
    String connectTimeout() default "300";

    /**
     * 读取超时时间
     * 单位 毫秒
     * 默认 400 ms
     * <p>
     * 如果希望从配置文件读取, 则使用 "${配置文件中的key}"
     */
    String socketTimeout() default "400";

    /**
     * 最大空闲连接数
     */
    int maxIdleConnections() default 5;


    /**
     * 空闲连接存活时间
     */
    long keepAliveDuration() default 5 * 60 * 1000;
}
