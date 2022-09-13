package com.bestv.remote.context;

import lombok.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 请求参数上下文
 *
 * @author taojiacheng
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class ParamContext {

    /**
     * baseUrl
     */
    private String baseUrl;

    /**
     * 请求头
     */
    private Map<String, Object> headers = new LinkedHashMap<>();

    /**
     * 请求参数 【 封装到url中 】
     */
    private Map<String, Object> urlParams = new LinkedHashMap<>();

    /**
     * 请求路径参数
     */
    private Map<String, Object> pathParams = new LinkedHashMap<>();

    /**
     * 请求体
     */
    private Map<String, Object> requestBody = new LinkedHashMap<>();

    /**
     * 缓存key
     */
    private Map<String, Object> cacheKey = new LinkedHashMap<>();


    /**
     * 封装方法参数, key: 形参名  val: 参数值
     * 通过此对象，可以在 fallback 或者 block是 获取方法所有的参数
     */
    private Map<String, Object> requestParams = new LinkedHashMap<>();

}
