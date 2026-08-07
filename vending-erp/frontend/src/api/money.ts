import request from '@/utils/request'
import { currentUserName } from '@/api/basedata'

function opHeaders(extra?: Record<string, string>) {
  return { headers: { 'X-User-Name': encodeURIComponent(currentUserName()), ...(extra || {}) } }
}

/**
 * 钱账基座 API(M3-1):账户 CRUD / 流水只读 / 凭证 / 结算模式。
 * 注意:没有"新增流水"接口——钱只能被单据改,流水由后端单据确认事件产生。
 */

// ---------- 类型 ----------

/** 真实 4 类 + 虚拟 2 类 */
export const REAL_ACCOUNT_TYPES = ['微信', '支付宝', '银行卡', '现金'] as const
export const VIRTUAL_ACCOUNT_TYPES = ['平台待结算', '老板垫付'] as const
export const ACCOUNT_TYPES = [...REAL_ACCOUNT_TYPES, ...VIRTUAL_ACCOUNT_TYPES]

export interface AccountRow {
  id: number
  accountName: string
  accountType: string
  isVirtual: boolean
  openingBalance: number | string
  openingSetAt?: string | null
  openingLocked: boolean
  balance: number | string
  status: number
}

export interface FlowRow {
  id: number
  flowNo: string
  accountId: number
  accountName?: string
  direction: '收' | '支'
  amount: number | string
  category: string
  plLine: string
  refDocType?: string
  refDocId?: number
  bizTime: string
  bookPeriod: string
  remark?: string
}

export interface PlSummaryRow {
  plLine: string
  period: string
  inflow: number | string
  outflow: number | string
  net: number | string
}

export interface PageResult<T> {
  records: T[]
  total: number
  current: number
  size: number
}

export interface SettleModeResp {
  mode: 'UNSET' | 'PLATFORM' | 'DIRECT'
  banner?: string | null
  options: { mode: string; label: string; description: string }[]
}

export interface AttachmentRow {
  id: number
  refType: string
  refId: number
  attType: string
  fileName?: string
  createTime?: string
  uploadBy?: number
}

// ---------- 账户 ----------

export function listAccounts(): Promise<AccountRow[]> {
  return request.get('/v1/money/accounts')
}

export function createAccount(data: {
  accountName: string
  accountType: string
  openingBalance?: number | null
}): Promise<number> {
  return request.post('/v1/money/accounts', data, opHeaders())
}

export function renameAccount(id: number, accountName: string): Promise<void> {
  return request.put(`/v1/money/accounts/${id}`, { accountName }, opHeaders())
}

/** 期初只能设一次:设过再调会被后端拒绝(走资金调整单) */
export function setOpening(id: number, openingBalance: number): Promise<void> {
  return request.post(`/v1/money/accounts/${id}/opening`, { openingBalance }, opHeaders())
}

export function changeAccountStatus(id: number, targetStatus: 0 | 1): Promise<void> {
  return request.put(`/v1/money/accounts/${id}/status`, { targetStatus }, opHeaders())
}

export function accountBalance(id: number): Promise<number> {
  return request.get(`/v1/money/accounts/${id}/balance`)
}

// ---------- 流水(只读) ----------

export function pageFlows(params: {
  current?: number
  size?: number
  accountId?: number
  category?: string
  fromPeriod?: string
  toPeriod?: string
}): Promise<PageResult<FlowRow>> {
  return request.get('/v1/money/flows', { params })
}

export function plSummary(params: {
  fromPeriod?: string
  toPeriod?: string
}): Promise<PlSummaryRow[]> {
  return request.get('/v1/money/flows/pl-summary', { params })
}

// ---------- 凭证 ----------

export const ATTACHMENT_TYPES = ['转账截图', '平台账单', '赔付凭证', '发票', '照片'] as const

export function uploadAttachment(
  refType: string,
  refId: number,
  attType: string,
  file: File,
): Promise<number> {
  const form = new FormData()
  form.append('refType', refType)
  form.append('refId', String(refId))
  form.append('attType', attType)
  form.append('file', file)
  return request.post('/v1/money/attachments', form, opHeaders())
}

export function listAttachments(refType: string, refId: number): Promise<AttachmentRow[]> {
  return request.get('/v1/money/attachments', { params: { refType, refId } })
}

/** 预览地址(inline 输出,图片/PDF 浏览器直显) */
export function attachmentFileUrl(id: number): string {
  return `/api/v1/money/attachments/${id}/file`
}

// ---------- 结算模式 ----------

export function getSettleMode(): Promise<SettleModeResp> {
  return request.get('/v1/money/settle-mode')
}

export function setSettleMode(mode: string): Promise<SettleModeResp> {
  return request.put('/v1/money/settle-mode', { mode }, opHeaders())
}
