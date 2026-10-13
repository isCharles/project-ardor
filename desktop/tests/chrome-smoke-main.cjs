// Dedicated test bootstrap: production main/preload, isolated from installed app data.
const { app } = require("electron");
const path = require("node:path");
const os = require("node:os");
if (!process.env.ARDOR_CHROME_TEST_DIR || !path.resolve(process.env.ARDOR_CHROME_TEST_DIR).startsWith(path.resolve(os.tmpdir()) + path.sep)) throw new Error("Isolated temporary profile required");
app.setPath("userData", process.env.ARDOR_CHROME_TEST_DIR);
app.on("browser-window-created", (_event, window) => window.hide());
require("../main.cjs");
