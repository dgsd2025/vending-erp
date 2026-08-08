import request from '@/utils/request'

/** 上手进度(驱动新手指引「开业向导」清单打勾)——后端实时算,不落表 */
export interface SetupStatus {
  machineCount: number
  productCount: number
  supplierCount: number
  accountCount: number
  settleMode: string
  settleModeSet: boolean
  prekitDone: boolean
  salesFlowing: boolean
  reconciled: boolean
}

export const guideApi = {
  /** GET /api/v1/guide/setup-status */
  setupStatus: (): Promise<SetupStatus> => request.get('/v1/guide/setup-status'),
}
