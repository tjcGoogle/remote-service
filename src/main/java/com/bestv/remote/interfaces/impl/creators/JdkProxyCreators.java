package com.bestv.remote.interfaces.impl.creators;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.NumberUtil;
import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.Tracer;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.bestv.remote.annotation.RemoteFunction;
import com.bestv.remote.annotation.RemoteParam;
import com.bestv.remote.annotation.RemoteService;
import com.bestv.remote.context.MethodContext;
import com.bestv.remote.context.ParamContext;
import com.bestv.remote.context.ServerContext;
import com.bestv.remote.convert.JsonSerializer;
import com.bestv.remote.exceptions.Http4xxException;
import com.bestv.remote.interfaces.BlockHandler;
import com.bestv.remote.interfaces.FallbackHandler;
import com.bestv.remote.interfaces.ProxyCreators;
import com.bestv.remote.interfaces.RestHandler;
import com.bestv.remote.interfaces.impl.restHandler.RestTemplateHandler;
import com.bestv.remote.retry.AbstractRetry;
import com.bestv.remote.utils.SpringContextHolder;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.util.CollectionUtils;
import org.springframework.validation.annotation.Validated;

import javax.validation.ConstraintViolation;
import javax.validation.Validation;
import javax.validation.ValidationException;
import javax.validation.Validator;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 使用JDK动态代理创建代理对象
 *
 * @author taojiacheng
 */
@Slf4j
public class JdkProxyCreators implements ProxyCreators {

    final static Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    final static String PLACE_HOLDER_REGEX = "\\$\\{([^}]*)\\}";

    final static String PLACE_HOLDER_PREFIX = "${";

    final static Pattern REGEX = Pattern.compile(PLACE_HOLDER_REGEX);

    /**
     * @param type 代理对象类型
     */
    @Override
    public Object createProxy(Class<?> type) {
        log.info("create proxy :{}", type);
        ServerContext serverContext = generateServerContext(type);
        log.info("remote server info:{}", serverContext);

        RestHandler restHandler = new RestTemplateHandler();
        restHandler.init(serverContext);
        return Proxy.newProxyInstance(this.getClass().getClassLoader(), new Class[]{type}, (proxy, method, args) -> {
            // 提取远程接口信息
            MethodContext methodContext = generateMethodContext(method);
            // 提取参数信息
            ParamContext paramContext = generateParamContext(method, args);
            // 熔断处理
            Entry entry = null;
            try {
                if (StringUtils.isNotEmpty(methodContext.getSentinelResource())) {
                    entry = SphU.entry(methodContext.getSentinelResource());
                }
                // 缓存校验
                Object cacheResult = attemptLoadCache(type, method, paramContext.getCacheKey());
                if (cacheResult != null) {
                    return cacheResult;
                }
                // 参数校验
                validParams(type, method, args);
                log.info("remote methodInfo : {}, paramInfo : {}", methodContext, paramContext);
                // 调用rest请求，获取远程服务结果
                Object result = doInvokeRest(methodContext, paramContext, restHandler);
                // 返回结果校验
                validResponse(type, method, result);
                // 生成缓存
                generateServiceCache(type, method, paramContext.getCacheKey(), result, methodContext.getHasFallback());
                return result;
            } catch (BlockException blockException) {
                // 熔断处理
                return invokeBlock(serverContext, methodContext, paramContext, blockException);
            } catch (Throwable bizException) {
                if (bizException instanceof Http4xxException) {
                    // 4xx 异常不参与熔断统计
                    log.info("http4xxException abnormal does not participate in fuse statistics");
                } else {
                    // sentinel 异常信息统计
                    Tracer.trace(bizException);
                }
                // 执行降级
                return invokeFallback(serverContext, methodContext, paramContext, bizException);
            } finally {
                if (entry != null) {
                    entry.exit();
                }
            }
        });
    }


