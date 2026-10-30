package cn.tudijiance.common;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 统一用配置的时区（默认 Asia/Shanghai）取当前时间，数据库里存的都是这个时区的本地时间。
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock(AppProperties properties) {
        return Clock.system(properties.zoneId());
    }
}
