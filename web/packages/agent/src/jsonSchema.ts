/**
 * 纯前端 Tool inputSchema 的受支持子集定义与执行前参数校验。
 *
 * <p>设计原因（二次审计 Q-03）：模型生成的参数属于不可信输入，公共契约承诺
 * "模型只能生成 inputSchema 声明的业务参数"。本模块把该承诺落在统一执行边界：
 * 注册阶段编译 Schema 并拒绝任何不受支持的关键字（明确失败而不是静默忽略），
 * 执行阶段在进入页面 execute() 之前校验参数，非法参数形成明确的 Tool 失败结果。
 *
 * <p>受支持子集（除此之外的关键字一律在注册时失败）：
 * type（含类型联合）、properties、required、additionalProperties（仅布尔）、
 * enum（原始值数组）、items（数组元素 Schema）、title/description（注释性，忽略）。
 * 根 Schema 必须是 object 类型：Function Calling 的参数载体固定为 JSON 对象。
 */
import type { JsonObject, JsonValue } from './types';

/** 参数校验问题：path 定位到具体参数位置，message 面向模型可自纠错的中文描述。 */
export interface ToolArgumentIssue {
  /** 参数定位路径，如 "arguments.section" 或 "arguments.items[0]"。 */
  readonly path: string;
  /** 违反的约束描述。 */
  readonly message: string;
}

/** 编译后的参数校验器；validate 返回 null 表示参数完全合法。 */
export interface CompiledToolInputSchema {
  validate(arguments_: JsonObject): ToolArgumentIssue | null;
}

/** 子集允许出现的全部关键字；注释性关键字在编译时直接忽略。 */
const ANNOTATION_KEYS = new Set(['title', 'description']);

/** 子集支持的类型关键字取值。 */
const SUPPORTED_TYPES = new Set([
  'object',
  'array',
  'string',
  'number',
  'integer',
  'boolean',
  'null',
]);

/** 单个 Schema 节点编译后的校验闭包。 */
type NodeValidator = (value: JsonValue, path: string) => ToolArgumentIssue | null;

/** 原始值判断：enum 取值只允许原始类型，保证比较语义确定。 */
function isJsonPrimitive(value: JsonValue): boolean {
  return typeof value === 'string'
    || typeof value === 'number'
    || typeof value === 'boolean'
    || value === null;
}

/**
 * 编译 Tool inputSchema；不受支持的结构立即抛错。
 *
 * @param schema 页面声明的输入 Schema（注册方保证是对象）
 * @param toolName 用于错误信息的 Tool 名
 */
export function compileToolInputSchema(
  schema: JsonObject,
  toolName: string,
): CompiledToolInputSchema {
  const root = compileNode(schema, 'inputSchema', toolName);
  if (!root.expectsObject) {
    throw new Error(
      `${toolName} inputSchema 根类型必须是 object：Function Calling 参数载体固定为 JSON 对象`,
    );
  }
  return {
    validate: (arguments_: JsonObject) => {
      // 运行期防御：快照调用方类型上承诺 JsonObject，边界处仍校验真实形态。
      if (typeof arguments_ !== 'object' || arguments_ === null || Array.isArray(arguments_)) {
        return {
          path: 'arguments',
          message: 'Tool 参数必须是 JSON 对象',
        };
      }
      return root.validate(arguments_, 'arguments');
    },
  };
}

/** 编译中间产物：额外携带根节点是否为 object 的判定。 */
interface CompiledNode {
  validate: NodeValidator;
  /** 是否声明了 object 类型（用于根 Schema 检查）。 */
  expectsObject: boolean;
}

/** 递归编译单个 Schema 节点；遇到不支持的关键字或非法取值立即抛错。 */
function compileNode(schema: JsonObject, path: string, toolName: string): CompiledNode {
  for (const key of Object.keys(schema)) {
    if (
      !ANNOTATION_KEYS.has(key)
      && key !== 'type'
      && key !== 'properties'
      && key !== 'required'
      && key !== 'additionalProperties'
      && key !== 'enum'
      && key !== 'items'
    ) {
      throw new Error(
        `${toolName} ${path} 使用了不受支持的关键字 "${key}"：受支持子集为 type/properties/required/additionalProperties/enum/items`,
      );
    }
  }

  const types = compileTypes(schema, path, toolName);
  const enumValidator = compileEnum(schema, path, toolName);
  const propertiesValidator = types.has('object')
    ? compileProperties(schema, path, toolName)
    : null;
  if (propertiesValidator == null) {
    for (const key of ['properties', 'required', 'additionalProperties']) {
      if (key in schema) {
        throw new Error(
          `${toolName} ${path} 声明了 "${key}"，但类型不包含 object`,
        );
      }
    }
  }
  const itemValidator = types.has('array') ? compileItems(schema, path, toolName) : null;
  if (itemValidator == null && 'items' in schema) {
    throw new Error(`${toolName} ${path} 声明了 "items"，但类型不包含 array`);
  }

  return {
    expectsObject: types.has('object'),
    validate: (value, valuePath) => {
      if (!matchesTypes(types, value)) {
        return { path: valuePath, message: `类型必须是 ${[...types].join(' | ')}` };
      }
      if (enumValidator != null && !enumValidator.allowedValues.includes(value)) {
        return {
          path: valuePath,
          message: `取值必须在 enum 声明的范围内: ${JSON.stringify(enumValidator.allowedValues)}`,
        };
      }
      if (propertiesValidator != null && value !== null && typeof value === 'object' && !Array.isArray(value)) {
        const propertyIssue = propertiesValidator.validate(value, valuePath);
        if (propertyIssue != null) {
          return propertyIssue;
        }
      }
      if (itemValidator != null && Array.isArray(value)) {
        for (let index = 0; index < value.length; index += 1) {
          const issue = itemValidator.validate(value[index] ?? null, `${valuePath}[${index}]`);
          if (issue != null) {
            return issue;
          }
        }
      }
      return null;
    },
  };
}