    /**
     * 生成缓存
     *
     * @param type        类
     * @param method      方法
     * @param cacheKeyMap cacheKeyMap
     * @param result      远程服务结果
     */
    protected void generateServiceCache(Class<?> type, Method method, Map<String, Object> cacheKeyMap, Object result, boolean isFallback) {
        RemoteFunction remoteFunction = method.getAnnotation(RemoteFunction.class);
        // fallback 后结果不入缓存
        if (remoteFunction != null && remoteFunction.cacheable() && !isFallback) {
            // 校验缓存是否存在 , 缓存策略：取 类 + 方法 + 参数 的 hash 值 作为缓存key
            String paramsDigest = extractParamsDigest(cacheKeyMap);
            if (StringUtils.isEmpty(paramsDigest)) {
                return;
            }
            String cacheKey = type.getSimpleName() + "$" + method.getName() + "@" + paramsDigest;
            @SuppressWarnings("unchecked")
            RedisTemplate<Object, Object> redisTemplate = SpringContextHolder.getBean(RedisTemplate.class);
            String expireIn = remoteFunction.expireIn().trim();
            if (expireIn.startsWith(PLACE_HOLDER_PREFIX)) {
                String propertiesKey = extractPlaceHolder(expireIn);
                expireIn = StringUtils.trim(SpringContextHolder.getRequiredProperty(propertiesKey));
                if (!NumberUtil.isNumber(expireIn)) {
                    throw new IllegalArgumentException("expireIn must be number");
                }
            }
            int expire = Integer.parseInt(expireIn);
            redisTemplate.opsForValue().set(cacheKey, result, expire, TimeUnit.SECONDS);
        }
    }

    /**
     * 尝试从缓存获取数据
     *
     * @param type        类
     * @param method      方法
     * @param cacheKeyMap 缓存key
     */
    protected Object attemptLoadCache(Class<?> type, Method method, Map<String, Object> cacheKeyMap) {
        RemoteFunction annotation = method.getAnnotation(RemoteFunction.class);
        if (annotation != null && annotation.cacheable()) {
            // 校验缓存是否存在,缓存策略：取 类 + 方法 + 参数 的 摘要 值 作为缓存 key
            String paramsDigest = extractParamsDigest(cacheKeyMap);
            if (StringUtils.isEmpty(paramsDigest)) {
                return null;
            }
            String cacheKey = type.getSimpleName() + "$" + method.getName() + "@" + paramsDigest;
            @SuppressWarnings("unchecked")
            RedisTemplate<Object, Object> redisTemplate = SpringContextHolder.getBean(RedisTemplate.class);
            Object cachedResult = redisTemplate.opsForValue().get(cacheKey);
            if (cachedResult != null) {
                log.info("{} Hit the cache and return the result in the cache ", type.getSimpleName() + "$" + method.getName());
            }
            return cachedResult;
        }
        return null;
    }

