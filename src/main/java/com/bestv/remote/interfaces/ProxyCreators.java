package com.bestv.remote.interfaces;

/**
 * 构造代理对象
 *
 * @author taojiacheng
 */
public interface ProxyCreators {

    /**
     * 创建代理对象
     *
     * @param type 需要创建到代理对象类型
     * @return 代理对象
     */
    Object createProxy(Class<?> type);


}
