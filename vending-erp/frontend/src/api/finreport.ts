import request from '@/utils/request'

/**
 * 财务报表 API(M3-6):资产快照(即时/归档/趋势,p10 资产家底)+ 简版利润表。
 */

export interface InventoryRow {
  productId: number
  code: string
  name: string
  qty: number
  amount: number
}

export interface PendingRow {
  period: string
  cnt: number
  amount: number
}

export interface CashRow {
  accountId: number
  accountName: string
  accountType: string
  balance: number
  disabled: boolean
}

export interface ClaimItemRow {
  claimId: number
  claimNo: string
  claimTarget: string
  amount: number
  createTime: string | null
}

export interface PayableRow {
  supplierId: number
  supplierName: string
  balance: number
  prepay: boolean
}

export interface AssetSnapshotResp {
  asOf: string
  dataAsOf: string | null
  settleMode: string
  settleBanner: string | null
  inventoryAmount: number
  warehouseAmount: number
  machineAmount: number
  platformPending: number
  cashTotal: number
  claimReceivable: number
  payableTotal: number
  netAsset: number
  inventoryRows: InventoryRow[]
  pendingRows: PendingRow[]
  cashRows: CashRow[]
  claimRows: ClaimItemRow[]
  payableRows: PayableRow[]
  prevPeriod: string | null
  prevNetAsset: number | null
}

export interface SnapshotRow {
  id: number
  period: string
  inventoryAmount: number
  platformPending: number
  cashTotal: number
  claimReceivable: number
  payableTotal: number
  netAsset: number
  updateTime: string | null
  locked: boolean
}

export interface PlRow {
  key: string
  label: string
  amount: number
  subtotal: boolean
  note: string | null
}

export interface LockDiffRow {
  settlementId: number
  stmtNo: string
  periodStart: string
  periodEnd: string
  stlStatus: string
  note: string
}

export interface ProfitResp {
  period: string
  months: string[]
  locked: boolean
  lockedNote: string | null
  settleMode: string
  settleBanner: string | null
  rows: PlRow[]
  operatingProfit: number
  nonPlNet: number
  lockDiffNotes: LockDiffRow[]
}

export const finreportApi = {
  assets: () => request.get<never, AssetSnapshotResp>('/v1/finreport/assets'),
  archive: (period: string) =>
    request.post<never, SnapshotRow>('/v1/finreport/assets/archive', null, { params: { period } }),
  archives: () => request.get<never, SnapshotRow[]>('/v1/finreport/assets/archives'),
  trend: (months = 12) =>
    request.get<never, SnapshotRow[]>('/v1/finreport/assets/trend', { params: { months } }),
  profit: (period?: string) =>
    request.get<never, ProfitResp>('/v1/finreport/profit', { params: { period } }),
}
