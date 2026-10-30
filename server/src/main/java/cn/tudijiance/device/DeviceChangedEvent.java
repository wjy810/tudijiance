package cn.tudijiance.device;

/** 设备新建或修改后发布，模拟器据此立即给新的模拟设备生成数据。 */
public record DeviceChangedEvent(Long deviceId) {
}