/** 编译并校验 type 关键字；返回受支持类型的集合。 */
function compileTypes(schema: JsonObject, path: string, toolName: string): Set<string> {
  if (!('type' in schema)) {
    throw new Error(`${toolName} ${path} 缺少 "type" 声明：子集要求每个节点显式声明类型`);
  }
  const declared = schema.type;
  const list = Array.isArray(declared) ? declared : [declared];
  if (list.length === 0) {
    throw new Error(`${toolName} ${path} 的 type 不能为空数组`);
  }
  const types = new Set<string>();
  for (const entry of list) {
    if (typeof entry !== 'string' || !SUPPORTED_TYPES.has(entry)) {
      throw new Error(
        `${toolName} ${path} 的 type 取值不受支持: ${JSON.stringify(entry)}`,
      );
    }
    types.add(entry);
  }
  return types;
}

/** 编译 enum 关键字；取值必须是原始 JSON 值。 */
function compileEnum(schema: JsonObject, path: string, toolName: string)
  : { allowedValues: readonly JsonValue[] } | null {
  if (!('enum' in schema)) {
    return null;
  }
  const values = schema.enum;
  if (!Array.isArray(values) || values.length === 0) {
    throw new Error(`${toolName} ${path} 的 enum 必须是非空数组`);
  }
  for (const value of values) {
    if (!isJsonPrimitive(value)) {
      throw new Error(
        `${toolName} ${path} 的 enum 取值必须是原始值: ${JSON.stringify(value)}`,
      );
    }
  }
  return { allowedValues: values };
}

/** 编译 object 节点的属性表、必填与额外参数策略。 */
function compileProperties(schema: JsonObject, path: string, toolName: string)
  : { validate: NodeValidator } {
  const properties = schema.properties;
  if (properties !== undefined
    && (typeof properties !== 'object' || properties === null || Array.isArray(properties))) {
    throw new Error(`${toolName} ${path} 的 properties 必须是对象`);
  }
  const required = schema.required;
  if (required !== undefined) {
    if (!Array.isArray(required) || required.some(name => typeof name !== 'string')) {
      throw new Error(`${toolName} ${path} 的 required 必须是字符串数组`);
    }
  }
  const additional = schema.additionalProperties;
  if (additional !== undefined && typeof additional !== 'boolean') {
    throw new Error(`${toolName} ${path} 的 additionalProperties 只支持布尔值`);
  }

  const propertyValidators = new Map<string, CompiledNode>();
  for (const [name, propertySchema] of Object.entries(properties ?? {})) {
    if (typeof propertySchema !== 'object' || propertySchema === null || Array.isArray(propertySchema)) {
      throw new Error(`${toolName} ${path}.properties.${name} 必须是对象`);
    }
    propertyValidators.set(
      name,
      compileNode(propertySchema as JsonObject, `${path}.properties.${name}`, toolName),
    );
  }
  const requiredNames: readonly string[] = Array.isArray(required) ? required : [];

  return {
    validate: (value, valuePath) => {
      const record = value as Record<string, JsonValue>;
      for (const name of requiredNames) {
        if (!(name in record)) {
          return { path: `${valuePath}.${name}`, message: '缺少必填参数' };
        }
      }
      for (const [name, validator] of propertyValidators) {
        if (name in record) {
          const issue = validator.validate(record[name] ?? null, `${valuePath}.${name}`);
          if (issue != null) {
            return issue;
          }
        }
      }
      if (additional === false) {
        for (const name of Object.keys(record)) {
          if (!propertyValidators.has(name)) {
            return {
              path: `${valuePath}.${name}`,
              message: '未声明的参数：该 Tool 的 inputSchema 不允许额外参数',
            };
          }
        }
      }
      return null;
    },
  };
}

/** 编译 array 节点的元素 Schema。 */
function compileItems(schema: JsonObject, path: string, toolName: string)
  : { validate: NodeValidator } | null {
  const items = schema.items;
  if (items === undefined) {
    return null;
  }
  if (typeof items !== 'object' || items === null || Array.isArray(items)) {
    throw new Error(`${toolName} ${path} 的 items 必须是对象`);
  }
  const compiled = compileNode(items as JsonObject, `${path}.items`, toolName);
  return { validate: compiled.validate };
}

/** 按 JSON 类型语义判断取值是否匹配声明的类型集合。 */
function matchesTypes(types: Set<string>, value: JsonValue): boolean {
  for (const type of types) {
    switch (type) {
      case 'object':
        if (typeof value === 'object' && value !== null && !Array.isArray(value)) {
          return true;
        }
        break;
      case 'array':
        if (Array.isArray(value)) {
          return true;
        }
        break;
      case 'string':
        if (typeof value === 'string') {
          return true;
        }
        break;
      case 'number':
        if (typeof value === 'number' && Number.isFinite(value)) {
          return true;
        }
        break;
      case 'integer':
        if (typeof value === 'number' && Number.isInteger(value)) {
          return true;
        }
        break;
      case 'boolean':
        if (typeof value === 'boolean') {
          return true;
        }
        break;
      case 'null':
        if (value === null) {
          return true;
        }
        break;
      default:
        return false;
    }
  }
  return false;
}
