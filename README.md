# tudijiance：小麦品种实验田数字化管理

学校的小麦品种实验田按行×列划分成若干小区，每个小区种一个品种。本项目要做一个数据采集和呈现系统：第一期在手机上手动录入、导入以前的电子表格、用平面图和图表呈现数据；第二期自动采集；第三期品种推荐。记载项目按农业行业标准 NY/T 1301—2025。

需求详见 [docs/需求分析.md](docs/需求分析.md)。

## 当前进度

第一期的后端和电脑端大部分已完成：平面图录入、布置试验、数据查看、统计、自助分析（自己选字段做折线、柱状、饼图等）、数据大屏，
以及历史表格导入（框选区域、按行或按列、对应到录入项目，表头会记成录入项目的别名）。界面按"麦田手记"风格做了改版。

平面图按**区块布局**画：实验田 → 区块 → 小区两层网格，单位可以自定义，区块和小区都能合并，特殊区块缩小后四周是道路、可以在原位置里拖动；
有指北针、四边方位，分自由视角和固定视角。《河南省小麦品种试验记载本》的字段已经合并进来：田间记载、考种、产量的表头能自动认出，
另有试验记载本（基本情况、气象资料、品种评价），折合亩产按记载本的收获面积算。详见[地块布局与记载本](docs/地块布局与记载本.md)。

手机端（安卓 APP）有了第一版：触屏选小区录入，没信号时先存在手机上，联网后自动上传。

录入项目可以标"重要"（记载本里要填的默认都标了），录入时排在前面。系统管理员可以在"功能模块"里停用模块、添加外部链接，
停用后菜单不显示、接口也拒绝。"数据质量"页按标准第 9 章检查：重要项目漏录、超出常见范围、和其它小区差得多、日期先后不对、
产量误差变异系数 ≥ 15%、比对照增产 ≥ 30%、缺苗等；录入时超出常见范围会当场提醒，老师核实没错的问题以后不再提醒。
学生不能直接改数据：发现录错了提交"更正申请"（改成什么或删掉、为什么），老师在"更正申请"页对照原值和新值通过或驳回，
通过后才改，修改记录里记下是按谁的申请改的。

还没做的有：土壤和气象数据录入、历史数据批量导入的后续完善、系统维护、部署配置；手机端还要在真机上测试。

逐项对照见[技术选型第 10 节](docs/技术选型.md#10-当前进度)。

## 目录

```
docs/
  需求分析.md                                 需求分析（老师需求 + 确认结果）
  技术选型.md                                 技术选型：客户端、后端、权限、导入、预留接口、当前进度
  地块布局与记载本.md                          区块布局（合并、特殊区块、方位、视角）和河南省记载本字段的对应
  界面设计规范.md                              电脑端界面设计规范
  硬件设备需求.md                             第一期硬件：手机、电脑、大屏、测量仪器、采购清单
  记载标准摘要_NY-T1301-2025.md               小麦品种试验记载标准摘要：分级、公式、数据描述
  老师需求聊天记录.webp                        原始需求（微信聊天截图）
  参考论文_农用大棚环境监控设备综合管理系统.docx   参考论文
server/                                       后端（Spring Boot 4 + MyBatis-Plus + MySQL）
client/                                       电脑端（Vue 3 + Element Plus + Electron）和手机端（Vant + Capacitor，src/mobile、android/）
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

手机端在浏览器里看（手机尺寸），打开 http://localhost:5174 ：

```bash
cd client && pnpm dev:mobile
```

打安卓 APK（需要 JDK 21 和 Android SDK，`client/android/local.properties` 里写 `sdk.dir=Android SDK 的路径`），输出 `client/android/app/build/outputs/apk/debug/app-debug.apk`：

```bash
cd client && pnpm android:apk
```

登录后先用管理员新建老师、学生、游客账号：老师建实验田和试验、分配品种；老师和学生在平面图上选中小区录入数据；游客只能看数据大屏。

### 独立离线安卓版

不需要服务器或账号，在入口点“一键进入”即可使用。首次进入会创建一块示例实验田，并加载项目自带的录入项目；也可以在“我的”中新建本机实验田，填写行列和品种。支持选小区录入、查看本机记录和小区最新值。

```bash
cd client
pnpm dev:offline       # 浏览器预览，http://localhost:5174
pnpm android:offline   # 打包安卓离线版，需要 JDK 21 和 Android SDK
```

离线 APK 输出到 `client/release/tudijiance-offline-0.2.0.apk`，应用名为“小麦实验田离线版”，包名为 `cn.tudijiance.mobile.offline`，可以与联机版同时安装。离线版的数据仅保存在本机，不自动上传、不自动清理，重启和退出后保留；卸载或清除应用数据会删除这些记录。当前离线版提供手机端录入流程，未包含电脑端统计分析和服务器协作功能。

## 测试

```bash
cd server && mvn test
```

```bash
cd client && pnpm test && pnpm type-check
```

## 独立田间种植图与记载本

仓库根目录的 `src/`、`tests/` 和 `package.json` 保留独立的 React 版田间种植图与记载本，数据保存在浏览器本地。它与上面的 `client/`、`server/` 分别运行；使用说明见 [独立种植图工具](docs/field-map-standalone.md)。
它的区块、合并、路面、指北针、视角和记载本字段已经合并进电脑端和后端（见[地块布局与记载本](docs/地块布局与记载本.md)），这里只留作参考。

在仓库根目录运行：

```bash
npm ci
npm run dev -- --port 5175
npm test
npm run build
```

开发地址为 http://localhost:5175 ，与电脑端的 5173、手机端的 5174 区分。
