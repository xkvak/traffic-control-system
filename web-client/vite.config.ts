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
      "/turnstile": {
        target: turnstileProxyTarget,
      },
      "/.well-known": {
        target: turnstileProxyTarget,
      },
    },
  },
});
