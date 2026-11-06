/** 在平面图上选株时从哪里选的 */
export interface SelectLocation {
  fieldId: number
  blockId?: string | null
  plotId?: number | null
  /** 例如"东地 区块1 3 号小区块" */
  text: string
  seasonId?: number | null
}
