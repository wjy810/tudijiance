// PlotPicker 组件用到的类型

/** 平面图上的一个小区格子 */
export interface PickerCell {
  plotId: number
  rowNo: number
  colNo: number
  code: string
  /** 第二行：品种 */
  sub: string
  /** 第三行：重复或指标值 */
  third?: string
  fill: string
  /** 能不能选（保护区、走道不能） */
  selectable: boolean
  ck?: boolean
  abnormal?: boolean
  /** 今天录过 */
  today?: boolean
  /** 有还没传上去的录入 */
  unsynced?: boolean
}
