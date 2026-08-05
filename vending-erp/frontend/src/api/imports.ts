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
