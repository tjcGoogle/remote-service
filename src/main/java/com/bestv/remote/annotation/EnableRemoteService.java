package com.bestv.remote.annotation;

import com.bestv.remote.configuration.RemoteServiceRegistrar;
import com.bestv.remote.utils.SpringContextHolder;
import org.springframework.context.annotation.Import;

import java.lang.annotation.*;

/**
 * 远程服务开关
 *
 * @author taojiacheng
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Documented
@Import({RemoteServiceRegistrar.class, SpringContextHolder.class})
public @interface EnableRemoteService {

    /**
     * 扫描路径
     */
    String[] basePackages() default {};

}
