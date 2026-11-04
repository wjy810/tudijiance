import type { FieldDef, SchemaLevel } from './types';

/** 字段定义均来自《河南省小麦品种试验记载本》(2024-2025) */

const GRADE5 = ['1', '2', '3', '4', '5'];
const FROST_HINT = '1级无冻害；2级叶尖受冻发黄；3级叶片冻死一半；4级叶片全枯；5级植株冻死';
const LODGE_HINT = '1级不倒伏；2级倾斜≤30°；3级30°~45°；4级45°~60°；5级>60°';
const IT_OPTIONS = ['0', '0;', '1', '2', '3', '4'];

/** 一、封面 / 基本情况 / 栽培管理 */
export const META_FIELDS: FieldDef[] = [
  { key: 'year', label: '年度', type: 'text', group: '封面', system: true },
  { key: 'trialType', label: '试验区别', type: 'text', group: '封面' },
  { key: 'trialGroup', label: '试验组别', type: 'text', group: '封面' },
  { key: 'unitName', label: '承试单位', type: 'text', group: '封面' },
  { key: 'location', label: '试验地点', type: 'text', group: '封面' },
  { key: 'altitude', label: '海拔', type: 'number', group: '封面', unit: 'm' },
  { key: 'lat', label: '北纬', type: 'text', group: '封面' },
  { key: 'lng', label: '东经', type: 'text', group: '封面' },
  { key: 'leader', label: '负责人', type: 'text', group: '封面' },
  { key: 'recorder', label: '记载人', type: 'text', group: '封面' },

  { key: 'commonCK', label: '共同对照品种', type: 'variety', group: '一、试验设计', system: true },
  { key: 'auxCK', label: '辅助对照品种名称', type: 'text', group: '一、试验设计' },
  { key: 'reps', label: '重复次数', type: 'number', group: '一、试验设计', system: true },
  { key: 'plotLength', label: '小区长度', type: 'number', group: '一、试验设计', unit: '米', system: true },
  { key: 'plotWidth', label: '小区宽度', type: 'number', group: '一、试验设计', unit: '米', system: true },
  { key: 'rowsPerPlot', label: '每小区行数', type: 'number', group: '一、试验设计', unit: '行', system: true },
  { key: 'rowSpacing', label: '行距', type: 'number', group: '一、试验设计', unit: '厘米', system: true },
  { key: 'fieldArea', label: '试验田面积', type: 'number', group: '一、试验设计', unit: '亩' },
  { key: 'density', label: '密度', type: 'number', group: '一、试验设计', unit: '万苗/亩' },

  { key: 'harvestLength', label: '收获长度', type: 'number', group: '产量计算参数', unit: 'm', system: true, hint: '空则取小区长度' },
  { key: 'ridgeWidth', label: '畦埂宽', type: 'number', group: '产量计算参数', unit: 'cm', system: true },
  { key: 'harvestArea', label: '收获面积(手动)', type: 'number', group: '产量计算参数', unit: 'm²', system: true, hint: '填写后优先使用；否则 = 收获长 × [(行数-1)×行距 + 畦埂宽]' },

  { key: 'prevCrop', label: '前茬', type: 'text', group: '二、栽培管理' },
  { key: 'soil', label: '土质', type: 'text', group: '二、栽培管理' },
  { key: 'irrigated', label: '水、旱地', type: 'select', group: '二、栽培管理', options: ['水地', '旱地'] },
  { key: 'baseFert', label: '基肥（种类、数量、施肥时间及方法）', type: 'textarea', group: '二、栽培管理' },
  { key: 'tillage', label: '整地（时间、机具、质量）', type: 'textarea', group: '二、栽培管理' },
  { key: 'seedFert', label: '种肥（种类、数量、施肥时间及方法）', type: 'textarea', group: '二、栽培管理' },
  { key: 'seedTreat', label: '种子处理', type: 'textarea', group: '二、栽培管理' },
  { key: 'sowingDate', label: '播种期', type: 'date', group: '二、栽培管理', system: true },
  { key: 'sowingMethod', label: '播种方法', type: 'text', group: '二、栽培管理' },
  { key: 'topDress', label: '追肥（种类、数量、质量、施用时间及方法）', type: 'textarea', group: '二、栽培管理' },
  { key: 'weeding', label: '中耕除草（时间、次数、方法和质量）', type: 'textarea', group: '二、栽培管理' },
  { key: 'irrigation', label: '灌溉（时间、次数、方法）', type: 'textarea', group: '二、栽培管理' },
  { key: 'pest', label: '病虫害防治（对象、时间、药剂名称和方法）', type: 'textarea', group: '二、栽培管理' },
  { key: 'harvestDate', label: '收获期', type: 'date', group: '二、栽培管理' },
  { key: 'other', label: '其它', type: 'textarea', group: '二、栽培管理' },

  { key: 'testUnit', label: '试验单位', type: 'text', group: '联系信息' },
  { key: 'contact', label: '负责人', type: 'text', group: '联系信息' },
  { key: 'phone', label: '联系电话', type: 'text', group: '联系信息' },
];

