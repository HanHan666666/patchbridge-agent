# Third-Party Notices

PatchBridge Agent 本身使用根目录 [MIT License](LICENSE)。Demo 为离线展示主题而提交了以下第三方前端资源；这些资源保留各自许可证，不因项目主许可证而改变。

| 资源 | 已记录版本/来源 | 许可证 | 仓库内许可证 | 修改情况 |
| --- | --- | --- | --- | --- |
| cybercore.css | cybercore-css 0.3.0，<https://github.com/sebyx07/cybercore-css> | MIT | [cybercore-LICENSE.txt](java/patchbridge-agent-demo/src/main/resources/static/css/vendor/cybercore-LICENSE.txt) | 上游 dist 原样复制 |
| NES.css | nes.css 2.3.0，<https://github.com/nostalgic-css/NES.css> | MIT | [NES.css-LICENSE.txt](java/patchbridge-agent-demo/src/main/resources/static/css/vendor/NES.css-LICENSE.txt) | 上游 minified CSS 原样复制 |
| RPGUI | RPGUI dist，<https://github.com/RonenNess/RPGUI> | zlib | [RPGUI-LICENSE.txt](java/patchbridge-agent-demo/src/main/resources/static/css/vendor/rpgui/RPGUI-LICENSE.txt) | CSS 与图片素材原样复制，未引入 rpgui.js |
| Press Start 2P | Google Fonts 字体，经 @fontsource 分发 | SIL Open Font License 1.1 | [PressStart2P-OFL-LICENSE.txt](java/patchbridge-agent-demo/src/main/resources/static/css/vendor/PressStart2P-OFL-LICENSE.txt) | woff2 与本地 @font-face 声明 |

完整文件清单、用途和加载方式见 [vendor/README.md](java/patchbridge-agent-demo/src/main/resources/static/css/vendor/README.md)。许可证正文必须与对应资源一起保留。

## Provenance Notes

当前历史记录没有保存 RPGUI 的上游 commit，也没有保存 Press Start 2P 的 @fontsource 包版本。v0.1 源码候选通过 Git commit 固定实际 vendored bytes；以后升级这两项资源时，必须先记录精确上游版本或 commit、下载来源和校验值，再替换文件及同步本清单。

本文件是第三方依赖索引，不替代各许可证正文，也不覆盖 Java/npm 构建依赖的许可证。公共 Maven/npm 二进制发布不属于 v0.1 R0 范围；进入公共包发布阶段时必须对实际 tarball/JAR 重新生成并审核分发物级许可证清单。