    /**
     * 提取参数的摘要值
     *
     * @param cacheKey 缓存key
     * @return 参数摘要
     * @throws JsonProcessingException  序列化错误
     * @throws NoSuchAlgorithmException 可忽略
     */
    protected String extractParamsDigest(Map<String, Object> cacheKey) {
        try {
            String strArgs = JsonSerializer.getInstance().writeValueAsString(cacheKey);
            MessageDigest m = MessageDigest.getInstance("MD5");
            m.reset();
            m.update(strArgs.getBytes(StandardCharsets.UTF_8));
            byte[] digest = m.digest();
            StringBuilder result = new StringBuilder();
            for (byte b : digest) {
                result.append(Integer.toHexString((0x000000FF & b) | 0xFFFFFF00).substring(6));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException ignored) {
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize parameter key ,{}", e.getMessage(), e);
        }
        return "";
    }

    /**
     * 返回结果校验
     *
     * @param result 返回值
     */
    protected void validResponse(Class<?> type, Method method, Object result) {
        Validated validated = Optional.ofNullable(method.getAnnotation(Validated.class)).orElse(type.getAnnotation(Validated.class));
        doValidated(method, validated, result);
    }

    /**
     * 执行远程调用
     *
     * @param methodContext 方法上下文
     * @param paramContext  参数上下文
     * @param restHandler   远程服务调用处理器
     * @return 调用结果
     */
    protected Object doInvokeRest(MethodContext methodContext, ParamContext paramContext, RestHandler restHandler) throws Throwable {
        // 重试执行
        return new AbstractRetry<Object>() {
            @Override
            protected Object retry() {
                return restHandler.invokeRest(methodContext, paramContext);
            }
        }.setRetryInterval(methodContext.getRetryInterval())
                .setRetryFor(methodContext.getRetryFor())
                .setRetryTimes(methodContext.getRetryTimes())
                .execute();
    }

    /**
     * 熔断流程
     *
     * @param serverContext  服务信息上下文
     * @param methodContext  方法上下文
     * @param blockException 异常信息
     * @return 熔断处理
     */
    @SuppressWarnings("rawtypes")
    protected Object invokeBlock(ServerContext serverContext, MethodContext methodContext, ParamContext paramContext, BlockException blockException) throws BlockException {
        // sentinel 熔断
        log.error("Fuse break occurs : {},{}", blockException.getMessage(), serverContext, blockException);
        methodContext.setHasFallback(true);
        Class<? extends BlockHandler> blockHandlerClass = methodContext.getBlockHandler();
        if (blockHandlerClass == RemoteFunction.None.class) {
            // 没有配置熔断处理
            throw blockException;
        }
        BlockHandler blockHandler = SpringContextHolder.getBean(blockHandlerClass);
        try {
            // 执行降级方法
            log.info("Execute the fuse processing method : {}", blockHandlerClass.getSimpleName());
            methodContext.setHasFallback(true);
            return blockHandler.handlerBlock(serverContext, methodContext, paramContext, blockException);
        } catch (Throwable throwable) {
            log.error("Fuse method execution failed : {}", throwable.getMessage(), throwable);
            throw throwable;
        }
    }

    /**
     * 降级流程
     *
     * @param serverContext 服务信息上下文
     * @param methodContext 方法上下文
     * @param e             异常信息
     * @return 降级结果
     */
    @SuppressWarnings("rawtypes")
    protected Object invokeFallback(ServerContext serverContext, MethodContext methodContext, ParamContext paramContext, Throwable e) throws Throwable {
        Class<? extends FallbackHandler> fallbackClass = methodContext.getFallback();
        // 没有指定降级方法时
        if (fallbackClass == RemoteFunction.None.class) {
            // 没有配置降级 不进行降级处理
            log.error("Remote service invocation failed without downgrading {}", e.getMessage(), e);
            throw e;
        }
        // 没有指定降级异常 则捕获所有异常进行降级
        log.info("Perform the downgrade process : {},{},{}", serverContext, methodContext, e.getMessage(), e);
        // 执行降级方法
        FallbackHandler<?> fallbackHandler = SpringContextHolder.getBean(fallbackClass);
        for (Class<? extends Throwable> fallbackException : methodContext.getFallbackFor()) {
            if (!fallbackException.isAssignableFrom(e.getClass())) {
                continue;
            }
            try {
                // 执行降级方法
                log.info("Execute the downgrade method : {}", fallbackClass.getSimpleName());
                methodContext.setHasFallback(true);
                return fallbackHandler.handlerFallback(serverContext, methodContext, paramContext, e);
            } catch (Throwable throwable) {
                log.error("Failed to execute the degraded method : {}", throwable.getMessage(), throwable);
                throw throwable;
            }
        }
        // 没有捕获到降级异常 不进行降级处理
        throw e;
    }

    /**
     * 请求参数上下文
     *
     * @param method method
     * @param args   参数列表
     * @return 参数上下文对象
     */
    protected ParamContext generateParamContext(Method method, Object[] args) {
        String mediaType = MediaType.APPLICATION_FORM_URLENCODED_VALUE;
        ParamContext paramContext = new ParamContext();
        // 获取参数
        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            RemoteParam remoteParam = parameter.getAnnotation(RemoteParam.class);
            if (remoteParam == null) {
                continue;
            }
            String name = StringUtils.isNotEmpty(remoteParam.name()) ? remoteParam.name() : parameter.getName();
            switch (remoteParam.type()) {
                case BASE_URL:
                    paramContext.setBaseUrl((String) args[i]);
                    break;
                case PATH:
                    extractParamsToMap(paramContext.getPathParams(), name, args[i]);
                    break;
                case HEADER:
                    extractParamsToMap(paramContext.getHeaders(), name, args[i]);
                    break;
                case FORM:
                    mediaType = MediaType.APPLICATION_FORM_URLENCODED_VALUE;
                    extractParamsToMap(paramContext.getRequestBody(), name, args[i]);
                    break;
                case JSON_BODY:
                    mediaType = MediaType.APPLICATION_JSON_VALUE;
                    extractParamsToMap(paramContext.getRequestBody(), name, args[i]);
                    break;
                case URL_PARAM:
                    extractParamsToMap(paramContext.getUrlParams(), name, args[i]);
                    break;
                case CACHE_KEY:
                    extractParamsToMap(paramContext.getCacheKey(), name, args[i]);
                    break;
                default:
                    break;
            }
            paramContext.getRequestParams().put(name, args[i]);
        }

        // 后置处理 包括 contentType、cacheKey
        paramContextPostHandler(method, paramContext, mediaType);
        return paramContext;
    }


    private void paramContextPostHandler(Method method, ParamContext paramContext, String mediaType) {
        // contentType 后置处理
        paramContext.getHeaders().putIfAbsent(HttpHeaders.CONTENT_TYPE, mediaType);

        // cacheKey 后置处理
        if (!CollectionUtils.isEmpty(paramContext.getCacheKey())) {
            return;
        }
        RemoteFunction remoteFunction = method.getAnnotation(RemoteFunction.class);
        if (remoteFunction != null && remoteFunction.cacheable()) {
            paramContext.getCacheKey().putAll(paramContext.getRequestParams());
        }
    }


