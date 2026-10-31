# tudijiance：小麦品种实验田数字化管理

学校的小麦品种实验田按行×列划分成若干小区，每个小区种一个品种。本项目要做一个数据采集和呈现系统：第一期在手机上手动录入、导入以前的电子表格、用平面图和图表呈现数据；第二期自动采集；第三期品种推荐。记载项目按农业行业标准 NY/T 1301—2025。

需求详见 [docs/需求分析.md](docs/需求分析.md)。

## 当前进度

第一期的后端和电脑端大部分已完成：平面图录入、布置试验、数据查看、统计、自助分析（自己选字段做折线、柱状、饼图等）、数据大屏，
以及历史表格导入（框选区域、按行或按列、对应到录入项目，表头会记成录入项目的别名）。界面按"麦田手记"风格做了改版。

还没做的有：手机 APP（主要录入端）和离线上传、土壤和气象数据录入、功能模块管理、系统维护、部署配置。

逐项对照见[技术选型第 10 节](docs/技术选型.md#10-当前进度)。

## 目录

```
docs/
  需求分析.md                                 需求分析（老师需求 + 确认结果）
  技术选型.md                                 技术选型：客户端、后端、权限、导入、预留接口、当前进度
  硬件设备需求.md                             第一期硬件：手机、电脑、大屏、测量仪器、采购清单
  记载标准摘要_NY-T1301-2025.md               小麦品种试验记载标准摘要：分级、公式、数据描述
  老师需求聊天记录.webp                        原始需求（微信聊天截图）
  参考论文_农用大棚环境监控设备综合管理系统.docx   参考论文
server/                                       后端（Spring Boot 4 + MyBatis-Plus + MySQL）
client/                                       电脑端（Vue 3 + Element Plus + Electron）
scripts/seed-demo.mjs                         本地演示数据（实验田、品种、4 个年度的试验和数据）
reference-code/                               参考论文对应的代码，仅供参考
  final-iot-web/                              前端：Vue 3 + Element Plus + ECharts
  iot_final-training/                         后端：Spring Boot 3 + MyBatis-Plus + MySQL + MQTT/Vert.x
```

`reference-code/` 里的 IDE 配置文件（`.idea/`、`*.iml`）按原项目自带的 `.gitignore` 没有上传。

## 本地运行

后端（需要 JDK 21、Maven）。`local` 配置用内置的 H2 数据库，不用装 MySQL，管理员账号 `admin`，密码 `admin123`：

```bash
cd server && mvn spring-boot:run -Dspring-boot.run.profiles=local
```

电脑端（需要 Node.js 22 以上、pnpm）：

```bash
cd client && pnpm install && pnpm dev:electron
```

只在浏览器里看用 `pnpm dev`，打开 http://localhost:5173 。

想先看看效果，可以在后端启动后建一些演示数据（老师 `laoshi`、学生 `xuesheng`、游客 `youke`，密码都是 `demo1234`）：

```bash
node scripts/seed-demo.mjs
```

打 Windows 安装包（输出在 `client/release/`，Linux、macOS 上也能打，不用装 wine）：

```bash
cd client && pnpm dist:win
```

安装后第一次登录时，在登录页的"服务器设置"里填上服务器地址。

登录后先用管理员新建老师、学生、游客账号：老师建实验田和试验、分配品种；老师和学生在平面图上选中小区录入数据；游客只能看数据大屏。

## 测试

```bash
cd server && mvn test
```

```bash
cd client && pnpm test && pnpm type-check
```
