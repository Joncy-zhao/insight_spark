import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  build: {
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes('node_modules')) return undefined
          if (id.includes('element-plus')) return 'vendor-element-plus'
          if (id.includes('zrender')) return 'vendor-zrender'
          if (id.includes('echarts')) return 'vendor-echarts'
          if (id.includes('jspdf') || id.includes('html2canvas') || id.includes('dompurify')) return 'vendor-export'
          if (id.includes('vue')) return 'vendor-vue'
          return 'vendor'
        }
      }
    },
    chunkSizeWarningLimit: 1200
  },
  server: {
    host: true
  },
})
