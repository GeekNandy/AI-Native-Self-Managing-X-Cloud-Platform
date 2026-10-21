const { defineConfig } = require("@playwright/test");

module.exports = defineConfig({
  testDir: "./tests/e2e",
  workers: 1,
  retries: 0,
  timeout: 30000,
  expect: { timeout: 10000 },
  reporter: [["list"], ["html", { open: "never" }]],
  use: {
    baseURL: "http://127.0.0.1:8090",
    viewport: { width: 1440, height: 1000 },
    timezoneId: "Europe/Berlin",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    launchOptions: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH
      ? { executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH }
      : {},
  },
  webServer: {
    command:
      "java -jar target/AI-Native-Self-Managing-X-Cloud-Platform-0.0.1-SNAPSHOT.jar --server.port=8090 --platform.ai.provider=fixture",
    url: "http://127.0.0.1:8090/actuator/health",
    timeout: 60000,
    reuseExistingServer: false,
    env: { PLATFORM_AI_PROVIDER: "fixture", PLATFORM_DATA_DIR: "memory", PLATFORM_OPERATOR_TOKEN: "e2e-operator-token-0123456789", PLATFORM_AGENT_TOKEN: "e2e-agent-token-0123456789" },
  },
});
