package com.bestv.remote.convert;

import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

import java.util.ArrayList;
import java.util.List;

/**
 * response 转换器自定义
 *
 * @author taojiacheng
 */
public class CustomerMappingJackson2HttpMessageConverter extends MappingJackson2HttpMessageConverter {

    /**
     * 配置自定义的响应消息转换
     */
    public CustomerMappingJackson2HttpMessageConverter() {
        List<MediaType> mediaTypes = new ArrayList<>();
        mediaTypes.add(MediaType.TEXT_PLAIN);
        mediaTypes.add(MediaType.TEXT_HTML);
        mediaTypes.add(MediaType.APPLICATION_OCTET_STREAM);
        super.setSupportedMediaTypes(mediaTypes);
    }
}
