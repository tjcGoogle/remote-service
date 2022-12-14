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
 * ??????JDK??????????????????????????????
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
     * @param type ??????????????????
     */
    @Override
    public Object createProxy(Class<?> type) {
        log.info("create proxy :{}", type);
        ServerContext serverContext = generateServerContext(type);
        log.info("remote server info:{}", serverContext);

        RestHandler restHandler = new RestTemplateHandler();
        restHandler.init(serverContext);
        return Proxy.newProxyInstance(this.getClass().getClassLoader(), new Class[]{type}, (proxy, method, args) -> {
            // ????????????????????????
            MethodContext methodContext = generateMethodContext(method);
            // ??????????????????
            ParamContext paramContext = generateParamContext(method, args);
            // ????????????
            Entry entry = null;
            try {
                if (StringUtils.isNotEmpty(methodContext.getSentinelResource())) {
                    entry = SphU.entry(methodContext.getSentinelResource());
                }
                // ????????????
                Object cacheResult = attemptLoadCache(type, method, paramContext.getCacheKey());
                if (cacheResult != null) {
                    return cacheResult;
                }
                // ????????????
                validParams(type, method, args);
                log.info("remote methodInfo : {}, paramInfo : {}", methodContext, paramContext);
                // ??????rest?????????????????????????????????
                Object result = doInvokeRest(methodContext, paramContext, restHandler);
                // ??????????????????
                validResponse(type, method, result);
                // ????????????
                generateServiceCache(type, method, paramContext.getCacheKey(), result, methodContext.getHasFallback());
                return result;
            } catch (BlockException blockException) {
                // ????????????
                return invokeBlock(serverContext, methodContext, paramContext, blockException);
            } catch (Throwable bizException) {
                if (bizException instanceof Http4xxException) {
                    // 4xx ???????????????????????????
                    log.info("http4xxException abnormal does not participate in fuse statistics");
                } else {
                    // sentinel ??????????????????
                    Tracer.trace(bizException);
                }
                // ????????????
                return invokeFallback(serverContext, methodContext, paramContext, bizException);
            } finally {
                if (entry != null) {
                    entry.exit();
                }
            }
        });
    }


    /**
     * ????????????
     *
     * @param type        ???
     * @param method      ??????
     * @param cacheKeyMap cacheKeyMap
     * @param result      ??????????????????
     */
    protected void generateServiceCache(Class<?> type, Method method, Map<String, Object> cacheKeyMap, Object result, boolean isFallback) {
        RemoteFunction remoteFunction = method.getAnnotation(RemoteFunction.class);
        // fallback ?????????????????????
        if (remoteFunction != null && remoteFunction.cacheable() && !isFallback) {
            // ???????????????????????? , ?????????????????? ??? + ?????? + ?????? ??? hash ??? ????????????key
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
     * ???????????????????????????
     *
     * @param type        ???
     * @param method      ??????
     * @param cacheKeyMap ??????key
     */
    protected Object attemptLoadCache(Class<?> type, Method method, Map<String, Object> cacheKeyMap) {
        RemoteFunction annotation = method.getAnnotation(RemoteFunction.class);
        if (annotation != null && annotation.cacheable()) {
            // ????????????????????????,?????????????????? ??? + ?????? + ?????? ??? ?????? ??? ???????????? key
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
     * ????????????????????????
     *
     * @param cacheKey ??????key
     * @return ????????????
     * @throws JsonProcessingException  ???????????????
     * @throws NoSuchAlgorithmException ?????????
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
     * ??????????????????
     *
     * @param result ?????????
     */
    protected void validResponse(Class<?> type, Method method, Object result) {
        Validated validated = Optional.ofNullable(method.getAnnotation(Validated.class)).orElse(type.getAnnotation(Validated.class));
        doValidated(method, validated, result);
    }

    /**
     * ??????????????????
     *
     * @param methodContext ???????????????
     * @param paramContext  ???????????????
     * @param restHandler   ???????????????????????????
     * @return ????????????
     */
    protected Object doInvokeRest(MethodContext methodContext, ParamContext paramContext, RestHandler restHandler) throws Throwable {
        // ????????????
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
     * ????????????
     *
     * @param serverContext  ?????????????????????
     * @param methodContext  ???????????????
     * @param blockException ????????????
     * @return ????????????
     */
    @SuppressWarnings("rawtypes")
    protected Object invokeBlock(ServerContext serverContext, MethodContext methodContext, ParamContext paramContext, BlockException blockException) throws BlockException {
        // sentinel ??????
        log.error("Fuse break occurs : {},{}", blockException.getMessage(), serverContext, blockException);
        methodContext.setHasFallback(true);
        Class<? extends BlockHandler> blockHandlerClass = methodContext.getBlockHandler();
        if (blockHandlerClass == RemoteFunction.None.class) {
            // ????????????????????????
            throw blockException;
        }
        BlockHandler blockHandler = SpringContextHolder.getBean(blockHandlerClass);
        try {
            // ??????????????????
            log.info("Execute the fuse processing method : {}", blockHandlerClass.getSimpleName());
            methodContext.setHasFallback(true);
            return blockHandler.handlerBlock(serverContext, methodContext, paramContext, blockException);
        } catch (Throwable throwable) {
            log.error("Fuse method execution failed : {}", throwable.getMessage(), throwable);
            throw throwable;
        }
    }

    /**
     * ????????????
     *
     * @param serverContext ?????????????????????
     * @param methodContext ???????????????
     * @param e             ????????????
     * @return ????????????
     */
    @SuppressWarnings("rawtypes")
    protected Object invokeFallback(ServerContext serverContext, MethodContext methodContext, ParamContext paramContext, Throwable e) throws Throwable {
        Class<? extends FallbackHandler> fallbackClass = methodContext.getFallback();
        // ???????????????????????????
        if (fallbackClass == RemoteFunction.None.class) {
            // ?????????????????? ?????????????????????
            log.error("Remote service invocation failed without downgrading {}", e.getMessage(), e);
            throw e;
        }
        // ???????????????????????? ?????????????????????????????????
        log.info("Perform the downgrade process : {},{},{}", serverContext, methodContext, e.getMessage(), e);
        // ??????????????????
        FallbackHandler<?> fallbackHandler = SpringContextHolder.getBean(fallbackClass);
        for (Class<? extends Throwable> fallbackException : methodContext.getFallbackFor()) {
            if (!fallbackException.isAssignableFrom(e.getClass())) {
                continue;
            }
            try {
                // ??????????????????
                log.info("Execute the downgrade method : {}", fallbackClass.getSimpleName());
                methodContext.setHasFallback(true);
                return fallbackHandler.handlerFallback(serverContext, methodContext, paramContext, e);
            } catch (Throwable throwable) {
                log.error("Failed to execute the degraded method : {}", throwable.getMessage(), throwable);
                throw throwable;
            }
        }
        // ??????????????????????????? ?????????????????????
        throw e;
    }

    /**
     * ?????????????????????
     *
     * @param method method
     * @param args   ????????????
     * @return ?????????????????????
     */
    protected ParamContext generateParamContext(Method method, Object[] args) {
        String mediaType = MediaType.APPLICATION_FORM_URLENCODED_VALUE;
        ParamContext paramContext = new ParamContext();
        // ????????????
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

        // ???????????? ?????? contentType???cacheKey
        paramContextPostHandler(method, paramContext, mediaType);
        return paramContext;
    }


    private void paramContextPostHandler(Method method, ParamContext paramContext, String mediaType) {
        // contentType ????????????
        paramContext.getHeaders().putIfAbsent(HttpHeaders.CONTENT_TYPE, mediaType);

        // cacheKey ????????????
        if (!CollectionUtils.isEmpty(paramContext.getCacheKey())) {
            return;
        }
        RemoteFunction remoteFunction = method.getAnnotation(RemoteFunction.class);
        if (remoteFunction != null && remoteFunction.cacheable()) {
            paramContext.getCacheKey().putAll(paramContext.getRequestParams());
        }
    }


    /**
     * ?????????????????????
     *
     * @param params ???????????????map
     * @param name   ?????????
     * @param arg    ??????
     */
    protected void extractParamsToMap(Map<String, Object> params, String name, Object arg) {
        /**
         * beanToMap,??????????????????????????????????????????map?????????????????? ?????????:????????? ???map
         */
        Map<String, Object> beanToMap = BeanUtil.beanToMap(arg);
        if (CollectionUtils.isEmpty(beanToMap)) {
            beanToMap.put(name, arg);
        }
        params.putAll(beanToMap);
    }

    /**
     * ???????????????????????????????????????
     *
     * @param method ????????????
     */
    protected MethodContext generateMethodContext(Method method) {
        MethodContext methodContext = new MethodContext();
        // ??????????????????
        extractRequestMethod(methodContext, method);
        // ??????????????????
        extractReturnType(methodContext, method);
        // ????????????????????????
        extractRetry(methodContext, method);
        // ????????????????????????
        extractFallback(methodContext, method);
        // ??????sentinel??????
        extractSentinelConfig(methodContext, method);
        return methodContext;
    }

    /**
     * ??????sentinel ????????????
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
     * ??????????????????
     * ??????????????????
     */
    protected void extractReturnType(MethodContext methodContext, Method method) {
        Class<?> returnType = method.getReturnType();
        methodContext.setReturnType(returnType);
    }

    /**
     * ????????????
     * ?????? @Validated ????????????
     *
     * @param method ????????????
     * @param args   ????????????
     */
    protected void validParams(Class<?> type, Method method, Object[] args) {
        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            // ????????? ?????? > ?????? > ???
            Validated validated = Optional.ofNullable(parameter.getAnnotation(Validated.class))
                    .orElse(
                            Optional.ofNullable(method.getAnnotation(Validated.class))
                                    .orElse(type.getAnnotation(Validated.class))
                    );
            doValidated(method, validated, args[i]);
        }
    }


    /**
     * ????????????
     *
     * @param method    ??????
     * @param validated @Validated
     * @param arg       ??????????????????
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
                            .append("???")
                            .append(key)
                            .append(" Verification failed because of: ")
                            .append(val)
                            .append("???")
                            .append(" ")
            );
            String message = stringBuilder.toString();
            log.error(message);
            throw new ValidationException(message);
        }
    }


    /**
     * ??????????????????
     *
     * @param methodContext ????????????
     * @param method        ??????????????????
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
     * ??????uri
     *
     * @param methodContext  ???????????????
     * @param remoteFunction ??????
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
     * ????????????????????????
     *
     * @param type RemoteService??????
     * @return ??????????????????
     */
    protected ServerContext generateServerContext(Class<?> type) {
        ServerContext serverContext = new ServerContext();
        RemoteService remoteService = type.getAnnotation(RemoteService.class);
        // ??????????????????url
        extractBaseUrl(serverContext, remoteService);
        // ??????????????????
        extractConnectionInfo(serverContext, remoteService);
        // ??????????????????
        extractServerInfo(serverContext, remoteService, type);
        return serverContext;
    }

    /**
     * ??????????????????
     *
     * @param serverContext serverContext
     * @param remoteService remoteService
     */
    protected void extractServerInfo(ServerContext serverContext, RemoteService remoteService, Class<?> type) {
        // ??????beanName
        String serviceId = remoteService.serviceId();
        if (StringUtils.isEmpty(serviceId)) {
            serviceId = type.getName().substring(type.getName().lastIndexOf(".") + 1);
        }
        serverContext.setServiceName(serviceId);
        serverContext.setTargetServerName(remoteService.targetServerName());
    }

    /**
     * ?????????????????????????????? serverContext
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
     * ???????????????????????? ????????? methodContext
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
     * ?????????????????? ????????? serverContext
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
     * ??????????????????url ????????? serverContext
     *
     * @param serverContext serverContext
     * @param remoteService @RemoteService
     */
    protected void extractBaseUrl(ServerContext serverContext, RemoteService remoteService) {
        // ??????url, ???????????????????????????
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
     * ?????????????????????
     */
    protected String extractPlaceHolder(String targetKey) {
        Matcher matcher = REGEX.matcher(targetKey);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return targetKey;
    }
}
