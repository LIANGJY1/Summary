const fs = require("fs")
const path = require("path")

const scriptPath = fs.realpathSync("/home/liang/.local/bin/opencode")
const scriptDir = path.dirname(scriptPath)

const cached = path.join(scriptDir, ".opencode")
if (fs.existsSync(cached)) {
  console.log("Cached:", cached)
}
