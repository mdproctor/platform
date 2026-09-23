# Four-Tier Expression Escape Model — Design Spec

## Summary

Implements the four-tier expression escape model designed in #391 D14. Provides platform-level primitives for YAML scenarios to bridge from declarative expressions to full Java runtime — from one-liner transforms up to stateful scope-aware actions. Pages (#412) consumes these primitives in the scenario runner.

## Scope

| Deliverable | Module | Description |
|-------------|--------|-------------|
| Expression default wiring | expression-core | Constructor-initialized defaults: CONDITION→mvel, TRANSFORM/FILTER→jq |
| InvokeDirective record | yaml-core | Parsed `invoke: Bean::method` data model |
| BeanInvoker SPI | platform-api | `invoke(String beanClassName, String methodName, Object... args)` |
| InvocationPolicy SPI | platform-api | `isAllowed(String beanClassName, String methodName)` — fail-closed allow-list |
| @ScenarioAction annotation | platform-api | Marker for scope-aware action methods |
| ActionRegistry + ActionHandle | yaml-core | `resolve(String name)` → `ActionHandle.invoke(ScenarioScope, Map)` — alongside ScenarioScope |
| CdiBeanInvoker | expression/ | CDI BeanManager implementation of BeanInvoker |
| CdiActionRegistry | expression/ | @Startup CDI discovery of @ScenarioAction methods |
| AllowListInvocationPolicy | expression/ | Config-driven package allow-list (configurable mock in platform/) |
| SpringBeanInvoker | expression-spring/ | ApplicationContext.getBean() implementation |
| SpringActionRegistry | expression-spring/ | Method-level @ScenarioAction scanning |
| Tutorial-quality tests | all modules | Full coverage with documentation-grade test names |

## Out of scope