/** 区块（重复 / 保护区等） */
export const BLOCK_FIELDS: FieldDef[] = [
  { key: 'name', label: '名称', type: 'text', group: '基本', system: true, onMap: true },
  {
    key: 'rep',
    label: '重复',
    type: 'select',
    group: '基本',
    system: true,
    options: ['1|Ⅰ', '2|Ⅱ', '3|Ⅲ', '4|Ⅳ', '5|Ⅴ', '6|Ⅵ'],
  },
  { key: 'note', label: '备注', type: 'textarea', group: '基本' },
];

/** 小区：四、田间记载 + 六、室内考种 */
export const PLOT_FIELDS: FieldDef[] = [
  { key: 'plotNo', label: '小区号', type: 'text', group: '基本', system: true, onMap: true },
  { key: 'variety', label: '品种', type: 'variety', group: '基本', system: true, onMap: true },

  { key: 'emergence', label: '出苗期', type: 'date', group: '生育期', system: true },
  { key: 'heading', label: '抽穗期', type: 'date', group: '生育期' },
  { key: 'maturity', label: '成熟期', type: 'date', group: '生育期', system: true },

  { key: 'habit', label: '习性', type: 'select', group: '茎蘖与株高', options: ['1|匍匐', '2|半匍匐', '3|直立'] },
  { key: 'basicSeedlings', label: '基本苗', type: 'number', group: '茎蘖与株高', unit: '万/亩' },
  { key: 'maxTillers', label: '最高分蘖', type: 'number', group: '茎蘖与株高', unit: '万/亩', system: true },
  { key: 'spikes', label: '亩成穗', type: 'number', group: '茎蘖与株高', unit: '万/亩', system: true },
  { key: 'height', label: '株高', type: 'number', group: '茎蘖与株高', unit: 'cm' },

  { key: 'winterFrostDate', label: '冬冻日期', type: 'date', group: '抗逆性' },
  { key: 'winterFrost', label: '冬冻程度', type: 'select', group: '抗逆性', options: GRADE5, hint: FROST_HINT },
  { key: 'springFrostDate', label: '春冻日期', type: 'date', group: '抗逆性' },
  { key: 'springFrost', label: '春冻程度', type: 'select', group: '抗逆性', options: GRADE5, hint: FROST_HINT },
  { key: 'overwinter', label: '越冬率', type: 'number', group: '抗逆性', unit: '%' },
  { key: 'droughtDate', label: '旱害日期', type: 'date', group: '抗逆性' },
  { key: 'drought', label: '旱害程度', type: 'select', group: '抗逆性', options: GRADE5 },
  { key: 'prematureDate', label: '青干日期', type: 'date', group: '抗逆性' },
  { key: 'premature', label: '青干程度', type: 'select', group: '抗逆性', options: GRADE5 },
  { key: 'wetDate', label: '湿害日期', type: 'date', group: '抗逆性' },
  { key: 'wet', label: '湿害程度', type: 'select', group: '抗逆性', options: GRADE5 },

  { key: 'lodge1Date', label: '第1次倒伏日期', type: 'date', group: '倒伏' },
  { key: 'lodge1Degree', label: '第1次倒伏程度', type: 'select', group: '倒伏', options: GRADE5, hint: LODGE_HINT },
  { key: 'lodge1Area', label: '第1次倒伏面积', type: 'number', group: '倒伏', unit: '%' },
  { key: 'lodge2Date', label: '第2次倒伏日期', type: 'date', group: '倒伏' },
  { key: 'lodge2Degree', label: '第2次倒伏程度', type: 'select', group: '倒伏', options: GRADE5, hint: LODGE_HINT },
  { key: 'lodge2Area', label: '第2次倒伏面积', type: 'number', group: '倒伏', unit: '%' },

  { key: 'stripeRustIT', label: '条锈病反应型', type: 'select', group: '病害', options: IT_OPTIONS },
  { key: 'stripeRustSev', label: '条锈病严重度', type: 'number', group: '病害', unit: '%' },
  { key: 'stripeRustInc', label: '条锈病普遍率', type: 'number', group: '病害', unit: '%' },
  { key: 'powderyMildew', label: '白粉病', type: 'select', group: '病害', options: ['0', '1', '2', '3', '4', '5', '6', '7', '8', '9'], hint: '0~9级' },
  { key: 'leafRustIT', label: '叶锈病反应型', type: 'select', group: '病害', options: IT_OPTIONS },
  { key: 'leafRustSev', label: '叶锈病严重度', type: 'number', group: '病害', unit: '%' },
  { key: 'leafRustInc', label: '叶锈病普遍率', type: 'number', group: '病害', unit: '%' },
  { key: 'fhbSev', label: '赤霉病严重度', type: 'select', group: '病害', options: GRADE5 },
  { key: 'fhbInc', label: '赤霉病病穗率', type: 'number', group: '病害', unit: '%' },
  { key: 'sharpEyespot', label: '纹枯病', type: 'select', group: '病害', options: ['0', '1', '2', '3', '4', '5'], hint: '0~5级' },
  { key: 'sharpEyespotWh', label: '纹枯病白穗率', type: 'number', group: '病害', unit: '%' },
  { key: 'rootRotSpike', label: '根腐病穗部', type: 'number', group: '病害', unit: '%' },
  { key: 'leafBlight', label: '叶枯病', type: 'select', group: '病害', options: GRADE5 },

  { key: 'sprouting', label: '穗发芽', type: 'select', group: '收获', options: GRADE5 },
  { key: 'shattering', label: '落粒性', type: 'select', group: '收获', options: ['1|难', '2|中', '3|易'] },
  { key: 'maturityLook', label: '熟相', type: 'select', group: '收获', options: ['1|好', '2|中', '3|差'] },
  { key: 'offTypes', label: '小区杂株数', type: 'number', group: '收获', unit: '株' },
  { key: 'plotYield', label: '小区产量', type: 'number', group: '收获', unit: 'kg', system: true },
  { key: 'fieldEval', label: '田间评价', type: 'textarea', group: '收获' },

  { key: 'spikeShape', label: '穗型', type: 'select', group: '室内考种', options: ['纺锤形', '长方形', '圆锥形', '棍棒形', '椭圆形'] },
  { key: 'glumeColor', label: '壳色', type: 'select', group: '室内考种', options: ['白', '红'] },
  { key: 'awn', label: '芒', type: 'select', group: '室内考种', options: ['长芒', '短芒', '顶芒', '无芒'] },
  { key: 'grainsPerSpike', label: '每穗粒数', type: 'number', group: '室内考种', unit: '粒' },
  { key: 'grainColor', label: '粒色', type: 'select', group: '室内考种', options: ['白', '红'] },
  { key: 'plumpness', label: '饱满度', type: 'select', group: '室内考种', options: ['饱满', '较饱满', '中等', '欠饱满'] },
  { key: 'grainQuality', label: '籽粒品质', type: 'select', group: '室内考种', options: ['硬质', '半硬质', '粉质'] },
  { key: 'blackPoint', label: '黑胚率', type: 'number', group: '室内考种', unit: '%' },
  { key: 'tkw', label: '千粒重', type: 'number', group: '室内考种', unit: '克' },
  { key: 'testWeight', label: '容重', type: 'number', group: '室内考种', unit: '克/升' },
  { key: 'offTypeRate', label: '杂株率', type: 'number', group: '室内考种', unit: '%' },
];

