import request from '@/utils/request'

/**
 * BI 经营分析 API(M4-1,§10.1):六维矩阵 / 单品四象限 / 客单连带支付 / 缺货损失 / 调价对比。
 * 只读模块;矩阵取不到的格子后端返回 null(前端显「—」),禁造假。
 */

export type BiDim = 'machine' | 'category' | 'product' | 'slot' | 'timeslot' | 'supplier'

export interface MatrixRow {
  key: number | null
  name: string
  code: string | null
  category: string | null
  salesQty: number | null
  salesAmt: number | null
  sharePct: number | null
  dailyAvgAmt: number | null
  grossProfit: number | null
  marginPct: number | null
  grossSharePct: number | null
  noCostSkuCount: number | null
  hasCost: boolean | null
  activeRate: number | null
  soldSkuCount: number | null
  onSaleSkuCount: number | null
  stockDays: number | null
  sellThroughPct: number | null
  lossQty: number | null
  lossAmt: number | null
  swallowRate: number | null
  stockoutLossAmt: number | null
  purchaseAmt: number | null
  purchaseSharePct: number | null
  marginContribution: number | null
  attributedSkuCount: number | null
  receiveDiffRate: number | null
  estQty: number | null
  estShared: boolean | null
}

export interface MatrixResp {
  month: string
  months: string[]
  dim: BiDim
  dataAsOf: string | null
  daysElapsed: number
  rows: MatrixRow[]
  weekdayRows: MatrixRow[] | null
}

export interface QuadrantPoint {
  productId: number
  name: string
  skuCode: string | null
  category: string | null
  salesQty: number
  salesAmt: number
  grossProfit: number | null
  marginPct: number | null
  quadrant: string | null
}

export interface QuadrantResp {
  month: string
  months: string[]
  dataAsOf: string | null
  qtyMedian: number | null
  marginMedian: number | null
  points: QuadrantPoint[]
  noCostPoints: QuadrantPoint[]
}

export interface ComboRow {
  productA: string
  productB: string
  times: number
}

export interface PayMethodRow {
  payMethod: string
  orderCount: number
  amt: number
  sharePct: number | null
}

export interface BasketResp {
  month: string
  months: string[]
  dataAsOf: string | null
  avgOrderValue: number | null
  orderCount: number
  multiItemOrderCount: number
  multiItemSharePct: number | null
  combos: ComboRow[]
  payMethods: PayMethodRow[]
}

export interface StockoutLossRow {
  machineId: number
  machineName: string
  productId: number
  productName: string
  coverageDays: number
  stockoutDays: number
  dailyGross: number
  estLoss: number
}

export interface StockoutLossResp {
  month: string
  months: string[]
  dataAsOf: string | null
  rows: StockoutLossRow[]
  totalEstLoss: number | null
  note: string
}

export interface PriceCompareRow {
  productId: number
  productName: string
  skuCode: string | null
  oldPrice: number | null
  newPrice: number | null
  effectDate: string
  changeSource: string
  beforeDays: number
  afterDays: number
  partial: boolean
  beforeAvgQty: number | null
  afterAvgQty: number | null
  beforeAvgAmt: number | null
  afterAvgAmt: number | null
  beforeGross: number | null
  afterGross: number | null
  qtyChangePct: number | null
}

export interface PriceCompareResp {
  dataAsOf: string | null
  rows: PriceCompareRow[]
}

export const biApi = {
  matrix: (month: string | undefined, dim: BiDim) =>
    request.get<never, MatrixResp>('/v1/bi/matrix', { params: { month, dim } }),
  quadrant: (month?: string) =>
    request.get<never, QuadrantResp>('/v1/bi/quadrant', { params: { month } }),
  basket: (month?: string) =>
    request.get<never, BasketResp>('/v1/bi/basket', { params: { month } }),
  stockoutLoss: (month?: string) =>
    request.get<never, StockoutLossResp>('/v1/bi/stockout-loss', { params: { month } }),
  priceCompare: () => request.get<never, PriceCompareResp>('/v1/bi/price-compare'),
}
