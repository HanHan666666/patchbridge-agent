# vendor 目录：第三方前端依赖

所有库本地化存放，保证 demo 可离线运行、行为可复现。其中带全局样式
（reset / base / typography）的库在 `index.html` 中声明为 `disabled`，
只在切换到对应主题时由页面脚本启用，避免影响其他主题；特效类
（如 `cyber-scanlines`、`nes-btn`、`rpgui-container`）常驻在页面元素上，
样式表未启用时这些类天然失效。升级时用新版本整文件替换，并同步更新本页版本号。

## cybercore.css

- 来源：<https://github.com/sebyx07/cybercore-css>（npm 包 `cybercore-css`）
- 版本：0.3.0（unpkg `cybercore-css@0.3.0` 的 `dist/cybercore.css` 完整版，未做任何修改）
- 许可证：MIT，全文见 [cybercore-LICENSE.txt](cybercore-LICENSE.txt)
- 用途：赛博朋克主题的页面特效——全页扫描线（`cyber-scanlines`）、标题故障字
  （`cyber-glitch`）、AI 面板霓虹流光外框（`cyber-neon-border`），
  以及 `--cyber-*` / `--font-*` 色板变量（`theme-cyberpunk.css` 的令牌取值来源）。

## nes.css

- 来源：<https://github.com/nostalgic-css/NES.css>（npm 包 `nes.css`）
- 版本：2.3.0（unpkg `nes.css@2.3.0` 的 `css/nes.min.css`，未做任何修改）
- 许可证：MIT，全文见 [NES.css-LICENSE.txt](NES.css-LICENSE.txt)
- 用途：NES 像素主题——页签上的 `nes-btn` 像素按钮、卡片上的 `nes-container`
  像素黑框，色板（蓝 `#209cee` / 红 `#e76e55` / 黄 `#f7d51d` / 黑 `#212529`）
  作为 `theme-nes.css` 的令牌取值来源。

## rpgui/

- 来源：<https://github.com/RonenNess/RPGUI>（dist 目录，含 CSS 与全部图片素材）
- 版本：master 分支（仓库未发布版本号；`rpgui.min.css` + `img/` 为官方 dist 原样拷贝）
- 许可证：zlib，全文见 [rpgui/RPGUI-LICENSE.txt](rpgui/RPGUI-LICENSE.txt)
- 用途：奇幻 RPG 主题——侧栏卡片挂 `rpgui-container framed-golden` 容器类
  （金色浮雕边框 + 羊皮纸底），页面光标使用它的指针素材，AI 面板经
  `::part(panel)` 复用它的 `border-image-golden.png` 获得同款浮雕框。
  只用其 CSS；`rpgui.js`（下拉框 / 滑块等交互行为）本 Demo 不需要，未引入。

## press-start-2p.css / press-start-2p.woff2

- 来源：Google Fonts 项目的 Press Start 2P（经 @fontsource 分发的 woff2 文件）
- 许可证：SIL Open Font License 1.1，全文见 [PressStart2P-OFL-LICENSE.txt](PressStart2P-OFL-LICENSE.txt)
- 用途：NES 与 RPG 两个主题共用的像素字体。常驻加载无副作用：页面没有元素
  使用该字体族时，浏览器不会下载 woff2 文件。
