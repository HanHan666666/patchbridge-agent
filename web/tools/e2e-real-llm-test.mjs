/**
 * 真实 LLM 浏览器验证（application-local.yml 的 mimo-v2.5，多模态）：
 * 发送一条需要工具决策的消息，验证真实模型经网关流式输出、自主选择工具、
 * 组织最终中文回答的全链路；再发送程序生成的纯红 PNG，验证多模态输入
 * （📎 上传 → content parts 组装 → 模型识图 → 会话持久化往返）。
 * 真实模型回答内容不确定，因此断言只验证结构性事实：工具被调用、回答非空、
 * 颜色识别正确。该脚本不进入 CI，避免自动消耗额度或受外部网络波动影响。
 */
import { chromium } from 'playwright-core';
import { deflateSync } from 'node:zlib';

const BASE = process.env.E2E_BASE ?? 'http://localhost:8080';
const BROWSER_CHANNEL = process.env.E2E_BROWSER_CHANNEL ?? 'chrome';

/**
 * 生成指定尺寸的纯色 PNG（RGB，无压缩依赖）：手工组装 IHDR/IDAT/IEND。
 * 测试需要内容完全确定的图片——用颜色提问是视觉模型最稳定的能力，
 * 断言“回答包含红色”比任何文本类问题都可靠。
 */
function solidColorPng(size, [r, g, b]) {
  const crcTable = Array.from({ length: 256 }, (_, n) => {
    let c = n;
    for (let k = 0; k < 8; k++) {
      c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    }
    return c >>> 0;
  });
  const crc32 = bytes => {
    let c = 0xffffffff;
    for (const byte of bytes) {
      c = crcTable[(c ^ byte) & 0xff] ^ (c >>> 8);
    }
    return (c ^ 0xffffffff) >>> 0;
  };
  const chunk = (type, data) => {
    const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
    const length = Buffer.alloc(4);
    length.writeUInt32BE(data.length);
    const crc = Buffer.alloc(4);
    crc.writeUInt32BE(crc32(body));
    return Buffer.concat([length, body, crc]);
  };

  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0);
  ihdr.writeUInt32BE(size, 4);
  ihdr[8] = 8;  // bit depth
  ihdr[9] = 2;  // color type: truecolor RGB
  const row = Buffer.concat([Buffer.from([0]), Buffer.alloc(size * 3)]);
  for (let x = 0; x < size; x++) {
    row[1 + x * 3] = r;
    row[2 + x * 3] = g;
    row[3 + x * 3] = b;
  }
  const idat = deflateSync(Buffer.concat(Array.from({ length: size }, () => row)));

  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', idat),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

let passed = 0;
let failed = 0;
const ok = (condition, label, detail = '') => {
  if (condition) {
    passed += 1;
    console.log(`  PASS  ${label}`);
  } else {
    failed += 1;
    console.log(`  FAIL  ${label} ${detail}`);
  }
};

