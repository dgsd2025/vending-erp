import { defineStore } from 'pinia'

/**
 * 全局应用状态。
 * dataAsOf:全局铁律「数据新鲜度一等公民」——所有页面顶部水印显示的数据截止日,
 * 后续由导入中心/后端接口写入,现在是占位。
 */
export const useAppStore = defineStore('app', {
  state: () => ({
    /** 数据截至日期(YYYY-MM-DD),null = 尚无数据 */
    dataAsOf: null as string | null,
  }),
  actions: {
    setDataAsOf(date: string) {
      this.dataAsOf = date
    },
  },
})
