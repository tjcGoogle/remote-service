package com.bestv.remote.configuration;

import com.bestv.remote.interfaces.ProxyCreators;
import com.bestv.remote.interfaces.impl.creators.JdkProxyCreators;
import org.springframework.beans.factory.FactoryBean;

/**
 * 远程调用服务factoryBean
 *
 * @author taojiacheng
 */
public class RemoteServiceFactoryBean implements FactoryBean<Object> {

    private final static ProxyCreators PROXY_CREATORS = new JdkProxyCreators();

    private final Class<?> remoteServiceClass;

    public RemoteServiceFactoryBean(Class<?> remoteServiceClass) {
        this.remoteServiceClass = remoteServiceClass;
    }

    @Override
    public Object getObject() {
        return PROXY_CREATORS.createProxy(remoteServiceClass);
    }

    @Override
    public Class<?> getObjectType() {
        return remoteServiceClass;
    }

    @Override
    public boolean isSingleton() {
        return true;
    }
}
