package com.bestv.remote.interfaces.impl.restHandler;

import cn.hutool.core.net.url.UrlBuilder;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.bestv.remote.context.MethodContext;
import com.bestv.remote.context.ParamContext;
import com.bestv.remote.context.ServerContext;
import com.bestv.remote.convert.CustomerMappingJackson2HttpMessageConverter;
import com.bestv.remote.exceptions.Http4xxException;
import com.bestv.remote.interfaces.RestHandler;
import com.bestv.remote.trace.TraceLogContext;
import com.bestv.remote.trace.TraceLogContextHolder;
import com.bestv.trace.bean.TraceLogBean;
import com.bestv.trace.log.TraceLogPrinter;
import lombok.extern.slf4j.Slf4j;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.*;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.OkHttp3ClientHttpRequestFactory;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StopWatch;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * restTemplate 实现远程服务调用
 * <p>
 * 内部依赖okhttp3实现
 *
 * @author taojiacheng
 */
@Slf4j
public class RestTemplateHandler implements RestHandler {

    protected RestTemplate restTemplate;

    protected ServerContext serverContext;

    /**
     * 初始化 restTemplate
     *
     * @param serverContext 远程服务信息
     */
    @Override
    public void init(ServerContext serverContext) {
        log.info("init restTemplate request : {}", serverContext);
        this.serverContext = serverContext;
        ConnectionPool connectionPool = new ConnectionPool(serverContext.getMaxIdleConnections(),
                serverContext.getKeepAliveDuration(), TimeUnit.MILLISECONDS);
        // TODO: 2022/9/13 对 https 的支持
        OkHttpClient okHttpClient = okHttpClient(serverContext, connectionPool);

        ClientHttpRequestFactory okHttp3ClientHttpRequestFactory =
                new OkHttp3ClientHttpRequestFactory(okHttpClient);
        this.restTemplate = new RestTemplate(okHttp3ClientHttpRequestFactory);

        // 添加自定义的消息转换器
        List<HttpMessageConverter<?>> messageConverters = restTemplate.getMessageConverters();
        messageConverters.add(new CustomerMappingJackson2HttpMessageConverter());
    }


    @NotNull
    protected OkHttpClient okHttpClient(ServerContext serverContext, ConnectionPool connectionPool) {
        return new OkHttpClient().newBuilder()
                .connectionPool(connectionPool)
                .connectTimeout(serverContext.getConnectTimeout(), TimeUnit.MILLISECONDS)
                .readTimeout(serverContext.getSocketTimeOut(), TimeUnit.MILLISECONDS)
                .writeTimeout(serverContext.getSocketTimeOut(), TimeUnit.MILLISECONDS)
                .hostnameVerifier((hostname, session) -> true)
                .build();
    }


    @Override
    public Object invokeRest(MethodContext methodContext, ParamContext paramContext) {
        // 构建请求头信息
        HttpHeaders httpHeaders = buildRequestHeaders(paramContext);
        String finalUrl = buildFinalUrl(methodContext, paramContext);
        log.info("do http request, finalUrl : {}", finalUrl);
        // 执行请求
        HttpMethod httpMethod = methodContext.getHttpMethod();
        // 请求链路
        TraceLogBean traceLogBean = generateTraceLogBean(serverContext, methodContext, paramContext);
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        try {
            // 执行远程服务调用
            ResponseEntity<?> responseEntity = doInvokeRest(finalUrl, httpMethod, httpHeaders, methodContext, paramContext);
            log.info("Remote service response results :{}", responseEntity);
            // 填充 trace_log bean
            fillTraceLogBean(traceLogBean, responseEntity);
            HttpStatus statusCode = responseEntity.getStatusCode();
            // 4xx 参数错误或者未通过鉴权异常 不走降级
            if (statusCode.is4xxClientError()) {
                throw new Http4xxException(statusCode.getReasonPhrase());
            }
            return responseEntity.getBody();
        } catch (HttpClientErrorException e) {
            log.error("execute remote service exceptions:「{}」", e.getMessage(), e);
            HttpStatus statusCode = e.getStatusCode();
            if (statusCode.is4xxClientError()) {
                throw new Http4xxException(e.getMessage());
            }
            throw e;
        } finally {
            stopWatch.stop();
            traceLogBean.setCost(stopWatch.getLastTaskTimeMillis() + "");
            TraceLogPrinter.printTraceInfo(traceLogBean);
        }
    }


