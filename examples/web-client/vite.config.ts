import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

const apiProxyTarget =
  process.env.VITE_API_PROXY_TARGET || "http://localhost:8080";
const turnstileProxyTarget =
  process.env.VITE_TURNSTILE_PROXY_TARGET || "http://localhost:8083";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/api": {
        target: apiProxyTarget,
      },
      // 대기열 API. 화면의 대기 페이지는 이 경로와 겹치지 않게 /waiting을 쓴다
      "/queue": {
        target: turnstileProxyTarget,
      },
    },
  },
});
