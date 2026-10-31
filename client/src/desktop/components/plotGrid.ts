// PlotGrid 组件用到的数据类型

export interface GridCell {
  plotId: number
  rowNo: number
  colNo: number
  lines: string[]
  fill: string
  /** 右上角的小标记，例如 CK 表示对照、! 表示异常 */
  badge?: string
  badgeColor?: string
  /** 左下角的小圆点，表示今天录过数据 */
  marker?: boolean
  /** 鼠标停在小区上时显示的说明 */
  tooltip?: string
}

export interface GridLinkSegment {
  from: number
  to: number
}

export interface GridLink {
  id: number
  label: string
  segments: GridLinkSegment[]
  /** 对照小区：标签画在它上方 */
  controlPlotIds: number[]
}

export interface GridDevice {
  id: number
  /** 第几行、第几列，可以是小数：3 表示第 3 格中间，3.5 表示第 3、4 格之间 */
  row: number
  col: number
  label: string
  status: string
}
