package com.bestv.remote.enums;

/**
 * 参数类型
 *
 * @author taojiacheng
 */
public enum ParamType {

    /**
     * baseUrl 占位符，可以动态指定 @RemoteService 的baseUrl
     */
    BASE_URL,

    /**
     * path占位符
     * 如 /recommend/{province}/{cpId}
     * 可使用 path 标记参数为路径参数，替换占位符
     * <p>
     * 仅基本数据类型&string
     */
    PATH,

    /**
     * 拼接在url后
     * <p>
     * 例 name=zhangsan&age=18...
     * <p>
     * 仅基本数据类型&string
     */
    URL_PARAM,

    /**
     * 请求体 body json
     * <p>
     * 注： json 和 form 只能同时存在1个
     */
    JSON_BODY,

    /**
     * 请求体 body form
     * <p>
     * 注: json 和 form 只能同时存在1个
     * <p>
     * 注: form参数必须是基本 数据类型 或者 List<基本数据类型>
     */
    FORM,

    /**
     * 请求头参数
     * <p>
     * 仅基本数据类型&string
     */
    HEADER,

    /**
     * 缓存的Key
     * 如果开启缓存，未指定CACHE_KEY，默认会取入参全字段的摘要值作为缓存key
     * 指定 CACHE_KEY 时,取指定CACHE_KEY 的参数值作为缓存key
     */
    CACHE_KEY;
}