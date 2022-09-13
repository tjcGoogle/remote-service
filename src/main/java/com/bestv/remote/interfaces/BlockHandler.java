package com.bestv.remote.interfaces;

import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.bestv.remote.context.MethodContext;
import com.bestv.remote.context.ParamContext;
import com.bestv.remote.context.ServerContext;

/**
 * 熔断接口 自定义的熔断方法需要实现此接口
 * 子类必须声明为spring bean
 *
 * @author taojiacheng
 */
public interface BlockHandler<T> {

    /**
     * 降级方法
     *
     * @param serverContext  服务信息上下文
     * @param methodContext  远程方法上下文
     * @param paramContext   参数上下文
     * @param blockException 执行时的异常信息
     * @return 执行时的方法返回
     */
    T handlerBlock(ServerContext serverContext, MethodContext methodContext, ParamContext paramContext, BlockException blockException);
}
