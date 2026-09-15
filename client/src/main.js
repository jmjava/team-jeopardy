import { createApp } from 'vue'
import App from './App.vue'
import './styles.css'
import { applyTheme } from './theme'

applyTheme()
createApp(App).mount('#app')
