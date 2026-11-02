// 打包 Windows 版：node pack.js [輸出資料夾]
const { packager } = require('@electron/packager')
const path = require('path')
const fs = require('fs')

const out = process.argv[2] || 'dist'
const version = process.env.APP_VERSION || require('./package.json').version

packager({
  dir: __dirname,
  name: 'MPlayer',
  platform: 'win32',
  arch: 'x64',
  out,
  overwrite: true,
  // ffmpeg.exe 不能放在 asar 裡執行
  asar: { unpack: '**/*.exe' },
  icon: path.join(__dirname, 'src', 'icon.ico'),
  appVersion: version,
  win32metadata: { CompanyName: 'MPlayer', FileDescription: 'MPlayer', ProductName: 'MPlayer' },
  // 只排除專案根目錄的東西（路徑以 / 開頭、相對於專案資料夾）
  ignore: [
    /^\/dist($|\/)/,
    /^\/out($|\/)/,
    /^\/downloads($|\/)/,
    /^\/tools($|\/)/,
    /^\/pack\.js$/,
    /^\/\.gitignore$/,
    // hls.js 附了十幾種建置版本和原始碼（約 20 MB），只需要 hls.min.js
    /^\/node_modules\/hls\.js\/(?!package\.json$|dist$|dist\/hls\.min\.js$)/,
  ],
})
  .then(([appPath]) => {
    // Chromium 介面語言檔只留中文和英文（其他語言約 40 MB，程式用不到）
    const keep = new Set(['en-US.pak', 'zh-TW.pak', 'zh-CN.pak'])
    const locales = path.join(appPath, 'locales')
    for (const f of fs.readdirSync(locales)) if (!keep.has(f)) fs.rmSync(path.join(locales, f))
    fs.writeFileSync(path.join(appPath, 'version.txt'), version)
    console.log(`打包完成：${appPath}（v${version}）`)
  })
  .catch((err) => {
    console.error(err)
    process.exit(1)
  })
