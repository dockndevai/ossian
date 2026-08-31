import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // Proxy /api to the backend so the browser sees one origin in dev. Auth still uses a
    // bearer token rather than cookies, so this is convenience, not a security boundary.
    proxy: {
      "/api": { target: "http://localhost:8081", changeOrigin: true },
    },
  },
});
