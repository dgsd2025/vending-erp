import request from '@/utils/request'

/**
 * 导入中心 API(M1-3):三通道两步式(上传预览 → 确认入账)+ 批次/错误/回滚/重处理/改价。
 */

export type ImportFileType = '出货明细' | '系统补货记录' | '商品列表'

export interface ColumnCheck {
  expected: string
  required: boolean
  found: boolean
}

export interface PreviewResp {
  token: string
  fileName: string
  fileType: ImportFileType
  rowTotal: number
  columnChecks: ColumnCheck[]
  columnsOk: boolean
  headers: string[]
  previewRows: Record<string, string | null>[]
  warnings: string[]
}

/** 导入自愈:单条列映射建议 */
export interface FixMapping {
  expected: string
  required: boolean
  suggested: string | null
  candidates: string[]
  reason: string
}

/** 导入自愈建议结果 */
export interface FixSuggestResp {
  token: string
  mappings: FixMapping[]
  confidence: number
  llmCallId: number | null
  mode: string
}

/** 失败行(供修改重导) */
export interface FailedRow {
  errorId: number
  rowNo: number
  cells: Record<string, string>
  errorType: string
  errorMsg: string
}

/** 某批次失败行清单 + 通道列规格 */
export interface FailedRowsResp {
  fileType: ImportFileType
  /** 每列 [列名, "1"必填/"0"选填] */
  columnSpec: [string, string][]
  rows: FailedRow[]
}

export interface NegativeStock {
  productId: number
  skuCode: string
  productName: string
  balance: number
}

export interface CommitResp {
  batchId: number
  batchNo: string
  fileType: ImportFileType
  rowTotal: number
  rowOk: number
  rowFail: number
  rowDup: number
  pendingBind: number
  priceChangeCount: number
  docsCreated: number
  matchedPrePending: number
  snapshots: number
  negativeStock: NegativeStock[]
}

export interface ImportBatch {
  id: number
  batchNo: string
  fileName: string
  fileType: ImportFileType
  archivePath?: string
  periodRange?: string
  rowTotal: number
  rowOk: number
  rowFail: number
  rowDup: number
  batchStatus: '处理中' | '已导入' | '已回滚'
  createTime?: string
}

export interface ImportError {
  id: number
  batchId: number
  rowNo: number
  rawContent: string
  errorType: string
  errorMsg: string
  resolveStatus: string
}

export interface PriceChange {
  productId: number
  skuCode: string
  productName: string
  refPrice: number
  newPrice: number
  rowCount: number
}

export interface RollbackResp {
  success: boolean
  blockers: string[]
  saleRemoved: number
  docsVoided: number
  ledgerRemoved: number
  snapshotRemoved: number
}

export interface ReprocessResp {
  scanned: number
  rebound: number
  stillPending: number
}

export interface PageResult<T> {
  records: T[]
  total: number
  size: number
  current: number
}

// ---------- 期初导入向导(M1-6) ----------

export interface ConflictGroup {
  code: string
  names: string[]
  splitCodes: string[]
}

export interface Step1PreviewResp {
  token: string
  fileName: string
  productCount: number
  aliasCount: number
  machineCount: number
  conflicts: ConflictGroup[]
  autoCreateCodes: string[]
  warnings: string[]
}

export interface Step1Resp {
  batchId: number
  productCreated: number
  productSkipped: number
  aliasCreated: number
  machineCreated: number
  splitProducts: number
}

export interface Step2PreviewResp {
  token: string
  fileName: string
  rowCount: number
  totalQty: number
  totalAmt: number
  dates: string[]
  supplierNames: string[]
  missingProducts: string[]
  warnings: string[]
}

export interface Step2Resp {
  batchId: number
  docsCreated: number
  itemCount: number
  totalAmt: number
  supplierCreated: number
  rowFail: number
}

export interface Step3PreviewResp {
  token: string
  fileName: string
  rowCount: number
  totalAmt: number
  warnings: string[]
}

export interface InitialStepState {
  done: boolean
  batchId: number | null
  batchNo: string | null
  batchStatus: string | null
  doneAt: string | null
  rowOk: number
  rowFail: number
}

export interface InitialStatusResp {
  step1: InitialStepState
  step2: InitialStepState
  step3: InitialStepState
  allStepsDone: boolean
  systemPurchaseTotal: number
  systemSaleTotal: number
}

