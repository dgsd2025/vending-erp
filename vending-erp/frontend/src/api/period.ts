import request from '@/utils/request'
import { currentUserName } from '@/api/basedata'

function opHeaders(extra?: Record<string, string>) {
  return { headers: { 'X-User-Name': encodeURIComponent(currentUserName()), ...(extra || {}) } }
}

/**
 * 期间管理 API(M1-7,P0-2 锁账×补导):
 * 锁账线=锁定某 YYYY-MM 及之前所有月;解锁限老板角色(占位)+强制备注;
 * 锁账只管改单不管补导——补导入当月,"上期调整"行承接。
 */

export interface PeriodLockRow {
  id: number
  period: string
  lockedAt: string
  lockedBy: number
  lockNote?: string | null
}

export interface LocksResp {
  lockLine: string | null
  locks: PeriodLockRow[]
}

export interface PriorAdjustResp {
  bookPeriod: string
  saleRows: number
  saleAmount: number | string
  saleLines: { bizPeriod: string; importBatchId?: number | null; rowCount: number; qty: number | string; amount: number | string }[]
  docLines: { bizPeriod: string; docType: string; docCount: number; amount: number | string }[]
}

export function getLocks(): Promise<LocksResp> {
  return request.get('/v1/period/locks')
}

export function lockPeriod(period: string, note?: string): Promise<number> {
  return request.post('/v1/period/locks', { period, note: note || null }, opHeaders())
}

/** 解锁:限老板角色(X-User-Role 占位)+强制备注 */
export function unlockPeriod(period: string, note: string): Promise<void> {
  return request.post('/v1/period/unlock', { period, note }, opHeaders({ 'X-User-Role': encodeURIComponent('老板') }))
}

/** 上期调整聚合(报表"上期调整"行取数口) */
export function getPriorAdjust(bookPeriod: string): Promise<PriorAdjustResp> {
  return request.get('/v1/period/prior-adjust', { params: { bookPeriod } })
}
