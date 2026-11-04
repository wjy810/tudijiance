/** 字段类型 */
export type FieldType = 'text' | 'number' | 'date' | 'select' | 'textarea' | 'variety';

/** 字段定义（可在“字段设置”中增删改） */
export interface FieldDef {
  key: string;
  label: string;
  type: FieldType;
  group?: string;
  unit?: string;
  /** select 选项，格式 "值" 或 "值|显示文本" */
  options?: string[];
  hint?: string;
  /** 内置字段：计算或导出依赖，不可删除 */
  system?: boolean;
  /** 在种植图中显示 */
  onMap?: boolean;
}

/** 字段所属层级：基本情况 / 区块 / 小区 / 子块(小区内的行、列) */
export type SchemaLevel = 'meta' | 'block' | 'plot' | 'sub';

/** 单元类型：正常种植 / 路面 / 保护区 / 空地 */
export type CellKind = 'normal' | 'road' | 'protect' | 'blank';

/** 合并区域，(r,c) 为左上角锚点 */
export interface Merge {
  r: number;
  c: number;
  rs: number;
  cs: number;
}

/** 网格：每行高度、每列宽度（米） */
export interface Grid {
  rows: number[];
  cols: number[];
  merges: Merge[];
}

/** 实际尺寸与在原槽位内的偏移（米）；为 null 表示填满槽位 */
export interface Fit {
  x: number;
  y: number;
  w: number;
  h: number;
}

/** 种植图节点：试验田(根) → 区块 → 小区 → 子块 …，每层结构相同 */
export interface PNode {
  id: string;
  kind: CellKind;
  data: Record<string, string>;
  fit: Fit | null;
  grid: Grid | null;
  /** 子节点，键为锚点 "r,c" */
  children: Record<string, PNode>;
}

export type VarietyRole = 'test' | 'ck' | 'ck2';

export interface Variety {
  id: string;
  code: string;
  name: string;
  role: VarietyRole;
  source?: string;
  note?: string;
  evaluation?: string;
}

export interface Project {
  version: 1;
  name: string;
  meta: Record<string, string>;
  schemas: Record<SchemaLevel, FieldDef[]>;
  varieties: Variety[];
  /** 气象资料，键 `${行key}|${月}|${cur|norm}` */
  weather: Record<string, string>;
  weatherAnalysis: string;
  /** 真北方向：相对图纸“上方”的顺时针角度（度） */
  northDeg: number;
  field: PNode;
  updatedAt: string;
}
