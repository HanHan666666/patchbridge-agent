
## 重点（极其重要）
- 代码可维护性可读性高于一切，不要为了追求短期的开发效率而牺牲代码质量。
- 详细的向用户说明你的思路，和你打算如何实现这个需求。
- 统一入口：能收敛的业务逻辑要集中封装，避免在多个页面/组件里写重复作用。
- 需求不明确时，不猜测、不兜底；只实现已明确要求的行为
- 修改代码时，应删除已无用途的兜底逻辑，而不是在其上继续叠加分支
- 不得为“提高健壮性”擅自保留旧逻辑、兼容路径或备用实现
- 不得添加 fallback、降级分支、静默容错、默认返回值或“临时可用”的替代实现除非用户要求
- 单元测试是为了保证逻辑没问题，不要为了测试而测试。

## 路线图同步（强制）
- `docs/roadmap.md` 是项目实施状态的唯一可信来源。
- 新增、修改、删除或暂缓功能时，必须在同一次变更中同步路线图的状态、范围、验收条件、最近更新时间和更新记录。
- 面向用户的能力只有在实现、关键测试、使用说明和 Demo（或明确的底层验证方式）全部完成后，才能在路线图中标记为已完成。

- 文档写作与维护必须遵守 `docs/contributing/documentation.md`；新增、移动或删除文档时同步更新该规范、文档中心和全部引用。
- 架构边界改变时，还必须同步架构文档或 ADR；README 不维护另一份可独立演进的详细路线图。


## 代码要求
1. 代码要求结构清晰，不应付事情，长远维护考虑，遵循设计模式最佳实践，遵循项目代码风格。
2. 保证代码逻辑严谨，整洁，结构清晰，容易理解和维护，不要过度设计增加系统复杂性
3. 工程优化，以工程化，能安全正常使用不出错为主，考虑周全，遵循越复杂越容易出错，越简单越容易可控原则，一个健康的系统 越简单越可控
4. 遵循合理的组件化设计原则，要考虑组件复用性的可能。
5. 在你发现架构不合理的时候，要及时的提出来。
6. 编写代码的过程中，必须牢记以下几个原则：
    - 开闭原则（Open Closed Principle，OCP）
    - 单一职责原则（Single Responsibility Principle, SRP）
    - 里氏代换原则（Liskov Substitution Principle，LSP）
    - 依赖倒转原则（Dependency Inversion Principle，DIP）
    - 接口隔离原则（Interface Segregation Principle，ISP）
    - 合成/聚合复用原则（Composite/Aggregate Reuse Principle，CARP）
    - 最少知识原则（Least Knowledge Principle，LKP）或者迪米特法则（Law of  Demeter，LOD）

## 八荣八耻
1.以暗猜接口为耻，以认真查阅为荣
2.以模糊执行为耻，以寻求确认为荣
3.以盲想业务为耻，以人类确认为荣
4.以创造接口为耻，以复用现有为荣
5.以跳过验证为耻，以主动测试为荣
6.以破坏架构为耻，以遵循规范为荣
7.以假装理解为耻，以诚实无知为荣
8.以盲目修改为耻，以谨慎重构为荣


## 代码注释规则（强制）

## 注释规范

所有生成或修改的代码必须遵守以下注释要求：
1. 注释语言统一使用中文。
2. 注释格式必须符合当前编程语言的标准文档注释规范，例如 JavaDoc、JSDoc、docstring、GoDoc 等。
3. 核心文件顶部应添加文件级注释，说明该文件的业务定位、适用场景，以及为什么需要该文件。
4. 字段、接口、类型、类、方法、函数上方必须添加中文注释。
5. 注释不能只是复述代码逻辑，也不要逐行解释代码本身。
6. 注释应重点说明设计原因、适用场景、边界条件、调用注意事项，以及该代码在整体流程中的作用。
7. 对显而易见的代码不要添加低价值注释，例如“获取名称”“设置值”“返回结果”等。
8. 涉及业务规则、兼容逻辑、异常兜底、性能取舍、安全限制或历史原因时，必须在注释中说明为什么这样处理。
9. 修改代码时必须同步维护相关注释，避免注释与实际代码行为不一致。


Edits are scope-preserving by default.

When the user corrects, removes, or rejects an existing element,
treat that as a local patch to the current artifact.

Remove or change only the named element and its strictly dependent parts.
Do not turn the correction into a new design principle, explanation,
rule, section, abstraction, or broader rewrite unless explicitly requested.

Preserve all unrelated content, structure, behavior, and prior decisions.

If completing the request would materially expand scope, ask first.

