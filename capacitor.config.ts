import type { CapacitorConfig } from "@capacitor/cli"

const config: CapacitorConfig = {
  appId: "ai.opencode.app",
  appName: "OpenCode",
  webDir: "www",
  server: {
    androidScheme: "http",
  },
  plugins: {
    StatusBar: {
      style: "DARK",
    },
    Keyboard: {
      resize: "body",
      resizeOnFullScreen: true,
    },
  },
}

export default config
