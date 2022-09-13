package com.bestv.remote.convert;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.text.SimpleDateFormat;
import java.util.TimeZone;

/**
 * objectMapper 单例工厂
 *
 * @author taojiacheng
 */
public class JsonSerializer {

    private final static ObjectMapper INSTANCE;

    static {
        INSTANCE = new ObjectMapper();
        SimpleModule simpleModule = new SimpleModule();
        // 防止精度丢失 long 序列化为字符串
        simpleModule.addSerializer(Long.class, ToStringSerializer.instance);
        INSTANCE.registerModule(simpleModule);
        // Asia/Shanghai
        INSTANCE.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        INSTANCE.setDateFormat(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"));
        INSTANCE.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        INSTANCE.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        INSTANCE.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        INSTANCE.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
    }

    private JsonSerializer() {
    }

    public static ObjectMapper getInstance() {
        return INSTANCE;
    }
}
