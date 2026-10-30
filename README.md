# tudijiance：小麦品种实验田数字化管理

学校的小麦品种实验田按行×列划分成若干小区，每个小区种一个品种。本项目要做一个手机端录入、服务器统一存储的小工具，用来管理小区、品种、播种和农事记录，并对多年数据做图表展示和品种对比。

需求详见 [docs/需求分析.md](docs/需求分析.md)。

## 目录

```
docs/
  需求分析.md                                 从老师的语音消息里整理出的需求
  老师需求聊天记录.webp                        原始需求（微信聊天截图）
  参考论文_农用大棚环境监控设备综合管理系统.docx   参考论文
reference-code/                               参考论文对应的代码，仅供参考
  final-iot-web/                              前端：Vue 3 + Element Plus + ECharts
  iot_final-training/                         后端：Spring Boot 3 + MyBatis-Plus + MySQL + MQTT/Vert.x
```

`reference-code/` 里的 IDE 配置文件（`.idea/`、`*.iml`）按原项目自带的 `.gitignore` 没有上传。
