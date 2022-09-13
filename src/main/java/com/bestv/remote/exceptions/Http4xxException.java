package com.bestv.remote.exceptions;

import cn.hutool.core.util.StrUtil;

/**
 * http 客户端异常，不走熔断降级
 *
 * @author taojiacheng
 */
public class Http4xxException extends RuntimeException {

    public Http4xxException(Throwable e) {
        super(e.getMessage(), e);
    }

    public Http4xxException(String message) {
        super(message);
    }

    public Http4xxException(String messageTemplate, Object... params) {
        super(StrUtil.format(messageTemplate, params));
    }

    public Http4xxException(String message, Throwable throwable) {
        super(message, throwable);
    }

    public Http4xxException(String message, Throwable throwable, boolean enableSuppression, boolean writableStackTrace) {
        super(message, throwable, enableSuppression, writableStackTrace);
    }
}
