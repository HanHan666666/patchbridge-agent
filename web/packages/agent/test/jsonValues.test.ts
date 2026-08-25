/** JSON 值快照测试：验证深冻结与非法运行时对象拒绝规则。 */
import { describe, expect, it } from 'vitest';
import { copyAndFreezeJsonValue } from '../src/jsonValues';
import type { JsonValue } from '../src/types';

describe('copyAndFreezeJsonValue', () => {
  it('递归复制并冻结对象和数组，不共享宿主嵌套引用', () => {
    const nested = ['before'];
    const source = { nested };

    const snapshot = copyAndFreezeJsonValue(source) as {
      readonly nested: readonly string[];
    };
    nested.push('after');

    expect(snapshot.nested).toEqual(['before']);
    expect(Object.isFrozen(snapshot)).toBe(true);
    expect(Object.isFrozen(snapshot.nested)).toBe(true);
  });

  it('拒绝非有限数字和伪装成 JsonValue 的运行时对象', () => {
    expect(() => copyAndFreezeJsonValue(Number.NaN)).toThrow('有限值');
    expect(() => copyAndFreezeJsonValue(new Date() as unknown as JsonValue))
      .toThrow('自定义原型');
    expect(() => copyAndFreezeJsonValue((() => undefined) as unknown as JsonValue))
      .toThrow('不支持的 JSON 值类型');
  });
});
