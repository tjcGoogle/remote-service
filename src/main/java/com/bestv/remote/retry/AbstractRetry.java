package com.bestv.remote.retry;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

/**
 * 同步重试
 *
 * @author taojiacheng
 */
@Slf4j
public abstract class AbstractRetry<T> {


    /**
     * 执行次数
     */
    protected int retryTimes;

    /**
     * 重试间隔时间
     */
    protected int retryInterval;

    /**
     * 重试异常
     */
    protected Class<? extends Throwable>[] retryFor;

    public AbstractRetry<T> setRetryInterval(int retryInterval) {
        if (retryInterval < 0) {
            throw new IllegalArgumentException("The retry interval cannot be less than 0 milliseconds");
        }
        this.retryInterval = retryInterval;
        return this;
    }

    public AbstractRetry<T> setRetryTimes(int retryTimes) {
        if (retryTimes < 1) {
            throw new IllegalArgumentException("The number of executions cannot be less than 1");
        }
        this.retryTimes = retryTimes;
        return this;
    }

    public AbstractRetry<T> setRetryFor(Class<? extends Throwable>[] retryFor) {
        this.retryFor = retryFor;
        return this;
    }


    /**
     * 重试执行的方法
     *
     * @return 方法返回结果
     * @throws Throwable 捕获的异常
     */
    protected abstract T retry() throws Throwable;

    /**
     * 同步执行重试
     *
     * @return 方法返回
     * @throws Throwable 业务异常
     */
    public T execute() throws Throwable {
        Throwable finalThrowable = null;
        assert retryTimes >= 1;
        for (int i = 0; i < retryTimes; i++) {
            try {
                // 重试成功就结束返回
                return retry();
            } catch (Throwable e) {
                boolean isCatch = false;
                for (Class<? extends Throwable> throwable : retryFor) {
                    if (throwable.isAssignableFrom(e.getClass()) && retryTimes > 1) {
                        isCatch = true;
                        log.info("An exception occurred , perform a retry operation, current number of execution:{}, abnormal information:{}", i + 1, e.getMessage(), e);
                        TimeUnit.MILLISECONDS.sleep(retryInterval);
                        break;
                    }
                }
                // 没有抓住直接抛出异常 不再进行重试
                if (!isCatch) {
                    throw e;
                }
                finalThrowable = e;
            }
        }
        /**
         * 重试次数结束 仍然没有正确返回
         */
        throw finalThrowable;
    }
}