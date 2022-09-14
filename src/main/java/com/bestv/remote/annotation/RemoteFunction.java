package com.bestv.remote.annotation;


import com.bestv.remote.interfaces.BlockHandler;
import com.bestv.remote.interfaces.FallbackHandler;
import org.springframework.http.HttpMethod;

import java.lang.annotation.*;

/**
 * 标记远程方法
 *
 * @author taojiacheng
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RemoteFunction {

    /**
     * uri,如果希望从配置文件中读取,使用 "${配置文件中的参数key}"
     * 可以通过 {?} 占位,如 /{province}/test,使用 @remoteParam(type=path) 指定占位参数
     */
    String value();


    /**
     * 请求方式
     */
    HttpMethod method();

    /**
     * 降级方法,依托于Spring容器
     * 所以FallbackHandler的实现类必须是Spring Bean
     */
    Class<? extends FallbackHandler> fallback() default None.class;


    /**
     * 熔断方法,依托于Spring容器
     * 所以自定义的BlockHandler的实现类必须是Spring Bean
     * <p>
     * 熔断和降级的处理和区别?
     * 一般而言，降级发生在客户端，在客户端调用远程服务的时候，由于远程服务异常，客户端启用的备选方案
     * 熔断发生在服务端，在一段时间内服务端频繁出现预期之外的结果【sentinel提供的慢调用比例、异常比例、异常数】，服务端启用的保护策略
     * <p>
     * 为什么要降级/熔断?
     * 实际上就是为了快速失败。比如，多服务的场景下，终端请求A服务，A服务依赖于B、C、D服务
     * 当 B 服务【超时、异常】的时候，A服务与B服务的连接不能及时释放
     * 当大量请求进入的时候，A服务连接数积压无法释放，造成A服务异常，最终导致整个链路异常
     * 这种场景下，A服务在感知B服务异常时，及时进行熔断【比如一段时间内不再调用B服务，而是直接启用保护策略】，从而达到保护整个链路
     */
    Class<? extends BlockHandler> block() default None.class;

    /**
     * 指定捕获降级异常
     * <p>
     * 未指定则所有异常都执行降级方法
     */
    Class<? extends Throwable>[] fallbackFor() default {Throwable.class};

    /**
     * sentinel 熔断策略
     */
    String sentinelResource() default "";


    /**
     * 执行次数
     * 发生 retryFor 指定的异常时执行重试
     * 如果没有指定 retryFor,则调用远程服务时发生异常时进行重试
     * 1 就是执行1次
     * 2 就是执行2次
     * ...
     * <p>
     * 注意-该方法为同步重试，如果需要异步重试，可在 remoteService 外层使用 {@link com.bestv.remote.retry.Retry}
     */
    int retryTimes() default 1;


    /**
     * 重试间隔时间
     * 单位 毫秒
     * 默认 0 ms
     */
    int retryInterval() default 0;

    /**
     * 指定捕获异常重试
     * <p>
     * 未指定则为 Throwable
     */
    Class<? extends Throwable>[] retryFor() default {Throwable.class};

    /**
     * 缓存远程服务结果
     * 默认使用redis进行缓存
     * 缓存实现是通过 方法入参来来判断的 开启后会有一定的内存开销
     */
    boolean cacheable() default false;


    /**
     * 缓存时间 (秒)
     * 默认 1 小时
     * <p>
     * 如果想再配置文件中配置，使用 ${配置文件中的key}
     */
    String expireIn() default "3600";


    interface None extends FallbackHandler<Object>, BlockHandler<Object> {

    }
}
