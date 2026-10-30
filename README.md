# tudijiance：小麦品种实验田数字化管理

学校的小麦品种实验田按行×列划分成若干小区，每个小区种一个品种。本项目要做一个电脑桌面应用和安卓 APP 录入、服务器统一存储的系统，按农业行业标准 NY/T 1301—2025 记载小区、品种、物候期、农事、抗逆、病害、考种和产量数据，并对多年数据做图表展示和品种对比。

需求详见 [docs/需求分析.md](docs/需求分析.md)。

## 目录

```
docs/
  需求分析.md                                 需求分析（老师需求 + 确认结果）
  技术选型.md                                 技术选型：客户端、后端、数据模型、离线同步、部署
  硬件设备需求.md                             阶段一硬件：手动录入和自动采集的分工、设备、采购清单
  记载标准摘要_NY-T1301-2025.md               小麦品种试验记载标准摘要：分级、公式、数据描述
  老师需求聊天记录.webp                        原始需求（微信聊天截图）
  参考论文_农用大棚环境监控设备综合管理系统.docx   参考论文
reference-code/                               参考论文对应的代码，仅供参考
  final-iot-web/                              前端：Vue 3 + Element Plus + ECharts
  iot_final-training/                         后端：Spring Boot 3 + MyBatis-Plus + MySQL + MQTT/Vert.x
```

`reference-code/` 里的 IDE 配置文件（`.idea/`、`*.iml`）按原项目自带的 `.gitignore` 没有上传。
