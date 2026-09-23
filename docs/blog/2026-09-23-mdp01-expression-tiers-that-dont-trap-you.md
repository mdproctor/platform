---
title: "Expression tiers that don't trap you"
date: 2026-09-23
author: mdp
entry_type: note
subtype: diary
projects: [casehubio/platform]
series: issue-411-expression-escape-model
tags: [expression, spi, yaml, orchestration, invoke, scenario-action]
---

The YAML orchestration layer has always had expressions — JQ for transforms, MVEL for conditions. But there was a ceiling. The moment you needed to call a CDI bean method, update a database, or do anything with side effects, you fell out of YAML into a `@ScenarioAction` class. That's a context switch: new file, new annotation, constructor injection, the full ceremony.

The four-tier model closes that gap. Tier 3 (`invoke: AlertRepository::save`) gives YAML direct access to any managed bean method — full CDI stack, interceptors, transactions, no adapter code. The interesting design question was where the boundary sits between "just call a method" (Tier 3) and "I need scope-aware orchestration primitives" (Tier 4). We landed on: if you need `ScenarioScope` — channels, spawn, child scopes — use `@ScenarioAction`. If you just need to call a bean, use `invoke:`. The tier separation *is* the abstraction boundary.

The security model forced a good constraint. Any dynamic invocation needs an allow-list — we went fail-closed with `InvocationPolicy`, defaulting to an empty package set that denies everything. Production deployments configure `casehub.expression.invoke.allowed-packages`. The `@DefaultBean` NoOp in platform/ is permissive, but it's irrelevant — without the expression module on the classpath, the `BeanInvoker` NoOp throws `UnsupportedOperationException` before the policy is ever checked.

The platform-api zero-dep constraint shaped the SPI design more than expected. `BeanInvoker.invoke()` takes `(String, String, Object...)` — primitives only, no yaml-core types. `ActionHandle.invoke(ScenarioScope, Map)` can't live in platform-api because `ScenarioScope` is yaml-core, so `ActionRegistry` and `ActionHandle` live in yaml-core's orchestration package alongside the scope they reference. The decision review caught this boundary violation before it shipped — the original design had everything in platform-api.

`ReflectiveBeanInvoker` is the framework-neutral core: a `Function<String, Object>` resolver that CDI wraps with `BeanManager.getBeans()` and Spring wraps with `ApplicationContext.getBean()`. Same POJO, different resolver function. Varargs handling was the only tricky part — fixed-arity methods take precedence over varargs when arg counts match, following Java's own overload resolution.

This opens up the scenario runner path for pages. The primitives are in place: expression defaults wired, bean invoke ready, action registry scanning at startup. The scenario runner consumes these — it doesn't need to build them.
