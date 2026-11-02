// 程式自己的資料要放哪：以程式所在的資料夾為主（和 MPlayer.exe 放一起），
// 不要散到「下載」這種跟程式無關的地方。
//   打包版：MPlayer.exe 所在的資料夾
//   開發時：desktop 專案資料夾
// 程式資料夾不能寫（例如裝在 Program Files）時，退回 userData（%APPDATA%\MPlayer）。
const { app } = require('electron')
const fs = require('original-fs')
const path = require('path')

let cachedRoot = null

function canWrite(dir) {
  try {
    fs.mkdirSync(dir, { recursive: true })
    const probe = path.join(dir, `.write-test-${process.pid}`)
    fs.writeFileSync(probe, '')
    fs.unlinkSync(probe)
    return true
  } catch {
    return false
  }
}

/** 程式資料夾（可寫才用），否則 userData */
function root() {
  if (cachedRoot) return cachedRoot
  const appDir = app.isPackaged ? path.dirname(process.execPath) : __dirname
  cachedRoot = canWrite(appDir) ? appDir : app.getPath('userData')
  return cachedRoot
}

/** 下載的影片預設放這裡 */
const defaultDownloadDir = () => path.join(root(), 'downloads')

module.exports = { root, defaultDownloadDir }
