package cn.tudijiance.ingest;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import cn.tudijiance.common.AppProperties;
import cn.tudijiance.device.Device;
import cn.tudijiance.device.DeviceService;
import cn.tudijiance.device.SensorReading;

/**
 * MQTT 接入（硬件预留）。配置 app.mqtt.enabled=true 后启用：
 * 连接 MQTT 服务器（Mosquitto），订阅 tudi/devices/+/up，收到的报文交给 {@link SensorIngestService} 入库。
 *
 * 设备的身份由 MQTT 服务器校验（每台 DTU 一个账号，只能发布到自己的主题），
 * 这里信任主题里的设备编号。
 */
@Component
@ConditionalOnProperty(prefix = "app.mqtt", name = "enabled", havingValue = "true")
public class MqttIngestAdapter implements SmartLifecycle, MqttCallback {

    private static final Logger log = LoggerFactory.getLogger(MqttIngestAdapter.class);
    private static final Pattern TOPIC = Pattern.compile("^tudi/devices/([A-Za-z0-9_-]+)/up$");

    private final AppProperties.Mqtt config;
    private final DeviceService deviceService;
    private final PayloadDecoder decoder;
    private final SensorIngestService ingestService;
    private final ScheduledExecutorService retryExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mqtt-connect");
        t.setDaemon(true);
        return t;
    });
    private MqttClient client;
    private volatile boolean running;

    public MqttIngestAdapter(AppProperties properties, DeviceService deviceService,
                             PayloadDecoder decoder, SensorIngestService ingestService) {
        this.config = properties.mqtt();
        this.deviceService = deviceService;
        this.decoder = decoder;
        this.ingestService = ingestService;
    }

    @Override
    public void start() {
        try {
            client = new MqttClient(config.brokerUrl(), config.clientId(), new MemoryPersistence());
            client.setCallback(this);
        } catch (MqttException e) {
            throw new IllegalStateException("MQTT 配置不正确：" + e.getMessage(), e);
        }
        running = true;
        retryExecutor.execute(this::connect);
    }

    /**
     * 首次连接。连上以后由 Paho 自动重连；首次连不上（比如 MQTT 服务器比后端晚启动）就每 30 秒重试一次，
     * 期间系统其他功能照常使用。
     */
    private void connect() {
        if (!running) {
            return;
        }
        MqttConnectionOptions options = new MqttConnectionOptions();
        options.setAutomaticReconnect(true);
        options.setCleanStart(true);
        if (config.username() != null && !config.username().isBlank()) {
            options.setUserName(config.username());
            options.setPassword(config.password() == null ? new byte[0] : config.password().getBytes(StandardCharsets.UTF_8));
        }
        try {
            client.connect(options);
        } catch (MqttException e) {
            log.error("连接 MQTT 服务器 {} 失败：{}，30 秒后重试", config.brokerUrl(), e.getMessage());
            retryExecutor.schedule(this::connect, 30, TimeUnit.SECONDS);
        }
    }

    @Override
    public void stop() {
        running = false;
        retryExecutor.shutdownNow();
        if (client != null) {
            try {
                if (client.isConnected()) {
                    client.disconnect();
                }
                client.close();
            } catch (MqttException e) {
                log.warn("断开 MQTT 连接时出错：{}", e.getMessage());
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        try {
            client.subscribe(config.topicFilter(), 1);
            log.info("已连接 MQTT 服务器 {}，订阅 {}", serverURI, config.topicFilter());
        } catch (MqttException e) {
            log.error("订阅 {} 失败：{}", config.topicFilter(), e.getMessage());
        }
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        Matcher m = TOPIC.matcher(topic);
        if (!m.matches()) {
            log.debug("忽略主题 {}", topic);
            return;
        }
        String code = m.group(1);
        Device device = deviceService.findByCode(code);
        if (device == null) {
            log.warn("收到未登记设备 {} 的数据，已忽略。请先在系统里登记这台设备", code);
            return;
        }
        try {
            List<ReadingRecord> records = decoder.decode(message.getPayload());
            ingestService.ingest(device, records, SensorReading.SOURCE_MQTT);
        } catch (RuntimeException e) {
            // 一条报文出错不能影响后面的报文
            log.warn("设备 {} 的报文处理失败：{}", code, e.getMessage());
        }
    }

    @Override
    public void disconnected(MqttDisconnectResponse response) {
        log.warn("MQTT 连接断开：{}，将自动重连", response.getReasonString());
    }

    @Override
    public void mqttErrorOccurred(MqttException exception) {
        log.error("MQTT 错误：{}", exception.getMessage());
    }

    @Override
    public void deliveryComplete(IMqttToken token) {
        // 只订阅不发布，用不到
    }

    @Override
    public void authPacketArrived(int reasonCode, MqttProperties properties) {
        // 不使用增强认证
    }
}
