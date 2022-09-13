package com.bestv.remote.configuration;

import com.bestv.remote.configuration.condition.RedisCondition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * @author taojiacheng
 */
@Configuration
public class RedisConfiguration {

    @Conditional(RedisCondition.class)
    @Bean
    public RedisTemplate<String, Object> redisTemplate() {

        return new RedisTemplate<>();
    }
}
