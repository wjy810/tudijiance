package cn.tudijiance.common;

import java.time.Duration;
import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 应用配置，对应 application.yml 里的 app.* 配置项。
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String zone,
        Jwt jwt,
        Admin admin,
        Device device,
        Mqtt mqtt,
        Simulator simulator) {

    public ZoneId zoneId() {
        return ZoneId.of(zone);
    }

    /**
     * 登录令牌。secret 为空时启动时随机生成，重启后已登录的用户要重新登录。
     *
     * @param ttl       电脑端令牌的有效期
     * @param mobileTtl 手机端令牌的有效期：田里可能几天没信号，要能一直离线录、回来再上传，所以长一些；不配置时和 ttl 一样
     */
    public record Jwt(String secret, Duration ttl, Duration mobileTtl) {

        public Duration ttlFor(boolean mobile) {
            return mobile && mobileTtl != null ? mobileTtl : ttl;
        }
    }

    /** 首次启动时创建的管理员账号 admin 的密码。为空时随机生成并打印到日志。 */
    public record Admin(String initialPassword) {
    }

    /** 设备超过 offlineAfter 没有上报数据，就标记为离线。 */
    public record Device(Duration offlineAfter) {
    }

    /** MQTT 接入（硬件预留）。enabled 为 false 时不连接 MQTT 服务器。 */
    public record Mqtt(boolean enabled, String brokerUrl, String username, String password,
                       String topicFilter, String clientId) {
    }

    /** 传感器模拟器：硬件到货之前，用模拟数据开发和演示。 */
    public record Simulator(boolean enabled, Duration interval, int backfillDays) {
    }
}
