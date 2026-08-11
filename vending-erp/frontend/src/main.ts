import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import 'virtual:uno.css'
import '@/styles/ledger.css'

import App from './App.vue'
import router from './router'
import NumberField from '@/components/common/NumberField.vue'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })
// el-input-number 在本项目渲染异常(输入区塌陷只剩 −/+,数字录不进,全站金额/期初/参数/容量录入受影响)。
// 全局把它替换成可靠的 NumberField(el-input type=number 实现,对外 v-model 仍是数字),
// 所有 <el-input-number> 无需改动即生效。根因(疑运行时渲染问题)待浏览器调试工具恢复后再深挖。
app.component('ElInputNumber', NumberField)
app.mount('#app')