/** 子块（小区内的行/列） */
export const SUB_FIELDS: FieldDef[] = [
  { key: 'name', label: '名称', type: 'text', group: '基本', system: true, onMap: true },
  { key: 'note', label: '备注', type: 'textarea', group: '基本' },
];

export const DEFAULT_SCHEMAS: Record<SchemaLevel, FieldDef[]> = {
  meta: META_FIELDS,
  block: BLOCK_FIELDS,
  plot: PLOT_FIELDS,
  sub: SUB_FIELDS,
};

export const LEVEL_LABELS: Record<SchemaLevel, string> = {
  meta: '基本情况',
  block: '区块',
  plot: '小区',
  sub: '子块(行/列)',
};

/** 层级深度 → 字段层级 */
export function levelOfDepth(depth: number): SchemaLevel {
  if (depth <= 0) return 'meta';
  if (depth === 1) return 'block';
  if (depth === 2) return 'plot';
  return 'sub';
}

export function depthName(depth: number): string {
  return ['试验田', '区块', '小区', '子块'][Math.min(depth, 3)];
}

/** 解析选项 "值|文本" */
export function parseOption(o: string): { value: string; label: string } {
  const i = o.indexOf('|');
  if (i < 0) return { value: o, label: o };
  return { value: o.slice(0, i), label: o.slice(i + 1) };
}