    /**
     * 提取请参数信息
     *
     * @param params 封装参数的map
     * @param name   参数名
     * @param arg    参数
     */
    protected void extractParamsToMap(Map<String, Object> params, String name, Object arg) {
        /**
         * beanToMap,如果是基本数据类型，则返回的map为空，则构建 参数名:参数值 的map
         */
        Map<String, Object> beanToMap = BeanUtil.beanToMap(arg);
        if (CollectionUtils.isEmpty(beanToMap)) {
            beanToMap.put(name, arg);
        }
        params.putAll(beanToMap);
    }

    /**
     * 根据方法和参数获取调用信息
     *
     * @param method 请求方法
     */
    protected MethodContext generateMethodContext(Method method) {
        MethodContext methodContext = new MethodContext();
        // 提取请求方法
        extractRequestMethod(methodContext, method);
        // 提取响应参数
        extractReturnType(methodContext, method);
        // 提取重试相关信息
        extractRetry(methodContext, method);
        // 提取降级相关配置
        extractFallback(methodContext, method);
        // 提取sentinel配置
        extractSentinelConfig(methodContext, method);
        return methodContext;
    }

    /**
     * 提取sentinel 配置信息
     *
     * @param methodContext methodContext
     * @param method        method
     */
    protected void extractSentinelConfig(MethodContext methodContext, Method method) {
        RemoteFunction remoteFunction = method.getAnnotation(RemoteFunction.class);
        if (remoteFunction != null) {
            methodContext.setSentinelResource(remoteFunction.sentinelResource());
            methodContext.setBlockHandler(remoteFunction.block());
        }
    }


    /**
     * 处理响应参数
     * 声明响应类型
     */
    protected void extractReturnType(MethodContext methodContext, Method method) {
        Class<?> returnType = method.getReturnType();
        methodContext.setReturnType(returnType);
    }

