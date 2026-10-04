package io.casehub.yaml.step.catalog;

import io.casehub.yaml.plugin.api.Definition;
import io.casehub.yaml.plugin.api.Portability;
import io.casehub.yaml.plugin.api.PluginRegistry;
import io.casehub.yaml.plugin.api.Result;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WalkerTest {

    private final Definition processDefn = defn("process");
    private final Definition assertDefn = defn("assert");

    private final PluginRegistry registry = new CompositePluginRegistry() {{
        register(processDefn);
        register(assertDefn);
    }};

    @Test
    void resolvesPluginNameAsKey() {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("process", Map.of("command", "deploy.sh"));

        List<ResolvedStep> resolved = Walker.resolve(List.of(step), registry);

        assertThat(resolved).hasSize(1);
        assertThat(resolved.get(0)).isInstanceOf(ResolvedStep.PluginStep.class);
        var plugin = (ResolvedStep.PluginStep) resolved.get(0);
        assertThat(plugin.actionName()).isEqualTo("process");
        assertThat(plugin.params()).containsEntry("command", "deploy.sh");
        assertThat(plugin.decorators()).isEmpty();
    }

    @Test
    void extractsDecoratorsFromPluginStep() {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("process", Map.of("command", "deploy.sh"));
        step.put("if", "${env.ready}");
        step.put("timeout", "30s");

        List<ResolvedStep> resolved = Walker.resolve(List.of(step), registry);

        var plugin = (ResolvedStep.PluginStep) resolved.get(0);
        assertThat(plugin.decorators()).containsEntry("if", "${env.ready}");
        assertThat(plugin.decorators()).containsEntry("timeout", "30s");
    }

    @Test
    void resolvesInvokeEscapeHatch() {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("invoke", Map.of("mcp", Map.of("tool", "custom-tool")));

        List<ResolvedStep> resolved = Walker.resolve(List.of(step), registry);

        assertThat(resolved).hasSize(1);
        assertThat(resolved.get(0)).isInstanceOf(ResolvedStep.InvokeStep.class);
        var invoke = (ResolvedStep.InvokeStep) resolved.get(0);
        assertThat(invoke.invokeSpec()).containsKey("mcp");
    }

    @Test
    void extractsStepLabelIgnoringItAsAction() {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("step", "deploy-prod");
        step.put("process", Map.of("command", "deploy.sh"));

        List<ResolvedStep> resolved = Walker.resolve(List.of(step), registry);

        var plugin = (ResolvedStep.PluginStep) resolved.get(0);
        assertThat(plugin.actionName()).isEqualTo("process");
        assertThat(plugin.decorators()).doesNotContainKey("step");
    }

    @Test
    void extractsStepLabel_passesToResolvedStep() {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("step", "risk-eval");
        step.put("process", Map.of("command", "evaluate.sh"));

        List<ResolvedStep> resolved = Walker.resolve(List.of(step), registry);

        assertThat(resolved).hasSize(1);
        assertThat(resolved.get(0).name()).isEqualTo("risk-eval");
    }

    @Test
    void noStepLabel_nameIsNull() {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("process", Map.of("command", "deploy.sh"));

        List<ResolvedStep> resolved = Walker.resolve(List.of(step), registry);

        assertThat(resolved.get(0).name()).isNull();
    }

    @Test
    void duplicateStepName_throws() {
        Map<String, Object> step1 = new LinkedHashMap<>();
        step1.put("step", "eval");
        step1.put("process", Map.of("command", "a.sh"));

        Map<String, Object> step2 = new LinkedHashMap<>();
        step2.put("step", "eval");
        step2.put("process", Map.of("command", "b.sh"));

        assertThatThrownBy(() -> Walker.resolve(List.of(step1, step2), registry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate step name 'eval'");
    }

    @Test
    void duplicateStepName_acrossNestedBlocks_throws() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("step", "eval");
        inner.put("process", Map.of("command", "a.sh"));

        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put("step", "eval");
        outer.put("process", Map.of("command", "b.sh"));

        Map<String, Object> block = new LinkedHashMap<>();
        block.put("block", List.of(inner));

        assertThatThrownBy(() -> Walker.resolve(List.of(block, outer), registry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate step name 'eval'");
    }

    @Test
    void parsesBarrierStep() {
        Map<String, Object> evalA = new LinkedHashMap<>();
        evalA.put("step", "eval-a");
        evalA.put("process", Map.of("command", "a.sh"));
        Map<String, Object> evalB = new LinkedHashMap<>();
        evalB.put("step", "eval-b");
        evalB.put("process", Map.of("command", "b.sh"));
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("step", "await-all");
        step.put("barrier", Map.of("await", List.of("eval-a", "eval-b"), "timeout", "30s"));

        List<ResolvedStep> resolved = Walker.resolve(List.of(evalA, evalB, step), registry);

        assertThat(resolved).hasSize(3);
        assertThat(resolved.get(2)).isInstanceOf(ResolvedStep.BarrierStep.class);
        var barrier = (ResolvedStep.BarrierStep) resolved.get(2);
        assertThat(barrier.name()).isEqualTo("await-all");
        assertThat(barrier.awaitSteps()).containsExactly("eval-a", "eval-b");
        assertThat(barrier.timeout()).isEqualTo(java.time.Duration.ofSeconds(30));
    }

    @Test
    void parsesQuorumStep() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("step", "strat-a");
        a.put("process", Map.of("command", "a.sh"));
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("step", "strat-b");
        b.put("process", Map.of("command", "b.sh"));
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("step", "strat-c");
        c.put("process", Map.of("command", "c.sh"));
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("step", "consensus");
        step.put("quorum", Map.of("required", 2, "of", List.of("strat-a", "strat-b", "strat-c"), "timeout", "15s"));

        List<ResolvedStep> resolved = Walker.resolve(List.of(a, b, c, step), registry);

        assertThat(resolved.get(3)).isInstanceOf(ResolvedStep.QuorumStep.class);
        var quorum = (ResolvedStep.QuorumStep) resolved.get(3);
        assertThat(quorum.name()).isEqualTo("consensus");
        assertThat(quorum.required()).isEqualTo(2);
        assertThat(quorum.ofSteps()).containsExactly("strat-a", "strat-b", "strat-c");
        assertThat(quorum.timeout()).isEqualTo(java.time.Duration.ofSeconds(15));
    }

    @Test
    void barrierWithoutAwait_throwsAtParseTime() {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("step", "wait");
        step.put("barrier", Map.of());

        assertThatThrownBy(() -> Walker.resolve(List.of(step), registry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("await");
    }

    @Test
    void quorumRequiredExceedsOf_throwsAtParseTime() {
        Map<String, Object> evalA = new LinkedHashMap<>();
        evalA.put("step", "a");
        evalA.put("process", Map.of("command", "a.sh"));
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("step", "q");
        step.put("quorum", Map.of("required", 5, "of", List.of("a")));

        assertThatThrownBy(() -> Walker.resolve(List.of(evalA, step), registry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required");
    }

    @Test
    void barrierAwaitsUnknownStep_throws() {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("step", "wait");
        step.put("barrier", Map.of("await", List.of("nonexistent")));

        assertThatThrownBy(() -> Walker.resolve(List.of(step), registry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown step 'nonexistent'");
    }

    @Test
    void quorumReferencesUnknownStep_throws() {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("step", "q");
        step.put("quorum", Map.of("required", 1, "of", List.of("ghost")));

        assertThatThrownBy(() -> Walker.resolve(List.of(step), registry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown step 'ghost'");
    }


    @Test
    void throwsOnUnknownAction() {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("unknown-action", Map.of("param", "value"));

        assertThatThrownBy(() -> Walker.resolve(List.of(step), registry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown-action")
                .hasMessageContaining("process")
                .hasMessageContaining("assert");
    }

    @Test
    void throwsOnEmptyStepMap() {
        Map<String, Object> step = new LinkedHashMap<>();

        assertThatThrownBy(() -> Walker.resolve(List.of(step), registry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void resolvesMultipleSteps() {
        Map<String, Object> step1 = new LinkedHashMap<>();
        step1.put("process", Map.of("command", "build.sh"));
        Map<String, Object> step2 = new LinkedHashMap<>();
        step2.put("assert", Map.of("expression", "true"));

        List<ResolvedStep> resolved = Walker.resolve(List.of(step1, step2), registry);

        assertThat(resolved).hasSize(2);
        assertThat(((ResolvedStep.PluginStep) resolved.get(0)).actionName())
                .isEqualTo("process");
        assertThat(((ResolvedStep.PluginStep) resolved.get(1)).actionName())
                .isEqualTo("assert");
    }

    @Test
    void allReservedKeysAreDecorators() {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("process", Map.of("command", "test.sh"));
        step.put("if", "true");
        step.put("retry", Map.of("max", 3));
        step.put("on-error", "fallback");
        step.put("on-success", "next");
        step.put("on-failure", "abort");

        List<ResolvedStep> resolved = Walker.resolve(List.of(step), registry);

        var plugin = (ResolvedStep.PluginStep) resolved.get(0);
        assertThat(plugin.decorators()).containsKeys("if", "retry", "on-error",
                "on-success", "on-failure");
        assertThat(plugin.params()).containsEntry("command", "test.sh");
    }


    @Test
    void resolvesBlockStep() {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("block", List.of(
                Map.of("process", Map.of("command", "a.sh")),
                Map.of("assert", Map.of("expression", "true"))
                                 ));
        step.put("loop", Map.of("count", 3));

        List<ResolvedStep> resolved = Walker.resolve(List.of(step), registry);

        assertThat(resolved).hasSize(1);
        assertThat(resolved.get(0)).isInstanceOf(ResolvedStep.BlockStep.class);
        var block = (ResolvedStep.BlockStep) resolved.get(0);
        assertThat(block.steps()).hasSize(2);
        assertThat(block.decorators()).containsKey("loop");
    }

    @Test
    void resolvesIfElseStep() {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("if", "${risk} == 'HIGH'");
        step.put("then", List.of(Map.of("process", Map.of("command", "escalate.sh"))));
        step.put("else", List.of(Map.of("process", Map.of("command", "proceed.sh"))));
        step.put("timeout", "30s");

        List<ResolvedStep> resolved = Walker.resolve(List.of(step), registry);

        assertThat(resolved).hasSize(1);
        assertThat(resolved.get(0)).isInstanceOf(ResolvedStep.IfElseStep.class);
        var ifElse = (ResolvedStep.IfElseStep) resolved.get(0);
        assertThat(ifElse.condition()).isEqualTo("${risk} == 'HIGH'");
        assertThat(ifElse.thenSteps()).hasSize(1);
        assertThat(ifElse.elseSteps()).hasSize(1);
        assertThat(ifElse.decorators()).containsKey("timeout");
        assertThat(ifElse.decorators()).doesNotContainKey("if");
    }

    @Test
    void resolvesIfWithoutElse() {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("if", "${enabled}");
        step.put("then", List.of(Map.of("process", Map.of("command", "a.sh"))));

        List<ResolvedStep> resolved = Walker.resolve(List.of(step), registry);

        var ifElse = (ResolvedStep.IfElseStep) resolved.get(0);
        assertThat(ifElse.elseSteps()).isEmpty();
    }

    @Test
    void ifWithoutThenIsDecorator() {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("process", Map.of("command", "a.sh"));
        step.put("if", "${enabled}");

        List<ResolvedStep> resolved = Walker.resolve(List.of(step), registry);

        assertThat(resolved.get(0)).isInstanceOf(ResolvedStep.PluginStep.class);
        assertThat(resolved.get(0).decorators()).containsKey("if");
    }

    @Test
    void resolvesMatchStep() {
        Map<String, Object> caseEntry = new LinkedHashMap<>();
        caseEntry.put("pattern", Map.of("type", "trade"));
        caseEntry.put("guard", "${match.amount} > 1000000");
        caseEntry.put("process", Map.of("command", "escalate.sh"));

        Map<String, Object> defaultEntry = new LinkedHashMap<>();
        defaultEntry.put("default", List.of(Map.of("process", Map.of("command", "log.sh"))));

        Map<String, Object> step = new LinkedHashMap<>();
        step.put("match", "${event}");
        step.put("cases", List.of(caseEntry, defaultEntry));

        List<ResolvedStep> resolved = Walker.resolve(List.of(step), registry);

        assertThat(resolved).hasSize(1);
        assertThat(resolved.get(0)).isInstanceOf(ResolvedStep.MatchStep.class);
        var match = (ResolvedStep.MatchStep) resolved.get(0);
        assertThat(match.scrutinee()).isEqualTo("${event}");
        assertThat(match.cases()).hasSize(2);
        assertThat(match.cases().get(0).pattern()).isInstanceOf(io.casehub.yaml.core.step.MatchPattern.StructuralPattern.class);
        assertThat(match.cases().get(0).guard()).isEqualTo("${match.amount} > 1000000");
        assertThat(match.cases().get(1).pattern()).isInstanceOf(io.casehub.yaml.core.step.MatchPattern.DefaultPattern.class);
    }

    @Test
    void resolvesParallelStep() {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("parallel", List.of(
                Map.of("process", Map.of("command", "a.sh")),
                Map.of("process", Map.of("command", "b.sh"))
                                    ));
        step.put("timeout", "60s");

        List<ResolvedStep> resolved = Walker.resolve(List.of(step), registry);

        assertThat(resolved).hasSize(1);
        assertThat(resolved.get(0)).isInstanceOf(ResolvedStep.ParallelStep.class);
        var par = (ResolvedStep.ParallelStep) resolved.get(0);
        assertThat(par.steps()).hasSize(2);
        assertThat(par.decorators()).containsKey("timeout");
    }

    @Test
    void rejectsAmbiguousStructuralPlusAction() {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("block", List.of(Map.of("process", Map.of("command", "a.sh"))));
        step.put("process", Map.of("command", "b.sh"));

        assertThatThrownBy(() -> Walker.resolve(List.of(step), registry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ambiguous");
    }

    @Test
    void rejectsOrphanedThen() {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("process", Map.of("command", "a.sh"));
        step.put("then", List.of(Map.of("process", Map.of("command", "b.sh"))));

        assertThatThrownBy(() -> Walker.resolve(List.of(step), registry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("then");
    }

    @Test
    void rejectsElseWithoutThen() {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("process", Map.of("command", "a.sh"));
        step.put("else", List.of(Map.of("process", Map.of("command", "b.sh"))));

        assertThatThrownBy(() -> Walker.resolve(List.of(step), registry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("else");
    }

    @Test
    void rejectsDefaultNotLast() {
        Map<String, Object> defaultEntry = new LinkedHashMap<>();
        defaultEntry.put("default", List.of(Map.of("process", Map.of("command", "log.sh"))));
        Map<String, Object> caseEntry = new LinkedHashMap<>();
        caseEntry.put("pattern", "ACTIVE");
        caseEntry.put("process", Map.of("command", "a.sh"));

        Map<String, Object> step = new LinkedHashMap<>();
        step.put("match", "${status}");
        step.put("cases", List.of(defaultEntry, caseEntry));

        assertThatThrownBy(() -> Walker.resolve(List.of(step), registry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("default must be the last case");
    }


    @Test
    void rejectsCaseWithoutPatternOrDefault() {
        Map<String, Object> caseEntry = new LinkedHashMap<>();
        caseEntry.put("guard", "${match.amount} > 1000");
        caseEntry.put("process", Map.of("command", "a.sh"));

        Map<String, Object> step = new LinkedHashMap<>();
        step.put("match", "${status}");
        step.put("cases", List.of(caseEntry));

        assertThatThrownBy(() -> Walker.resolve(List.of(step), registry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must contain either 'pattern' or 'default'");
    }

    @Test
    void recursivelyResolvesNestedBlocks() {
        Map<String, Object> innerBlock = new LinkedHashMap<>();
        innerBlock.put("block", List.of(
                Map.of("process", Map.of("command", "inner.sh"))));
        innerBlock.put("loop", Map.of("count", 2));

        Map<String, Object> outerBlock = new LinkedHashMap<>();
        outerBlock.put("block", List.of(
                Map.of("process", Map.of("command", "outer.sh")),
                innerBlock));

        List<ResolvedStep> resolved = Walker.resolve(List.of(outerBlock), registry);

        var outer = (ResolvedStep.BlockStep) resolved.get(0);
        assertThat(outer.steps()).hasSize(2);
        assertThat(outer.steps().get(1)).isInstanceOf(ResolvedStep.BlockStep.class);
        var inner = (ResolvedStep.BlockStep) outer.steps().get(1);
        assertThat(inner.steps()).hasSize(1);
        assertThat(inner.decorators()).containsKey("loop");
    }


    @Test
    void warnsWhenMatchHasNoDefault() {
        Map<String, Object> caseEntry = new LinkedHashMap<>();
        caseEntry.put("pattern", "ACTIVE");
        caseEntry.put("process", Map.of("command", "a.sh"));

        Map<String, Object> step = new LinkedHashMap<>();
        step.put("match", "${status}");
        step.put("cases", List.of(caseEntry));

        var handler = new java.util.logging.Handler() {
            final List<String> warnings = new java.util.ArrayList<>();

            @Override
            public void publish(java.util.logging.LogRecord record) {
                if (record.getLevel() == java.util.logging.Level.WARNING) {
                    warnings.add(record.getMessage());
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };

        var logger = java.util.logging.Logger.getLogger(Walker.class.getName());
        logger.addHandler(handler);
        try {
            Walker.resolve(List.of(step), registry);
            assertThat(handler.warnings).hasSize(1);
            assertThat(handler.warnings.get(0)).contains("default");
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void noWarningWhenMatchHasDefault() {
        Map<String, Object> caseEntry = new LinkedHashMap<>();
        caseEntry.put("pattern", "ACTIVE");
        caseEntry.put("process", Map.of("command", "a.sh"));

        Map<String, Object> defaultEntry = new LinkedHashMap<>();
        defaultEntry.put("default", List.of(Map.of("process", Map.of("command", "log.sh"))));

        Map<String, Object> step = new LinkedHashMap<>();
        step.put("match", "${status}");
        step.put("cases", List.of(caseEntry, defaultEntry));

        var handler = new java.util.logging.Handler() {
            final List<String> warnings = new java.util.ArrayList<>();

            @Override
            public void publish(java.util.logging.LogRecord record) {
                if (record.getLevel() == java.util.logging.Level.WARNING) {
                    warnings.add(record.getMessage());
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };

        var logger = java.util.logging.Logger.getLogger(Walker.class.getName());
        logger.addHandler(handler);
        try {
            Walker.resolve(List.of(step), registry);
            assertThat(handler.warnings).isEmpty();
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void exceedsMaxDepth_throwsWithPath() {
        Map<String, Object> deeplyNested = Map.of("process", Map.of());
        for (int i = 0; i < Walker.MAX_DEPTH + 1; i++) {
            deeplyNested = new LinkedHashMap<>(Map.of("block", List.of(deeplyNested)));
        }
        var steps = List.of(deeplyNested);
        assertThatThrownBy(() -> Walker.resolve(steps, registry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Maximum nesting depth")
                .hasMessageContaining("" + Walker.MAX_DEPTH);
    }

    @Test
    void atMaxDepth_succeeds() {
        Map<String, Object> nested = Map.of("process", Map.of());
        for (int i = 0; i < Walker.MAX_DEPTH; i++) {
            nested = new LinkedHashMap<>(Map.of("block", List.of(nested)));
        }
        var steps  = List.of(nested);
        var result = Walker.resolve(steps, registry);
        assertThat(result).hasSize(1);
    }


    private static Definition defn(String name) {
        return new Definition(name, null, Map.of(), Map.of(), Portability.JAVA,
                (params, services) -> Result.of(Map.of()));
    }

    @Test
    void resolvesTryCatchFinallyStep() {
        var step = new LinkedHashMap<String, Object>();
        step.put("try", List.of(Map.of("process", Map.of("cmd", "debit"))));
        step.put("catch", List.of(Map.of("process", Map.of("cmd", "compensate"))));
        step.put("finally", List.of(Map.of("process", Map.of("cmd", "audit"))));

        List<ResolvedStep> resolved = Walker.resolve(List.of(step), registry);
        assertThat(resolved).hasSize(1);
        assertThat(resolved.get(0)).isInstanceOf(ResolvedStep.TryCatchFinallyStep.class);
        var tcf = (ResolvedStep.TryCatchFinallyStep) resolved.get(0);
        assertThat(tcf.trySteps()).hasSize(1);
        assertThat(tcf.catchSteps()).hasSize(1);
        assertThat(tcf.finallySteps()).hasSize(1);
    }

    @Test
    void resolvesTryWithoutCatch() {
        var step = new LinkedHashMap<String, Object>();
        step.put("try", List.of(Map.of("process", Map.of("cmd", "run"))));
        step.put("finally", List.of(Map.of("process", Map.of("cmd", "cleanup"))));

        List<ResolvedStep> resolved = Walker.resolve(List.of(step), registry);
        var                tcf      = (ResolvedStep.TryCatchFinallyStep) resolved.get(0);
        assertThat(tcf.trySteps()).hasSize(1);
        assertThat(tcf.catchSteps()).isEmpty();
        assertThat(tcf.finallySteps()).hasSize(1);
    }

    @Test
    void catchWithoutTry_throws() {
        var step = new LinkedHashMap<String, Object>();
        step.put("process", Map.of("cmd", "run"));
        step.put("catch", List.of(Map.of("process", Map.of("cmd", "handle"))));

        assertThatThrownBy(() -> Walker.resolve(List.of(step), registry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'catch' requires 'try'");
    }

    @Test
    void finallyWithoutTry_throws() {
        var step = new LinkedHashMap<String, Object>();
        step.put("process", Map.of("cmd", "run"));
        step.put("finally", List.of(Map.of("process", Map.of("cmd", "cleanup"))));

        assertThatThrownBy(() -> Walker.resolve(List.of(step), registry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'finally' requires 'try'");
    }

    @Test
    void resolvesSelectStep() {
        var step = new LinkedHashMap<String, Object>();
        step.put("select", List.of(
                Map.of("subscribe", Map.of("channel", "quotes"),
                       "process", Map.of("cmd", "handle")),
                Map.of("wait", "timeout",
                       "process", Map.of("cmd", "fallback"))));

        List<ResolvedStep> resolved = Walker.resolve(List.of(step), registry);
        assertThat(resolved).hasSize(1);
        assertThat(resolved.get(0)).isInstanceOf(ResolvedStep.SelectStep.class);
        var sel = (ResolvedStep.SelectStep) resolved.get(0);
        assertThat(sel.branches()).hasSize(2);
        assertThat(sel.branches().get(0).type()).isEqualTo(ResolvedStep.SelectBranchType.SUBSCRIBE);
        assertThat(sel.branches().get(0).name()).isEqualTo("quotes");
        assertThat(sel.branches().get(1).type()).isEqualTo(ResolvedStep.SelectBranchType.WAIT);
        assertThat(sel.branches().get(1).name()).isEqualTo("timeout");
    }

    @Test
    void selectBranchWithoutSubscribeOrWait_throws() {
        var step = new LinkedHashMap<String, Object>();
        step.put("select", List.of(Map.of("invalid", "branch")));

        assertThatThrownBy(() -> Walker.resolve(List.of(step), registry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("select branch must contain 'subscribe' or 'wait'");
    }

    @Test
    void resolvesMatchCaseWithInlineAction() {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("match", "${status}");
        step.put("cases", List.of(
                Map.of("pattern", "active",
                        "process", Map.of("command", "activate.sh")),
                Map.of("default", List.of(
                        Map.of("assert", Map.of("expected", "fallback"))))));

        List<ResolvedStep> resolved = Walker.resolve(List.of(step), registry);

        assertThat(resolved).hasSize(1);
        var match = (ResolvedStep.MatchStep) resolved.get(0);
        assertThat(match.cases()).hasSize(2);
        assertThat(match.cases().get(0).steps()).hasSize(1);
        assertThat(match.cases().get(0).steps().get(0)).isInstanceOf(ResolvedStep.PluginStep.class);
        var plugin = (ResolvedStep.PluginStep) match.cases().get(0).steps().get(0);
        assertThat(plugin.actionName()).isEqualTo("process");
    }

    @Test
    void resolvesSelectBranchWithInlineAction() {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("select", List.of(
                Map.of("subscribe", "events",
                        "process", Map.of("command", "handle.sh")),
                Map.of("wait", "timeout-signal",
                        "assert", Map.of("expected", "timed-out"))));

        List<ResolvedStep> resolved = Walker.resolve(List.of(step), registry);

        assertThat(resolved).hasSize(1);
        var select = (ResolvedStep.SelectStep) resolved.get(0);
        assertThat(select.branches()).hasSize(2);
        assertThat(select.branches().get(0).steps()).hasSize(1);
        assertThat(select.branches().get(0).steps().get(0)).isInstanceOf(ResolvedStep.PluginStep.class);
    }

    @Test
    void rejectsStepsKeyAtStepLevel() {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("steps", List.of(Map.of("process", Map.of("command", "go.sh"))));

        assertThatThrownBy(() -> Walker.resolve(List.of(step), registry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'steps' is no longer valid");
    }

    @Test
    void rejectsDoKeyAtStepLevel() {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("do", List.of(Map.of("process", Map.of("command", "go.sh"))));

        assertThatThrownBy(() -> Walker.resolve(List.of(step), registry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'do' is no longer valid");
    }
}
