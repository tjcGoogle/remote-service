package com.bestv.remote.interfaces;


import com.bestv.remote.context.MethodContext;
import com.bestv.remote.context.ParamContext;
import com.bestv.remote.context.ServerContext;

/**
 * 降级接口 自定义的降级方法需要实现此接口
 * 子类必须声明为spring bean
 *
 * @author taojiacheng
 */
public interface FallbackHandler<T> {

    /**
     * 降级方法
     *
     * @param serverContext 服务信息上下文
     * @param methodContext 远程方法上下文 如果想获取请求参
     * @param paramContext  参数上下文对象
     * @param throwable     执行时的异常信息
     * @return 执行时的方法返回
     */
    T handlerFallback(ServerContext serverContext, MethodContext methodContext, ParamContext paramContext, Throwable throwable);
}