    /**
     * 参数校验
     * 解析 @Validated 校验参数
     *
     * @param method 切面方法
     * @param args   方法参数
     */
    protected void validParams(Class<?> type, Method method, Object[] args) {
        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            // 优先级 参数 > 方法 > 类
            Validated validated = Optional.ofNullable(parameter.getAnnotation(Validated.class))
                    .orElse(
                            Optional.ofNullable(method.getAnnotation(Validated.class))
                                    .orElse(type.getAnnotation(Validated.class))
                    );
            doValidated(method, validated, args[i]);
        }
    }


    /**
     * 校验参数
     *
     * @param method    方法
     * @param validated @Validated
     * @param arg       待校验的对象
     */
    protected void doValidated(Method method, Validated validated, Object arg) {
        if (validated == null) {
            return;
        }
        Set<ConstraintViolation<Object>> constraintViolations = VALIDATOR.validate(arg, validated.value());
        Map<String, String> validatedMsg = new HashMap<>(25);
        for (ConstraintViolation<Object> valid : constraintViolations) {
            validatedMsg.put(valid.getPropertyPath().toString(), valid.getMessage());
        }
        if (!CollectionUtils.isEmpty(constraintViolations)) {
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append("Method ").append(method.getDeclaringClass())
                    .append("$")
                    .append(method.getName())
                    .append(" ")
            ;
            validatedMsg.forEach((key, val) ->
                    stringBuilder
                            .append("「")
                            .append(key)
                            .append(" Verification failed because of: ")
                            .append(val)
                            .append("」")
                            .append(" ")
            );
            String message = stringBuilder.toString();
            log.error(message);
            throw new ValidationException(message);
        }
    }


    /**
     * 处理请求方法
     *
     * @param methodContext 方法信息
     * @param method        切面方法信息
     */
    protected void extractRequestMethod(MethodContext methodContext, Method method) {
        RemoteFunction remoteFunction = method.getAnnotation(RemoteFunction.class);
        if (remoteFunction != null) {
            HttpMethod httpMethod = remoteFunction.method();
            methodContext.setHttpMethod(httpMethod);
            extractUri(methodContext, remoteFunction);
        }
    }

    /**
     * 提取uri
     *
     * @param methodContext  方法上下文
     * @param remoteFunction 注解
     */
    protected void extractUri(MethodContext methodContext, RemoteFunction remoteFunction) {
        String uri = remoteFunction.value();
        if (uri.startsWith(PLACE_HOLDER_PREFIX)) {
            String propertiesKey = extractPlaceHolder(uri);
            uri = StringUtils.trim(SpringContextHolder.getRequiredProperty(propertiesKey));
        } else {
            uri = StringUtils.trim(remoteFunction.value());
        }
        methodContext.setUri(uri);
    }


    /**
     * 提取远程服务信息
     *
     * @param type RemoteService注解
     * @return 远程服务信息
     */
    protected ServerContext generateServerContext(Class<?> type) {
        ServerContext serverContext = new ServerContext();
        RemoteService remoteService = type.getAnnotation(RemoteService.class);
        // 提取远程服务url
        extractBaseUrl(serverContext, remoteService);
        // 提取超时配置
        extractConnectionInfo(serverContext, remoteService);
        // 提取服务信息
        extractServerInfo(serverContext, remoteService, type);
        return serverContext;
    }

    /**
     * 提取服务信息
     *
     * @param serverContext serverContext
     * @param remoteService remoteService
     */
    protected void extractServerInfo(ServerContext serverContext, RemoteService remoteService, Class<?> type) {
        // 获取beanName
        String serviceId = remoteService.serviceId();
        if (StringUtils.isEmpty(serviceId)) {
            serviceId = type.getName().substring(type.getName().lastIndexOf(".") + 1);
        }
        serverContext.setServiceName(serviceId);
        serverContext.setTargetServerName(remoteService.targetServerName());
    }

    /**
     * 提取连接信息，封装到 serverContext
     *
     * @param serverContext serverContext
     * @param remoteService @RemoteService
     */
    protected void extractConnectionInfo(ServerContext serverContext, RemoteService remoteService) {
        String connectTimeout = remoteService.connectTimeout().trim();
        if (connectTimeout.startsWith(PLACE_HOLDER_PREFIX)) {
            String propertiesKey = extractPlaceHolder(connectTimeout);
            connectTimeout = StringUtils.trim(SpringContextHolder.getRequiredProperty(propertiesKey));
        }
        if (!NumberUtil.isNumber(connectTimeout)) {
            throw new IllegalArgumentException("connectTimeout must be a number");
        }
        serverContext.setConnectTimeout(Integer.parseInt(connectTimeout));

        String socketTimeout = remoteService.socketTimeout().trim();
        if (socketTimeout.startsWith(PLACE_HOLDER_PREFIX)) {
            String propertiesKey = extractPlaceHolder(socketTimeout);
            socketTimeout = StringUtils.trim(SpringContextHolder.getRequiredProperty(propertiesKey));
        }
        if (!NumberUtil.isNumber(socketTimeout)) {
            throw new IllegalArgumentException("socketTimeout must be a number");
        }
        serverContext.setSocketTimeOut(Integer.parseInt(socketTimeout));
        serverContext.setMaxIdleConnections(remoteService.maxIdleConnections());
        serverContext.setKeepAliveDuration(remoteService.keepAliveDuration());
    }

    /**
     * 提取降级相关配置 封装到 methodContext
     *
     * @param methodContext methodContext
     * @param method        method
     */
    protected void extractFallback(MethodContext methodContext, Method method) {
        RemoteFunction remoteFunction = method.getAnnotation(RemoteFunction.class);
        if (remoteFunction != null) {
            methodContext.setFallback(remoteFunction.fallback());
            methodContext.setFallbackFor(remoteFunction.fallbackFor());
        }
    }

    /**
     * 提取重试配置 封装到 serverContext
     *
     * @param methodContext methodContext
     * @param method        method
     */
    protected void extractRetry(MethodContext methodContext, Method method) {
        RemoteFunction remoteFunction = method.getAnnotation(RemoteFunction.class);
        if (remoteFunction != null) {
            methodContext.setRetryFor(remoteFunction.retryFor());
            methodContext.setRetryTimes(remoteFunction.retryTimes());
            methodContext.setRetryInterval(remoteFunction.retryInterval());
        }
    }

    /**
     * 提取远程服务url 封装到 serverContext
     *
     * @param serverContext serverContext
     * @param remoteService @RemoteService
     */
    protected void extractBaseUrl(ServerContext serverContext, RemoteService remoteService) {
        // 读取url, 优先从配置文件读取
        String baseUrl = remoteService.baseUrl().trim();
        if (baseUrl.startsWith(PLACE_HOLDER_PREFIX)) {
            String propertiesKey = extractPlaceHolder(baseUrl);
            baseUrl = StringUtils.trim(SpringContextHolder.getRequiredProperty(propertiesKey));
        } else {
            baseUrl = StringUtils.trim(remoteService.baseUrl());
        }
        serverContext.setBaseUrl(baseUrl);
    }

    /**
     * 提取占位符内容
     */
    protected String extractPlaceHolder(String targetKey) {
        Matcher matcher = REGEX.matcher(targetKey);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return targetKey;
    }
}
