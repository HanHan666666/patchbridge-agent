/**
 * 纯 JSON 值的复制与冻结入口。
 *
 * <p>Tool 参数、JSON Schema 与 ModelState 会跨越 Registry、Runtime、Provider 和 Hook
 * 边界。所有需要运行时不可变保证的调用都集中到这里，避免各层实现不同深度的复制规则。
 */
import type { JsonObject, JsonValue } from './types';

/** 递归复制并冻结 JSON 值，切断宿主或 Adapter 持有的全部嵌套引用。 */
export function copyAndFreezeJsonValue(value: JsonValue): JsonValue {
  if (value == null || typeof value === 'string' || typeof value === 'boolean') {
    return value;
  }
  if (typeof value === 'number') {
    if (!Number.isFinite(value)) {
      throw new Error('JSON 数字必须是有限值');
    }
    return value;
  }
  if (Array.isArray(value)) {
    return Object.freeze(value.map(copyAndFreezeJsonValue));
  }
  if (typeof value === 'object') {
    const prototype = Object.getPrototypeOf(value);
    if (prototype !== Object.prototype && prototype !== null) {
      throw new Error('JSON 对象不能包含自定义原型');
    }
    const copied: Record<string, JsonValue> = {};
    for (const [key, child] of Object.entries(value)) {
      copied[key] = copyAndFreezeJsonValue(child);
    }
    return Object.freeze(copied);
  }
  throw new Error(`不支持的 JSON 值类型: ${typeof value}`);
}

/** 复制并冻结已知为 JSON 对象的值，同时保留 JsonObject 静态类型。 */
export function copyAndFreezeJsonObject(value: JsonObject): JsonObject {
  return copyAndFreezeJsonValue(value) as JsonObject;
}