    /**
     * 填充trace_log bean
     */
    protected void fillTraceLogBean(TraceLogBean traceLogBean, ResponseEntity<?> responseEntity) {
        Object responseBody = responseEntity.getBody();
        // traceLog 日志打印
        traceLogBean.setHttpStatus(responseEntity.getStatusCodeValue() + "");
        try {
            JSONObject entries = JSONUtil.parseObj(responseBody);
            traceLogBean.setTpResponseCode(entries.getStr("code"));
            traceLogBean.setResponse(entries.toJSONString(0));
        } catch (Exception ignored) {
            traceLogBean.setResponse(String.valueOf(responseBody));
            traceLogBean.setTpResponseCode("-1");
        }
    }


    /**
     * 日志追踪Bean
     *
     * @param serverContext 远程服务上下文信息
     * @param methodContext 方法上下文
     * @param paramContext  参数上下文
     * @return traceLogBean
     */
    protected TraceLogBean generateTraceLogBean(ServerContext serverContext,
                                                MethodContext methodContext, ParamContext paramContext) {
        HttpMethod httpMethod = methodContext.getHttpMethod();
        TraceLogBean traceLogBean = new TraceLogBean();
        traceLogBean.setMethod(httpMethod.name());

        TraceLogContext traceLogContext = TraceLogContextHolder.getTraceLogContext();
        traceLogBean.setSn(traceLogContext.getSn());
        traceLogBean.setTargetServerIp(serverContext.getBaseUrl());
        traceLogBean.setTargetApi(URLUtil.getPath(URLUtil.normalize(methodContext.getFinalUrl())));
        traceLogBean.setTargetServerName(serverContext.getTargetServerName());
        traceLogBean.setUserId(traceLogContext.getUserId());
        traceLogBean.setExtra(traceLogContext.getExtra());
        traceLogBean.setParams(paramContext.getRequestParams().toString());
        return traceLogBean;
    }

    /**
     * 组装最终请求的url参数
     *
     * @param methodContext 方法上下文
     * @param paramContext  参数上下文对象
     * @return url
     */
    protected String buildFinalUrl(MethodContext methodContext, ParamContext paramContext) {
        String baseUrl = paramContext.getBaseUrl();
        if (StringUtils.isEmpty(baseUrl)) {
            // 优先取参数中传递的baseUrl
            baseUrl = serverContext.getBaseUrl();
        }
        if (StringUtils.isEmpty(baseUrl)) {
            throw new Http4xxException("baseUrl is empty");
        }
        UrlBuilder builder = UrlBuilder.of(baseUrl);
        String uri = methodContext.getUri();
        uri = StrUtil.format(uri, paramContext.getPathParams());
        builder.addPath(uri);
        paramContext.getUrlParams().forEach(builder::addQuery);
        String finalUrl = builder.build();
        methodContext.setFinalUrl(finalUrl);
        return finalUrl;
    }


    /**
     * 构建请求头
     *
     * @param paramContext 方法上下文
     * @return 请求头
     */
    protected HttpHeaders buildRequestHeaders(ParamContext paramContext) {
        HttpHeaders httpHeaders = new HttpHeaders();
        paramContext.getHeaders().forEach((key, val) -> httpHeaders.set(key, String.valueOf(val)));
        return httpHeaders;
    }


    /**
     * 执行http请求
     *
     * @param methodContext 方法上下文
     * @param paramContext  参数上下文
     * @param httpMethod    请求方法
     * @param headers       请求头
     * @param finalUrl      请求url
     * @return 响应结果
     */
    protected ResponseEntity<?> doInvokeRest(String finalUrl, HttpMethod httpMethod, HttpHeaders headers,
                                             MethodContext methodContext, ParamContext paramContext) {
        MediaType contentType = headers.getContentType();
        HttpEntity<Object> httpEntity;
        if (MediaType.APPLICATION_JSON.includes(contentType)) {
            httpEntity = new HttpEntity<>(paramContext.getRequestBody(), headers);
        } else {
            MultiValueMap<String, String> multiValueMap = convertMultiValueMap(paramContext.getRequestBody());
            httpEntity = new HttpEntity<>(multiValueMap, headers);
        }
        log.info("restTemplate execute remote service calls {} ", httpEntity);
        return restTemplate.exchange(finalUrl, httpMethod, httpEntity, methodContext.getReturnType());
    }


    /**
     * 针对form表单转换multiValueMap
     */
    protected MultiValueMap<String, String> convertMultiValueMap(Map<String, Object> requestBody) {
        MultiValueMap<String, String> multiValueMap = new LinkedMultiValueMap<>();
        requestBody.forEach((key, val) -> {
            if (val instanceof Collection) {
                @SuppressWarnings("unchecked")
                Collection<Object> valCollection = (Collection<Object>) val;
                valCollection.forEach(e -> multiValueMap.add(key, String.valueOf(e)));
            } else {
                multiValueMap.add(key, String.valueOf(val));
            }
        });
        return multiValueMap;
    }

}
