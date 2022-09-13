package com.bestv.remote.interfaces.impl.restHandler;

import com.bestv.remote.context.MethodContext;
import com.bestv.remote.context.ParamContext;
import com.bestv.remote.context.ServerContext;
import com.bestv.remote.interfaces.RestHandler;

/**
 * spring5 webClient 调用远程服务
 * todo 异步非阻塞
 *
 * @author taojiacheng
 */
public class WebClientRestHandler implements RestHandler {

    @Override
    public void init(ServerContext serverContext) {

    }

    @Override
    public Object invokeRest(MethodContext methodContext, ParamContext paramContext) {

        return null;
    }
}
