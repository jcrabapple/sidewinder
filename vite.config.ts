// Dev server binds 0.0.0.0 + HTTPS (Jason's standing rule for dev servers),
// so it is reachable from other devices on the LAN (phones, tablets).
import { defineConfig } from 'vite'
import preact from '@preact/preset-vite'
import basicSsl from '@vitejs/plugin-basic-ssl'

export default defineConfig({
  plugins: [preact(), basicSsl()],
  server: {
    host: '0.0.0.0',
    port: 5180,
  },
  build: {
    target: 'es2022',
    sourcemap: true,
  },
})
