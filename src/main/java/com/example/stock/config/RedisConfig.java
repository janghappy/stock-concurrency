package com.example.stock.config;

import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

@Configuration
public class RedisConfig {

    /**
     * RedisTemplate이 Lettuce로 연결하도록 연결 팩토리를 직접 등록한다.
     *
     * <p>redisson-spring-boot-starter는 Spring Boot의 Redis 자동 설정보다 먼저 RedissonConnectionFactory를 등록하므로,
     * 그대로 두면 RedisTemplate(Lettuce 스핀 락)도 Redisson 클라이언트로 명령을 보낸다.
     * RedisConnectionFactory 빈이 이미 있으면 Redisson의 연결 팩토리는 등록되지 않고,
     * RedissonClient(Redisson 락)는 그대로 Redisson이 만든다.
     */
    @Bean
    public LettuceConnectionFactory redisConnectionFactory(RedisProperties redisProperties) {
        RedisStandaloneConfiguration configuration =
                new RedisStandaloneConfiguration(redisProperties.getHost(), redisProperties.getPort());
        configuration.setDatabase(redisProperties.getDatabase());
        configuration.setPassword(RedisPassword.of(redisProperties.getPassword()));
        return new LettuceConnectionFactory(configuration);
    }

}