export function optionLabel(def: FieldDef, value: string): string {
  if (!def.options) return value;
  for (const o of def.options) {
    const p = parseOption(o);
    if (p.value === value) return p.label === p.value ? p.value : `${p.value} ${p.label}`;
  }
  return value;
}

/** 气象资料表行 */
export interface WeatherRow {
  key: string;
  group: string;
  label: string;
}

export const WEATHER_MONTHS = ['9', '10', '11', '12', '1', '2', '3', '4', '5', '6'];

export const WEATHER_ROWS: WeatherRow[] = [
  ...(['上旬', '中旬', '下旬'] as const).flatMap((d, i) =>
    [
      ['avg', '平均气温'],
      ['max', '最高气温'],
      ['min', '最低气温'],
    ].map(([k, l]) => ({ key: `t${i}_${k}`, group: `${d}气温(℃)`, label: l })),
  ),
  { key: 'tMonth', group: '月平均气温(℃)', label: '月平均气温' },
  { key: 'p0', group: '降水(mm)', label: '上旬' },
  { key: 'p1', group: '降水(mm)', label: '中旬' },
  { key: 'p2', group: '降水(mm)', label: '下旬' },
  { key: 'pMonth', group: '月降水', label: '月降水总量(mm)' },
  { key: 'pDays', group: '月降水', label: '月降水天数(天)' },
  { key: 'pMax', group: '月降水', label: '月最大降水量(mm)' },
  { key: 's0', group: '日照(小时)', label: '上旬' },
  { key: 's1', group: '日照(小时)', label: '中旬' },
  { key: 's2', group: '日照(小时)', label: '下旬' },
  { key: 'sMonth', group: '月日照', label: '月日照时数(小时)' },
];
