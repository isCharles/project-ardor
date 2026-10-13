// Dedicated test bootstrap: production main/preload, isolated from installed app data.
const { app, net } = require("electron");
const path = require("node:path");
const os = require("node:os");
const fs = require("node:fs");
const profile = process.env.ARDOR_CHROME_TEST_DIR;
if (!profile || !fs.realpathSync.native(profile).toLowerCase().startsWith(fs.realpathSync.native(os.tmpdir()).toLowerCase() + path.sep) || !path.basename(profile).startsWith("ardor-chrome-test-")) { console.error("Isolated temporary profile required"); app.exit(1); }
app.setPath("userData", process.env.ARDOR_CHROME_TEST_DIR);
// This UI test has no backend. Keep the unchanged server health probe synthetic,
// just like renderer APIs, while loading the real built frontend over HTTP.
net.fetch = async (url) => {
  if (url === "http://127.0.0.1:3101/") return { ok: true, status: 200 };
  if (url === "http://127.0.0.1:3101/api/auth/me") return { ok: false, status: 401 };
  throw new Error("Unexpected test health probe");
};
app.on("browser-window-created", (_event, window) => window.once("ready-to-show", () => window.minimize()));
require("../main.cjs");
