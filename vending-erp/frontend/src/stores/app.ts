import { defineStore } from 'pinia'
import { reportApi } from '@/api/report'

/**
 * 全局应用状态。
 * dataAsOf:全局铁律「数据新鲜度一等公民」——所有页面顶部水印显示的数据截止日。
 * 由导入中心/驾驶舱写入,任一数据页首次进入若为空会 lazy 拉取一次(loadDataAsOf),
 * 保证 DataFreshnessBar 在全站每个页面都能自足显示,不依赖用户先经过驾驶舱。
 * 铁律1(数据截至):超过 3 天未更新 → 判定为「过期」,水印变灰 + 提示先导数据。
 */
export const useAppStore = defineStore('app', {
  state: () => ({
    /** 数据截至日期(YYYY-MM-DD),null = 尚无数据 */
    dataAsOf: null as string | null,
    /** 已尝试过 lazy 拉取(避免每页重复打后端) */
    _asOfLoaded: false,
  }),
  getters: {
    /** 距今天已过去几天(0=今天,null=无数据);按自然日取整 */
    daysStale(state): number | null {
      if (!state.dataAsOf) return null
      const d = new Date(state.dataAsOf + 'T00:00:00')
      const today = new Date()
      today.setHours(0, 0, 0, 0)
      return Math.floor((today.getTime() - d.getTime()) / 86_400_000)
    },
    /** 铁律1:超 3 天未更新即过期(建议先导数据) */
    isStale(): boolean {
      const n = (this as unknown as { daysStale: number | null }).daysStale
      return n != null && n > 3
    },
  },
  actions: {
    setDataAsOf(date: string) {
      this.dataAsOf = date
      this._asOfLoaded = true
    },
    /**
     * 直达非驾驶舱页面时,惰性补一次「数据截至」真值(复用库存快照的 dataAsOf,
     * 与驾驶舱同一真相源)。失败静默——水印退化为「尚未导入」不报错。
     */
    async loadDataAsOf(force = false) {
      if (this._asOfLoaded && !force) return
      this._asOfLoaded = true
      try {
        const s = await reportApi.stock()
        if (s.dataAsOf) this.dataAsOf = s.dataAsOf.slice(0, 10)
      } catch {
        /* 后端不可用:保持 null,水印显示「尚未导入」 */
      }
    },
  },
})
