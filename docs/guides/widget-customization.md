# 定制默认 Widget 样式

## 参考主题与样式入口

Widget 提供三层稳定入口：

1. <code>--patchbridge-agent-*</code> CSS Variables 调整颜色、字体、间距、尺寸和圆角；
2. <code>::part()</code> 覆盖 panel、header、message、composer、input、send-button、reasoning、max-tokens-notice 等语义节点；
3. <code>theme="none"</code> 移除参考视觉主题。

不要依赖 Shadow DOM 内部 class。需要改变 DOM 或交互结构时，使用 Headless Controller 自建 View。

---

## 设计说明与完整令牌

默认 `<patchbridge-agent>` 是参考 View，不是框架强制主题。建议按改动深度选择三层 API。

### CSS Variables：修改常用视觉令牌

```css
patchbridge-agent {
  --patchbridge-agent-color-primary: #00695c;
  --patchbridge-agent-color-background: #fcfffe;
  --patchbridge-agent-color-text: #10201d;
  --patchbridge-agent-font-family: Inter, sans-serif;
  --patchbridge-agent-radius-panel: 0;
  --patchbridge-agent-conversation-list-width: 240px;
}
```

### `::part`：覆盖稳定语义节点

```css
patchbridge-agent::part(header) {
  border-bottom: 2px solid currentColor;
}

patchbridge-agent::part(user-message) {
  box-shadow: none;
}

patchbridge-agent::part(send-button) {
  min-width: 88px;
}
```

公共 Parts 包括 `panel`、`header`、`conversation-list`、`messages`、`message`、`user-message`、`assistant-message`、`tool-message`、`status`、`composer`、`input` 与 `send-button` 等。内部 class 名不属于兼容性承诺。

### `theme="none"`：完全移除参考视觉主题

```html
<patchbridge-agent endpoint="/ai" theme="none"></patchbridge-agent>
```

`theme="none"` 不再注入默认颜色、字体、间距和圆角，只保留布局、滚动、交互状态和键盘焦点等基础行为。若连 DOM 结构和交互方式也需要改变，应直接使用 Headless `@patchbridge-agent/agent`，不应穿透 Shadow DOM 依赖内部实现。

完整令牌与 Parts 清单见 [`web/packages/widget/README.md`](../../web/packages/widget/README.md)。