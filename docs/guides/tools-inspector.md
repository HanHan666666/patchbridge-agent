# 使用 Tools Inspector

## 使用与展示

~~~html
<script src="/ai/assets/patchbridge-agent-tool-inspector.js"></script>
<patchbridge-agent-tool-inspector id="tool-inspector"></patchbridge-agent-tool-inspector>
<script>
  const inspector = document.querySelector('#tool-inspector');
  document.querySelector('patchbridge-agent')
    .addEventListener('patchbridge-agent-ready', event => {
      inspector.toolSource = event.detail.toolInspectionSource;
    });
</script>
~~~

Inspector 只读展示名称、描述、来源、注记、Schema、revision 和 snapshot scope。空闲时展示当前 Registry；Execution 期间固定展示本轮实际快照。它不执行 Tool，也不会改变运行中的能力集合。OPENAPI 是预留来源，v0.1 没有对应 Provider。

---

## 设计说明与视觉定制

Inspector 是独立包和独立静态资源，默认 Widget 不依赖它：

```html
<script src="/ai/assets/patchbridge-agent-tool-inspector.js"></script>
<patchbridge-agent-tool-inspector id="tools"></patchbridge-agent-tool-inspector>
```

把 Widget ready 事件里的执行感知只读源交给它；Registry 仍保留给页面注册 Tool 和
WebMCP Adapter：

```js
document.addEventListener('patchbridge-agent-ready', event => {
  document.getElementById('tools').toolSource = event.detail.toolInspectionSource;
});
```

它会只读展示名称、标题、描述、来源、确认提示、JSON Schema、revision，以及“本轮已
冻结 / 当前目录”范围，没有 Tool 执行按钮。空闲时初次绑定会刷新一次 Registry；执行中
只返回本轮快照，Inspector 不能改变 Agent 能力。删除该脚本、标签和 Demo 页签即可从
生产环境完全移除，不需要修改 Agent、Controller 或 Widget。

Inspector 也不强制宿主视觉：可用 `--patchbridge-agent-tools-accent-color`、
`--patchbridge-agent-tools-background`、`--patchbridge-agent-tools-border-color`、
`--patchbridge-agent-tools-radius` 等变量改参考主题，或用 `panel`、`header`、`list`、
`tool`、`source`、`name`、`schema`、`empty`、`error` 这些 Shadow Parts 覆盖语义节点。
Demo 将强调色改为深蓝，就是这种宿主侧覆盖的最小示例。

Inspector 不按来源过滤，因此看到的是 Agent 当前的完整能力：后端原生、OpenAPI、远程 MCP、纯前端和 WebMCP。若某个来源加载失败，组件显示错误并保留上一次成功快照。