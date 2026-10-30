package cn.tudijiance.ingest;

import java.util.List;

/**
 * 把设备发来的原始报文解析成读数。
 *
 * 硬件预留：默认实现是 {@link JsonPayloadDecoder}，对应 docs/硬件接口约定.md 里的 JSON 格式。
 * 如果选定的 DTU 只能发 Modbus 原始报文（十六进制），新增一个实现类，按传感器的寄存器表解析即可，
 * 上报接口、存储和图表都不用改。
 */
public interface PayloadDecoder {

    List<ReadingRecord> decode(byte[] payload);
}
