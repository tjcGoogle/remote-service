package com.bestv.remote.interfaces;

import com.bestv.remote.context.MethodContext;
import com.bestv.remote.context.ParamContext;
import com.bestv.remote.context.ServerContext;

/**
 * @author taojiacheng
 */
public interface RestHandler {
    /**
     * 初始化
     *
     * @param serverContext 远程服务信息
     */
    void init(ServerContext serverContext);

    /**
     * 调用远程服务，获取远程服务结果
     *
     * @param methodContext 请求信息
     * @param paramContext  参数信息
     * @return 远程服务返回结果
     */
    Object invokeRest(MethodContext methodContext, ParamContext paramContext);
}