- Scenario runner integration (pages #412 owns this)
- MCP-based scenario debugging (future)
- Expression engine sandboxing (future — InvocationPolicy establishes the pattern)

## Four-Tier Architecture

| Tier | YAML Syntax | Power | Runtime |
|------|-------------|-------|---------|
| 1. Expression | `transform: ".price * 1.1"` | JQ/MVEL one-liners | ExpressionEngineRegistry.compile() — already exists |
| 2. Compute block | `compute: { engine: jq, expression: "..." }` | Multi-line expressions | ComputeBlock record → registry.compile(block.engine(), block.expression(), ...) — already exists |
| 3. Bean invoke | `invoke: Bean::method` | Full CDI/Spring stack | InvokeDirective → BeanInvoker.invoke() — this branch |
| 4. @ScenarioAction | `action: name` | Stateful, scope-aware | ActionRegistry.resolve() → ActionHandle.invoke() — this branch |

Tiers 1 and 2 are already implemented. This branch delivers Tiers 3 and 4 plus the expression default wiring.

## Tier 1-2: Expression Default Wiring

### Change

`DefaultExpressionEngineRegistry` constructor registers convention defaults:

```java
public DefaultExpressionEngineRegistry() {
    registerDefault(ExpressionContext.CONDITION, "mvel");
    registerDefault(ExpressionContext.TRANSFORM, "jq");
    registerDefault(ExpressionContext.FILTER, "jq");
}
```

Framework-neutral — both Quarkus and Spring get defaults automatically via the core POJO constructor. Overridable via subsequent `registerDefault()` calls.

### ComputeBlock compilation

No bridge method needed. Callers use:
```java
registry.compile(block.engine(), block.expression(), contextType, resultType)
```

## Tier 3: Bean Invoke

### InvokeDirective (yaml-core)

```java
package io.casehub.yaml.core.orchestration;

public record InvokeDirective(String beanClassName, String methodName, List<String> args) {
    
    public static InvokeDirective parse(Object raw) {
        // Shorthand: "io.casehub.trading.AlertRepository::save"
        // Full form: { bean: "...", method: "...", args: ["${price}", "${quantity}"] }
    }
}
```

Variables are always resolved before `parse()` — VariableResolver is a pre-processing step. The `::` separator is unambiguous after resolution (Java class names don't contain `::`).

### BeanInvoker SPI (platform-api)

```java
package io.casehub.platform.api.expression;

public interface BeanInvoker {
    Object invoke(String beanClassName, String methodName, Object... args);
}
```

Primitive parameters only — no yaml-core types cross into platform-api. The caller destructures InvokeDirective before calling:

```java
invoker.invoke(directive.beanClassName(), directive.methodName(), resolvedArgs)
```

### InvocationPolicy SPI (platform-api)

```java
package io.casehub.platform.api.expression;

public interface InvocationPolicy {
    boolean isAllowed(String beanClassName, String methodName);
}
```

### AllowListInvocationPolicy (expression/)

Configured via `casehub.expression.invoke.allowed-packages` (comma-separated package prefixes). When no packages configured, defaults to the project's own root package — fail-closed, not unrestricted.

`CdiBeanInvoker` checks `policy.isAllowed()` before every invocation. Denied invocations throw `InvocationDeniedException`.

`@DefaultBean NoOpInvocationPolicy` in platform/ returns `true` for all — displaced by AllowListInvocationPolicy when expression/ is on classpath.

### Method resolution

Parameter-count matching (YAML can't express Java types):
1. Find all public methods with the given name
2. Filter by arg count — varargs methods match any count ≥ (paramCount - 1)
3. On ambiguity: prefer fixed-arity over varargs (matching Java's own resolution)
4. If still ambiguous: throw `AmbiguousMethodException` with candidates listed

## Tier 4: @ScenarioAction

### Annotation (platform-api)

```java
package io.casehub.platform.api.expression;

@Retention(RUNTIME)
@Target(METHOD)
public @interface ScenarioAction {
    String value();  // action name referenced in YAML
}
```

### ActionRegistry + ActionHandle (yaml-core)

ActionRegistry and ActionHandle live in `io.casehub.yaml.core.orchestration` alongside ScenarioScope — ActionHandle.invoke() takes ScenarioScope as a parameter, so they must be co-located. @ScenarioAction annotation stays in platform-api (it's a pure marker with no yaml-core imports).

```java
package io.casehub.yaml.core.orchestration;

public interface ActionRegistry {
    Optional<ActionHandle> resolve(String name);
    Set<String> registeredNames();
}

public interface ActionHandle {
    Object invoke(ScenarioScope scope, Map<String, Object> args);
}
```

ScenarioScope coupling is intentional — Tier 4 is for stateful scope-aware logic. If an action doesn't need scope, it belongs in Tier 3 (invoke:).

### CdiActionRegistry (expression/)

`@ApplicationScoped @Startup` — scans all CDI beans for methods annotated `@ScenarioAction`, builds name → MethodHandle map. Duplicate names are rejected at startup with a descriptive error.

Method signature contract:
```java
@ScenarioAction("place-order")
public OrderResult placeOrder(ScenarioScope scope, Map<String, Object> args) { ... }
```

## Dual-Framework Implementation

### Core POJOs (expression-core/)

- `DefaultExpressionEngineRegistry` — already exists, gains constructor defaults
- `AllowListInvocationPolicy` — framework-neutral package-prefix matching

### Quarkus CDI (expression/)

- `CdiBeanInvoker @ApplicationScoped` — BeanManager.getBeans() + create() + invoke
- `CdiActionRegistry @ApplicationScoped @Startup` — Instance<Object> iteration + method scanning
- `AllowListInvocationPolicy @ApplicationScoped` — @ConfigProperty for allowed-packages

### Spring (expression-spring/)

Hand-written (not auto-generated — BeanManager logic doesn't translate via spring-generator):

- `SpringBeanInvoker` — ApplicationContext.getBean(Class.forName(beanClassName))
- `SpringActionRegistry` — iterate ApplicationContext.getBeanDefinitionNames(), scan methods
- `SpringAllowListInvocationPolicy` — @ConfigurationProperties for allowed-packages

## Test Strategy

Tutorial-quality tests following the #410 standard:

| Module | Tests | Coverage |
|--------|-------|----------|
| yaml-core | InvokeDirectiveTest | parse shorthand, parse full form, parse with args, invalid syntax, `::` edge cases |
| platform-api | InvocationPolicyTest, ActionRegistryTest | SPI contract tests |
| expression/ | CdiBeanInvokerTest | invoke simple method, invoke with args, varargs, overload disambiguation, policy denial, class not found, method not found |
| expression/ | CdiActionRegistryTest | discover single action, multiple actions per class, duplicate name rejection, invoke with scope and args |
| expression/ | AllowListInvocationPolicyTest | allowed package, denied package, no config (fail-closed), multiple packages |
| expression/ | ExpressionDefaultsTest | CONDITION defaults to mvel, TRANSFORM defaults to jq, override works |
| expression-spring/ | SpringBeanInvokerTest | ApplicationContext resolution, policy enforcement |
| expression-spring/ | SpringActionRegistryTest | method-level scanning, invoke |

## References

- #391 D14 — four-tier escape model design
- #391 D3 — ComputeBlock data model
- #391 D4 — ExpressionContext enum + registry defaults
- #405 D14 — YAML capability equivalence constraint
- ExpressionEngineRegistry (platform-api/src/main/java/.../expression/ExpressionEngineRegistry.java)
- DefaultExpressionEngineRegistry (expression-core/src/main/java/.../expression/)
- ForEachDirective (yaml-core — parse(Object) pattern)
- ComputeBlock (yaml-core — directive data model pattern)
- @McpDomain (platform-api — CDI discovery pattern for ActionRegistry)
- platform-api zero-dep constraint (CLAUDE.md §Rules)