export interface ValidateResp {
  systemPurchase: number
  systemSale: number
  expectedPurchase: number
  expectedSale: number
  purchaseDiff: number
  saleDiff: number
  purchasePass: boolean
  salePass: boolean
  pass: boolean
}

const operatorHeader = () => ({ 'X-User-Name': encodeURIComponent('管理员') })

export const importsApi = {
  /** 第①步:上传解析预览(未入账) */
  upload(fileType: ImportFileType, file: File): Promise<PreviewResp> {
    const form = new FormData()
    form.append('fileType', fileType)
    form.append('file', file)
    return request.post('/v1/imports/upload', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 120000,
    })
  },
  /** 第②步:确认入账 */
  confirm(token: string): Promise<CommitResp> {
    return request.post('/v1/imports/confirm', { token }, { headers: operatorHeader(), timeout: 300000 })
  },
  /** 导入自愈:AI 猜列映射(厂家改模板时) */
  fixSuggest(token: string, force = false): Promise<FixSuggestResp> {
    return request.post('/v1/imports/fix/suggest', { token, force }, { timeout: 60000 })
  },
  /** 导入自愈:按确认的映射重新校验预览 */
  fixApply(token: string, columnMap: Record<string, string>): Promise<PreviewResp> {
    return request.post('/v1/imports/fix/apply', { token, columnMap })
  },
  /** 取批次失败行(带原始数据,供修改) */
  failedRows(batchId: number): Promise<FailedRowsResp> {
    return request.get(`/v1/imports/batches/${batchId}/failed-rows`)
  },
  /** 修改失败行后重新导入(建修正批次) */
  refix(batchId: number, rows: Record<string, string>[]): Promise<CommitResp> {
    return request.post(`/v1/imports/batches/${batchId}/refix`, { rows }, { headers: operatorHeader(), timeout: 120000 })
  },
  batches(current = 1, size = 20, fileType?: string): Promise<PageResult<ImportBatch>> {
    return request.get('/v1/imports/batches', { params: { current, size, fileType } })
  },
  errors(batchId: number, current = 1, size = 50): Promise<PageResult<ImportError>> {
    return request.get(`/v1/imports/batches/${batchId}/errors`, { params: { current, size } })
  },
  rollback(batchId: number): Promise<RollbackResp> {
    return request.post(`/v1/imports/batches/${batchId}/rollback`, null, { headers: operatorHeader() })
  },
  reprocess(batchId: number): Promise<ReprocessResp> {
    return request.post(`/v1/imports/batches/${batchId}/reprocess`, null, { headers: operatorHeader() })
  },
  priceChanges(batchId: number): Promise<PriceChange[]> {
    return request.get(`/v1/imports/batches/${batchId}/price-changes`)
  },
  confirmPriceChanges(batchId: number, items: { productId: number; newPrice: number }[]): Promise<number> {
    return request.post(`/v1/imports/batches/${batchId}/price-changes/confirm`, { items }, { headers: operatorHeader() })
  },
}

/** 期初导入向导 API:三步都直接吃老 Excel 套表原文件 */
export const initialApi = {
  status(): Promise<InitialStatusResp> {
    return request.get('/v1/imports/initial/status')
  },
  stepUpload(step: 1 | 2 | 3, file: File): Promise<Step1PreviewResp & Step2PreviewResp & Step3PreviewResp> {
    const form = new FormData()
    form.append('file', file)
    return request.post(`/v1/imports/initial/step${step}/upload`, form, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 120000,
    })
  },
  step1Confirm(token: string, resolutions: { code: string; mode: 'split' | 'first' }[]): Promise<Step1Resp> {
    return request.post('/v1/imports/initial/step1/confirm', { token, resolutions }, { headers: operatorHeader(), timeout: 300000 })
  },
  step2Confirm(token: string): Promise<Step2Resp> {
    return request.post(`/v1/imports/initial/step2/confirm?token=${token}`, null, { headers: operatorHeader(), timeout: 300000 })
  },
  step3Confirm(token: string): Promise<CommitResp> {
    return request.post(`/v1/imports/initial/step3/confirm?token=${token}`, null, { headers: operatorHeader(), timeout: 300000 })
  },
  validate(expectedPurchase: number, expectedSale: number): Promise<ValidateResp> {
    return request.post('/v1/imports/initial/validate', { expectedPurchase, expectedSale }, { headers: operatorHeader() })
  },
}
