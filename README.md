# tudijiance：小麦品种实验田数字化管理

学校的小麦品种实验田按行×列划分成若干小区，每个小区种一个品种。本项目要做一个数据采集和呈现系统：第一期在手机上手动录入、导入以前的电子表格、用平面图和图表呈现数据；第二期自动采集；第三期品种推荐。记载项目按农业行业标准 NY/T 1301—2025。

需求详见 [docs/需求分析.md](docs/需求分析.md)。

## 目录

```
docs/
  需求分析.md                                 需求分析（老师需求 + 确认结果）
  技术选型.md                                 技术选型：客户端、后端、权限、导入、预留接口、当前进度
  硬件设备需求.md                             第一期硬件：手机、电脑、大屏、测量仪器、采购清单
  记载标准摘要_NY-T1301-2025.md               小麦品种试验记载标准摘要：分级、公式、数据描述
  老师需求聊天记录.webp                        原始需求（微信聊天截图）
  参考论文_农用大棚环境监控设备综合管理系统.docx   参考论文
server/                                       后端（Spring Boot），开发中
client/                                       前端（Vue 3 + Electron），开发中
reference-code/                               参考论文对应的代码，仅供参考
  final-iot-web/                              前端：Vue 3 + Element Plus + ECharts
  iot_final-training/                         后端：Spring Boot 3 + MyBatis-Plus + MySQL + MQTT/Vert.x
```

`reference-code/` 里的 IDE 配置文件（`.idea/`、`*.iml`）按原项目自带的 `.gitignore` 没有上传。
