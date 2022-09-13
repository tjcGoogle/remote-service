package com.bestv.remote.annotation;


import com.bestv.remote.enums.ParamType;

import java.lang.annotation.*;

/**
 * 标记远程参数
 *
 * @author taojiacheng
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RemoteParam {

    /**
     * 参数名 不指定取形参名
     */
    String value() default "";

    /**
     * 参数类型
     */
    ParamType type();
}