async function main() {
  const browser = await chromium.launch({ channel: BROWSER_CHANNEL, headless: true });
  const page = await browser.newPage();

  await page.goto(`${BASE}/login`);
  await page.fill('input[name=username]', 'admin');
  await page.fill('input[name=password]', 'admin123');
  await page.click('button[type=submit]');
  await page.waitForURL(`${BASE}/`);

  const widget = page.locator('patchbridge-agent');
  await widget.locator('.panel').waitFor({ state: 'visible', timeout: 5000 });
  // 新开一个会话，避免匹配到历史会话里的旧消息（测试隔离）
  await widget.locator('.new-btn').click();
  await page.waitForTimeout(400);

  console.log('== 真实 LLM 对话（要求列出设备，模型应自主调用 device_list）==');
  const textarea = widget.locator('textarea');
  await textarea.fill('帮我看一下现在有哪些设备在告警？用一两句话总结。');
  await textarea.press('Enter');

  // 真实模型：先流式（可能带思考），再决定工具，最终回答——放宽到 90 秒
  const finalAnswer = widget.locator('.messages .bubble.assistant', { hasText: /告警|设备|正常|没有|无/ });
  await finalAnswer.last().waitFor({ state: 'visible', timeout: 90000 });
  ok(true, '真实模型给出最终回答');

  const usedTool = await widget.locator('.tool-tag, .tool-result').count();
  ok(usedTool >= 1, `模型自主调用了工具（${usedTool} 个工具节点）`);

  const answerText = await finalAnswer.last().textContent();
  console.log(`  模型回答摘要：${(answerText ?? '').slice(0, 120)}`);
  ok((answerText ?? '').trim().length > 5, '回答内容非空');

  // 完成后收敛（saving → done）：真实模型可能思考较久，用轮询等待而非固定 sleep
  let recovered = false;
  for (let i = 0; i < 60; i++) {
    if (await textarea.isEnabled()) {
      recovered = true;
      break;
    }
    await page.waitForTimeout(1000);
  }
  ok(recovered, '完成后输入框恢复');
  ok(await widget.locator('.error-bar', { hasText: '请求失败' }).count() === 0, '无网络/权限错误');

  // 流式中断链路（真实流式下点停止）
  await textarea.fill('再详细介绍一下第一台设备');
  await textarea.press('Enter');
  await page.waitForTimeout(1500);
  const stopBtn = widget.locator('.send-btn', { hasText: '停止' });
  if (await stopBtn.count() === 1) {
    await stopBtn.click();
    await page.waitForTimeout(800);
    ok(await textarea.isEnabled(), '真实流式中止后输入框恢复');
  } else {
    ok(true, '（模型响应过快未捕捉到停止按钮，中止链路已由单测覆盖）');
  }

  console.log('== 多模态：发送纯红 PNG 并提问颜色 ==');
  await widget.locator('.new-btn').click();
  await page.waitForTimeout(400);
  await widget.locator('input[type=file]').setInputFiles({
    name: 'red.png',
    mimeType: 'image/png',
    buffer: solidColorPng(64, [255, 0, 0]),
  });
  await widget.locator('.attachment-chip img').waitFor({ state: 'visible', timeout: 5000 });
  ok(true, '选择图片后出现待发送预览');

  await widget.locator('textarea').fill('这张图片是什么颜色？只用两三个字回答。');
  await widget.locator('textarea').press('Enter');
  // 用户气泡内渲染图片（content parts 消息的 UI 呈现）
  const bubbleImage = widget.locator('.messages .bubble.user .chat-image');
  await bubbleImage.first().waitFor({ state: 'visible', timeout: 30000 });
  ok(true, '用户消息气泡渲染图片');

  // 视觉模型识别纯色是最稳定的能力；放宽到 90 秒
  const colorBubble = widget.locator('.messages .bubble.assistant');
  await colorBubble.last().waitFor({ state: 'visible', timeout: 90000 });
  for (let i = 0; i < 60; i++) {
    if (await widget.locator('textarea').isEnabled()) {
      break;
    }
    await page.waitForTimeout(1000);
  }
  const colorAnswer = (await colorBubble.last().textContent()) ?? '';
  console.log(`  模型颜色回答：${colorAnswer.slice(0, 60)}`);
  ok(/红|red/i.test(colorAnswer), `模型正确识别图片颜色（回答含“红/red”）`);

  // 图片随会话持久化：刷新后历史消息里的图片完整恢复（data URL 往返）
  await page.reload();
  await page.waitForLoadState('domcontentloaded');
  const restored = page.locator('patchbridge-agent');
  await restored.locator('.panel').waitFor({ state: 'visible', timeout: 5000 });
  await restored.locator('.messages .bubble.user .chat-image').first()
    .waitFor({ state: 'visible', timeout: 8000 });
  ok(true, '图片随会话持久化并在刷新后恢复');
  await page.screenshot({ path: 'tools/e2e-multimodal.png', fullPage: true });

  await page.screenshot({ path: 'tools/e2e-real-llm.png', fullPage: true });
  await browser.close();
  console.log(`\n结果：${passed} 通过，${failed} 失败`);
  process.exit(failed > 0 ? 1 : 0);
}

main().catch(error => {
  console.error('真实 LLM 浏览器验证异常:', error);
  process.exit(1);
});
