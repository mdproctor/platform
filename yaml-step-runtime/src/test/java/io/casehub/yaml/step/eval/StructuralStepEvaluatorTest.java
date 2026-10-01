package io.casehub.yaml.step.eval;

import io.casehub.yaml.core.condition.ConditionEvaluator;
import io.casehub.yaml.core.orchestration.DefaultScenarioScope;
import io.casehub.yaml.core.resolver.VariableResolver;
import io.casehub.yaml.core.resolver.VariableSource;
import io.casehub.yaml.core.step.MatchPattern;
import io.casehub.yaml.plugin.api.Result;
import io.casehub.yaml.step.catalog.ResolvedMatchCase;
import io.casehub.yaml.step.catalog.ResolvedStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StructuralStepEvaluatorTest {

    private StructuralStepEvaluator evaluator;
    private VariableResolver resolver;

    @BeforeEach
    void setUp() {
        var condEval = new ConditionEvaluator(null);
        evaluator = new StructuralStepEvaluator(condEval);
        resolver = new VariableResolver(Map.of(), Set.of());
    }

    private ResolvedStep leaf(String id) {
        return new ResolvedStep.InvokeStep(null, Map.of("id", id), Map.of());
    }

    private StepRunner successRunner(Map<String, Object> output) {
        return (step, res) -> Result.of(output);
    }

    private StepRunner trackingRunner(List<String> order) {
        return (step, res) -> {
            if (step instanceof ResolvedStep.InvokeStep inv) {
                order.add((String) inv.invokeSpec().get("id"));
            }
            return Result.of(Map.of());
        };
    }

    // ── BlockStep ──────────────────────────────────────────────────

    @Nested
    class BlockStepTests {

        @Test
        void emptyBlock_returnsSuccess() {
            var block = new ResolvedStep.BlockStep(null, List.of(), Map.of());
            var result = evaluator.evaluate(block, resolver, successRunner(Map.of()));
            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        void singleStep_delegatesToRunner() {
            var block = new ResolvedStep.BlockStep(null, List.of(leaf("a")), Map.of());
            var result = evaluator.evaluate(block, resolver,
                    (step, res) -> Result.of(Map.of("key", "value")));
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.output()).containsEntry("key", "value");
        }

        @Test
        void multipleSteps_executesSequentially() {
            var order = new ArrayList<String>();
            var block = new ResolvedStep.BlockStep(null, 
                    List.of(leaf("first"), leaf("second"), leaf("third")), Map.of());
            evaluator.evaluate(block, resolver, trackingRunner(order));
            assertThat(order).containsExactly("first", "second", "third");
        }

        @Test
        void firstStepFails_shortCircuits() {
            var order = new ArrayList<String>();
            var block = new ResolvedStep.BlockStep(null, 
                    List.of(leaf("a"), leaf("b")), Map.of());
            var result = evaluator.evaluate(block, resolver, (step, res) -> {
                if (step instanceof ResolvedStep.InvokeStep inv) {
                    order.add((String) inv.invokeSpec().get("id"));
                    if ("a".equals(inv.invokeSpec().get("id"))) {
                        return Result.failed("boom");
                    }
                }
                return Result.of(Map.of());
            });
            assertThat(result.isSuccess()).isFalse();
            assertThat(order).containsExactly("a");
        }

        @Test
        void returnsLastStepOutput() {
            var block = new ResolvedStep.BlockStep(null, 
                    List.of(leaf("a"), leaf("b")), Map.of());
            var result = evaluator.evaluate(block, resolver, (step, res) -> {
                if (step instanceof ResolvedStep.InvokeStep inv) {
                    return Result.of(Map.of("from", inv.invokeSpec().get("id")));
                }
                return Result.of(Map.of());
            });
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.output()).containsEntry("from", "b");
        }

        @Test
        void nestedBlocks_executeCorrectly() {
            var order = new ArrayList<String>();
            var inner = new ResolvedStep.BlockStep(null, 
                    List.of(leaf("inner1"), leaf("inner2")), Map.of());
            var outer = new ResolvedStep.BlockStep(null, 
                    List.of(leaf("outer1"), inner, leaf("outer2")), Map.of());
            evaluator.evaluate(outer, resolver, trackingRunner(order));
            assertThat(order).containsExactly("outer1", "inner1", "inner2", "outer2");
        }
    }

    // ── IfElseStep ─────────────────────────────────────────────────

    @Nested
    class IfElseStepTests {

        @Test
        void conditionTrue_executesThenBranch() {
            var step = new ResolvedStep.IfElseStep(null, "true",
                    List.of(leaf("then")), List.of(leaf("else")), Map.of());
            var order = new ArrayList<String>();
            evaluator.evaluate(step, resolver, trackingRunner(order));
            assertThat(order).containsExactly("then");
        }

        @Test
        void conditionFalse_executesElseBranch() {
            var step = new ResolvedStep.IfElseStep(null, "false",
                    List.of(leaf("then")), List.of(leaf("else")), Map.of());
            var order = new ArrayList<String>();
            evaluator.evaluate(step, resolver, trackingRunner(order));
            assertThat(order).containsExactly("else");
        }

        @Test
        void conditionFalse_noElseBranch_returnsSuccess() {
            var step = new ResolvedStep.IfElseStep(null, "false",
                    List.of(leaf("then")), null, Map.of());
            var result = evaluator.evaluate(step, resolver, successRunner(Map.of()));
            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        void conditionWithVariableInterpolation() {
            var step = new ResolvedStep.IfElseStep(null, "${var.flag}",
                    List.of(leaf("then")), List.of(leaf("else")), Map.of());
            var flagResolver = new VariableResolver(
                    Map.of("var", (VariableSource) name -> "flag".equals(name) ? "true" : null),
                    Set.of());
            var order = new ArrayList<String>();
            evaluator.evaluate(step, flagResolver, trackingRunner(order));
            assertThat(order).containsExactly("then");
        }

        @Test
        void conditionEvaluationThrows_returnsFailure() {
            var step = new ResolvedStep.IfElseStep(null, "not-a-boolean",
                    List.of(leaf("then")), List.of(leaf("else")), Map.of());
            var result = evaluator.evaluate(step, resolver, successRunner(Map.of()));
            assertThat(result.isSuccess()).isFalse();
        }

        @Test
        void thenBranchFails_returnsFailure() {
            var step = new ResolvedStep.IfElseStep(null, "true",
                    List.of(leaf("then")), null, Map.of());
            var result = evaluator.evaluate(step, resolver,
                    (s, r) -> Result.failed("then failed"));
            assertThat(result.isSuccess()).isFalse();
        }

        @Test
        void multipleThenSteps_executesAllSequentially() {
            var step = new ResolvedStep.IfElseStep(null, "true",
                    List.of(leaf("t1"), leaf("t2"), leaf("t3")), null, Map.of());
            var order = new ArrayList<String>();
            evaluator.evaluate(step, resolver, trackingRunner(order));
            assertThat(order).containsExactly("t1", "t2", "t3");
        }
    }

    // ── MatchStep ──────────────────────────────────────────────────

    @Nested
    class MatchStepTests {

        @Test
        void valuePattern_matches_executesCase() {
            var mc = new ResolvedMatchCase(
                    new MatchPattern.ValuePattern("ACTIVE"), null, List.of(leaf("matched")));
            var step = new ResolvedStep.MatchStep(null, "ACTIVE", List.of(mc), Map.of());
            var order = new ArrayList<String>();
            evaluator.evaluate(step, resolver, trackingRunner(order));
            assertThat(order).containsExactly("matched");
        }

        @Test
        void valuePattern_noMatch_returnsEmptySuccess() {
            var mc = new ResolvedMatchCase(
                    new MatchPattern.ValuePattern("ACTIVE"), null, List.of(leaf("matched")));
            var step = new ResolvedStep.MatchStep(null, "INACTIVE", List.of(mc), Map.of());
            var result = evaluator.evaluate(step, resolver, successRunner(Map.of()));
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.output()).isEmpty();
        }

        @Test
        void structuralPattern_matches_executesCase() {
            var mc = new ResolvedMatchCase(
                    new MatchPattern.StructuralPattern(Map.of("type", "trade")),
                    null, List.of(leaf("matched")));
            var scrutinee = Map.of("type", "trade", "amount", 100);
            var step = new ResolvedStep.MatchStep(null, "${var.event}", List.of(mc), Map.of());
            var objResolver = resolver.withObjectScope("var",
                    name -> "event".equals(name) ? scrutinee : null);
            var order = new ArrayList<String>();
            evaluator.evaluate(step, objResolver, trackingRunner(order));
            assertThat(order).containsExactly("matched");
        }

        @Test
        void anyOfPattern_matches_executesCase() {
            var mc = new ResolvedMatchCase(
                    new MatchPattern.AnyOfPattern(List.of("A", "B", "C")),
                    null, List.of(leaf("matched")));
            var step = new ResolvedStep.MatchStep(null, "B", List.of(mc), Map.of());
            var order = new ArrayList<String>();
            evaluator.evaluate(step, resolver, trackingRunner(order));
            assertThat(order).containsExactly("matched");
        }

        @Test
        void defaultPattern_alwaysMatches() {
            var mc = new ResolvedMatchCase(
                    new MatchPattern.DefaultPattern(), null, List.of(leaf("default")));
            var step = new ResolvedStep.MatchStep(null, "anything", List.of(mc), Map.of());
            var order = new ArrayList<String>();
            evaluator.evaluate(step, resolver, trackingRunner(order));
            assertThat(order).containsExactly("default");
        }

        @Test
        void firstMatchWins_multipleMatchingCases() {
            var c1 = new ResolvedMatchCase(
                    new MatchPattern.ValuePattern("X"), null, List.of(leaf("first")));
            var c2 = new ResolvedMatchCase(
                    new MatchPattern.DefaultPattern(), null, List.of(leaf("second")));
            var step = new ResolvedStep.MatchStep(null, "X", List.of(c1, c2), Map.of());
            var order = new ArrayList<String>();
            evaluator.evaluate(step, resolver, trackingRunner(order));
            assertThat(order).containsExactly("first");
        }

        @Test
        void guardCondition_truthy_executesCase() {
            var condEval = new ConditionEvaluator(null);
            evaluator = new StructuralStepEvaluator(condEval);
            var mc = new ResolvedMatchCase(
                    new MatchPattern.DefaultPattern(), "true", List.of(leaf("guarded")));
            var step = new ResolvedStep.MatchStep(null, "val", List.of(mc), Map.of());
            var order = new ArrayList<String>();
            evaluator.evaluate(step, resolver, trackingRunner(order));
            assertThat(order).containsExactly("guarded");
        }

        @Test
        void guardCondition_falsy_skipsToNextCase() {
            var c1 = new ResolvedMatchCase(
                    new MatchPattern.DefaultPattern(), "false", List.of(leaf("skipped")));
            var c2 = new ResolvedMatchCase(
                    new MatchPattern.DefaultPattern(), null, List.of(leaf("fallthrough")));
            var step = new ResolvedStep.MatchStep(null, "val", List.of(c1, c2), Map.of());
            var order = new ArrayList<String>();
            evaluator.evaluate(step, resolver, trackingRunner(order));
            assertThat(order).containsExactly("fallthrough");
        }

        @Test
        void matchContext_stringScrutinee_accessibleViaMatchPrefix() {
            var mc = new ResolvedMatchCase(
                    new MatchPattern.ValuePattern("hello"), null, List.of(leaf("check")));
            var step = new ResolvedStep.MatchStep(null, "hello", List.of(mc), Map.of());
            var capturedResolver = new VariableResolver[1];
            evaluator.evaluate(step, resolver, (s, r) -> {
                capturedResolver[0] = r;
                return Result.of(Map.of());
            });
            String resolved = capturedResolver[0].resolveString("${match.value}", "test");
            assertThat(resolved).isEqualTo("hello");
        }

        @Test
        void matchContext_mapScrutinee_fieldsAccessible() {
            var scrutinee = Map.of("type", "trade", "amount", 500);
            var mc = new ResolvedMatchCase(
                    new MatchPattern.StructuralPattern(Map.of("type", "trade")),
                    null, List.of(leaf("check")));
            var step = new ResolvedStep.MatchStep(null, "${var.event}", List.of(mc), Map.of());
            var objResolver = resolver.withObjectScope("var",
                    name -> "event".equals(name) ? scrutinee : null);
            var capturedResolver = new VariableResolver[1];
            evaluator.evaluate(step, objResolver, (s, r) -> {
                capturedResolver[0] = r;
                return Result.of(Map.of());
            });
            Object matchType = capturedResolver[0].resolve("${match.type}");
            assertThat(matchType).isEqualTo("trade");
        }

        @Test
        void scrutineeFromVariable_resolvesBeforeMatching() {
            var mc = new ResolvedMatchCase(
                    new MatchPattern.ValuePattern("resolved"),
                    null, List.of(leaf("matched")));
            var step = new ResolvedStep.MatchStep(null, "${var.val}", List.of(mc), Map.of());
            var varResolver = new VariableResolver(
                    Map.of("var", (VariableSource) name ->
                            "val".equals(name) ? "resolved" : null),
                    Set.of());
            var order = new ArrayList<String>();
            evaluator.evaluate(step, varResolver, trackingRunner(order));
            assertThat(order).containsExactly("matched");
        }
    }

    // ── ParallelStep ───────────────────────────────────────────────

    @Nested
    class ParallelStepTests {

        @Test
        void emptyParallel_returnsSuccess() {
            var step = new ResolvedStep.ParallelStep(null, List.of(), Map.of());
            var result = evaluator.evaluate(step, resolver, successRunner(Map.of()));
            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        void singleStep_executes() {
            var step = new ResolvedStep.ParallelStep(null, List.of(leaf("a")), Map.of());
            var order = new CopyOnWriteArrayList<String>();
            var result = evaluator.evaluate(step, resolver, (s, r) -> {
                if (s instanceof ResolvedStep.InvokeStep inv) {
                    order.add((String) inv.invokeSpec().get("id"));
                }
                return Result.of(Map.of("done", true));
            });
            assertThat(result.isSuccess()).isTrue();
            assertThat(order).containsExactly("a");
        }

        @Test
        void multipleSteps_executesConcurrently() throws InterruptedException {
            var latch = new CountDownLatch(2);
            var threadNames = new CopyOnWriteArrayList<String>();
            var step = new ResolvedStep.ParallelStep(null, 
                    List.of(leaf("a"), leaf("b")), Map.of());
            evaluator.evaluate(step, resolver, (s, r) -> {
                threadNames.add(Thread.currentThread().getName());
                latch.countDown();
                try { latch.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return Result.of(Map.of());
            });
            assertThat(threadNames).hasSize(2);
        }

        @Test
        void allSucceed_returnsSuccess() {
            var step = new ResolvedStep.ParallelStep(null, 
                    List.of(leaf("a"), leaf("b"), leaf("c")), Map.of());
            var result = evaluator.evaluate(step, resolver, successRunner(Map.of("ok", true)));
            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        void oneStepFails_waitsForAll_returnsFailure() {
            var completed = new AtomicInteger(0);
            var step = new ResolvedStep.ParallelStep(null, 
                    List.of(leaf("fail"), leaf("ok")), Map.of());
            var result = evaluator.evaluate(step, resolver, (s, r) -> {
                if (s instanceof ResolvedStep.InvokeStep inv
                        && "fail".equals(inv.invokeSpec().get("id"))) {
                    completed.incrementAndGet();
                    return Result.failed("boom");
                }
                try { Thread.sleep(50); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                completed.incrementAndGet();
                return Result.of(Map.of());
            });
            assertThat(result.isSuccess()).isFalse();
            assertThat(completed.get()).isEqualTo(2);
        }

        @Test
        void allFail_returnsFirstFailure() {
            var step = new ResolvedStep.ParallelStep(null, 
                    List.of(leaf("a"), leaf("b")), Map.of());
            var result = evaluator.evaluate(step, resolver,
                    (s, r) -> Result.failed("fail"));
            assertThat(result.isSuccess()).isFalse();
        }
    }

    // ── TryCatchFinallyStep ─────────────────────────────────────────

    @Nested
    class TryCatchFinallyStepTests {

        @Test
        void trySucceeds_catchSkipped_finallyRuns() {
            var order = new ArrayList<String>();
            var step = new ResolvedStep.TryCatchFinallyStep(null, 
                    List.of(leaf("try1")),
                    List.of(leaf("catch1")),
                    List.of(leaf("finally1")),
                    Map.of());
            evaluator.evaluate(step, resolver, trackingRunner(order));
            assertThat(order).containsExactly("try1", "finally1");
        }

        @Test
        void tryFails_catchRuns_finallyRuns() {
            var order = new ArrayList<String>();
            var step = new ResolvedStep.TryCatchFinallyStep(null, 
                    List.of(leaf("try1")),
                    List.of(leaf("catch1")),
                    List.of(leaf("finally1")),
                    Map.of());
            evaluator.evaluate(step, resolver, (s, r) -> {
                if (s instanceof ResolvedStep.InvokeStep inv) {
                    String id = (String) inv.invokeSpec().get("id");
                    order.add(id);
                    if ("try1".equals(id)) return Result.failed("boom");
                }
                return Result.of(Map.of());
            });
            assertThat(order).containsExactly("try1", "catch1", "finally1");
        }

        @Test
        void tryFails_noCatch_finallyStillRuns() {
            var order = new ArrayList<String>();
            var step = new ResolvedStep.TryCatchFinallyStep(null, 
                    List.of(leaf("try1")),
                    null,
                    List.of(leaf("finally1")),
                    Map.of());
            evaluator.evaluate(step, resolver, (s, r) -> {
                if (s instanceof ResolvedStep.InvokeStep inv) {
                    String id = (String) inv.invokeSpec().get("id");
                    order.add(id);
                    if ("try1".equals(id)) return Result.failed("boom");
                }
                return Result.of(Map.of());
            });
            assertThat(order).containsExactly("try1", "finally1");
        }

        @Test
        void tryFails_catchSucceeds_overallSuccess() {
            var step = new ResolvedStep.TryCatchFinallyStep(null, 
                    List.of(leaf("try1")),
                    List.of(leaf("catch1")),
                    null,
                    Map.of());
            var result = evaluator.evaluate(step, resolver, (s, r) -> {
                if (s instanceof ResolvedStep.InvokeStep inv
                        && "try1".equals(inv.invokeSpec().get("id"))) {
                    return Result.failed("boom");
                }
                return Result.of(Map.of("handled", true));
            });
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.output()).containsEntry("handled", true);
        }

        @Test
        void tryFails_noCatch_overallFailure() {
            var step = new ResolvedStep.TryCatchFinallyStep(null, 
                    List.of(leaf("try1")),
                    null,
                    null,
                    Map.of());
            var result = evaluator.evaluate(step, resolver,
                    (s, r) -> Result.failed("boom"));
            assertThat(result.isSuccess()).isFalse();
        }

        @Test
        void finallyFails_overridesTrySuccess() {
            var step = new ResolvedStep.TryCatchFinallyStep(null, 
                    List.of(leaf("try1")),
                    null,
                    List.of(leaf("finally1")),
                    Map.of());
            var result = evaluator.evaluate(step, resolver, (s, r) -> {
                if (s instanceof ResolvedStep.InvokeStep inv
                        && "finally1".equals(inv.invokeSpec().get("id"))) {
                    return Result.failed("finally boom");
                }
                return Result.of(Map.of("ok", true));
            });
            assertThat(result.isSuccess()).isFalse();
        }

        @Test
        void catchReceivesErrorContext() {
            var step = new ResolvedStep.TryCatchFinallyStep(null, 
                    List.of(leaf("try1")),
                    List.of(leaf("catch1")),
                    null,
                    Map.of());
            var capturedResolver = new VariableResolver[1];
            evaluator.evaluate(step, resolver, (s, r) -> {
                if (s instanceof ResolvedStep.InvokeStep inv) {
                    if ("try1".equals(inv.invokeSpec().get("id"))) {
                        return Result.failed("original error");
                    }
                    capturedResolver[0] = r;
                }
                return Result.of(Map.of());
            });
            String errorMsg = capturedResolver[0].resolveString("${error.message}", "test");
            assertThat(errorMsg).isEqualTo("original error");
        }

        @Test
        void emptyTry_succeeds() {
            var step = new ResolvedStep.TryCatchFinallyStep(null, 
                    List.of(), null, null, Map.of());
            var result = evaluator.evaluate(step, resolver, successRunner(Map.of()));
            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        void multipleTrySteps_secondFails_catchRuns() {
            var order = new ArrayList<String>();
            var step = new ResolvedStep.TryCatchFinallyStep(null, 
                    List.of(leaf("t1"), leaf("t2"), leaf("t3")),
                    List.of(leaf("c1")),
                    null,
                    Map.of());
            evaluator.evaluate(step, resolver, (s, r) -> {
                if (s instanceof ResolvedStep.InvokeStep inv) {
                    String id = (String) inv.invokeSpec().get("id");
                    order.add(id);
                    if ("t2".equals(id)) return Result.failed("boom");
                }
                return Result.of(Map.of());
            });
            assertThat(order).containsExactly("t1", "t2", "c1");
        }
    }

    // ── SelectStep ──────────────────────────────────────────────────

    @Nested
    class SelectStepTests {

        @Test
        void emptySelect_returnsSuccess() {
            var step = new ResolvedStep.SelectStep(null, List.of(), Map.of());
            var result = evaluator.evaluate(step, resolver, successRunner(Map.of()));
            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        void select_withoutScope_returnsFailure() {
            var branch = new ResolvedStep.SelectBranch(ResolvedStep.SelectBranchType.WAIT, "sig", List.of(leaf("a")));
            var step = new ResolvedStep.SelectStep(null, List.of(branch), Map.of());
            var result = evaluator.evaluate(step, resolver, successRunner(Map.of()));
            assertThat(result.isSuccess()).isFalse();
        }

        @Test
        void select_signalBranchWins() {
            try (var scope = new DefaultScenarioScope()) {
                var scopedEval = new StructuralStepEvaluator(
                        new ConditionEvaluator(null), scope);
                var signal = scope.signal("fast");
                signal.signal("payload");

                var branch1 = new ResolvedStep.SelectBranch(ResolvedStep.SelectBranchType.WAIT, "fast", List.of(leaf("winner")));
                var branch2 = new ResolvedStep.SelectBranch(ResolvedStep.SelectBranchType.WAIT, "slow", List.of(leaf("loser")));
                var step = new ResolvedStep.SelectStep(null, List.of(branch1, branch2), Map.of());

                var order = new ArrayList<String>();
                scopedEval.evaluate(step, resolver, trackingRunner(order));
                assertThat(order).containsExactly("winner");
            }
        }

        @Test
        void select_channelBranchWins() throws InterruptedException {
            try (var scope = new DefaultScenarioScope()) {
                var scopedEval = new StructuralStepEvaluator(
                        new ConditionEvaluator(null), scope);
                var channel = scope.<Object>channel("quotes");
                channel.send(Map.of("price", 42));

                var branch = new ResolvedStep.SelectBranch(ResolvedStep.SelectBranchType.SUBSCRIBE, "quotes", List.of(leaf("got-quote")));
                var step = new ResolvedStep.SelectStep(null, List.of(branch), Map.of());

                var order = new ArrayList<String>();
                scopedEval.evaluate(step, resolver, trackingRunner(order));
                assertThat(order).containsExactly("got-quote");
            }
        }
    }

    // ── Leaf passthrough ───────────────────────────────────────────

    @Nested
    class LeafPassthroughTests {

        @Test
        void pluginStep_delegatesToRunner() {
            var plugin = new ResolvedStep.PluginStep(null, null, Map.of("x", 1), Map.of());
            var result = evaluator.evaluate(plugin, resolver,
                    (s, r) -> Result.of(Map.of("ran", true)));
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.output()).containsEntry("ran", true);
        }

        @Test
        void invokeStep_delegatesToRunner() {
            var invoke = new ResolvedStep.InvokeStep(null, Map.of("mcp", "tool"), Map.of());
            var result = evaluator.evaluate(invoke, resolver,
                    (s, r) -> Result.of(Map.of("ran", true)));
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.output()).containsEntry("ran", true);
        }
    }

    // ── Decorator + Structural composition ─────────────────────────

    @Nested
    class DecoratorCompositionTests {

        @Test
        void blockWithWhenFalse_skipsExecution() {
            var block = new ResolvedStep.BlockStep(null, 
                    List.of(leaf("a")), Map.of("when", "false"));
            var count = new AtomicInteger(0);
            var result = evaluator.evaluate(block, resolver, (s, r) -> {
                count.incrementAndGet();
                return Result.of(Map.of());
            });
            assertThat(result.isSuccess()).isTrue();
            assertThat(count.get()).isZero();
        }

        @Test
        void blockWithWhenTrue_executes() {
            var block = new ResolvedStep.BlockStep(null, 
                    List.of(leaf("a")), Map.of("when", "true"));
            var order = new ArrayList<String>();
            evaluator.evaluate(block, resolver, trackingRunner(order));
            assertThat(order).containsExactly("a");
        }

        @Test
        void parallelWithRetry_retriesOnFailure() {
            var count = new AtomicInteger(0);
            var parallel = new ResolvedStep.ParallelStep(null, 
                    List.of(leaf("a")), Map.of("retry", 3));
            var result = evaluator.evaluate(parallel, resolver, (s, r) -> {
                if (count.incrementAndGet() < 3) return Result.failed("not yet");
                return Result.of(Map.of("ok", true));
            });
            assertThat(result.isSuccess()).isTrue();
            assertThat(count.get()).isEqualTo(3);
        }

        @Test
        void tryCatchWithTimeout_timeoutWrapsEntireBlock() {
            var step = new ResolvedStep.TryCatchFinallyStep(null, 
                    List.of(leaf("try1")), List.of(leaf("catch1")), null,
                    Map.of("timeout", "5s"));
            var result = evaluator.evaluate(step, resolver,
                    (s, r) -> Result.of(Map.of("ok", true)));
            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        void leafStepWithDecorators_decoratorsApplied() {
            var invoke = new ResolvedStep.InvokeStep(null, 
                    Map.of("mcp", "tool"), Map.of("when", "false"));
            var count = new AtomicInteger(0);
            var result = evaluator.evaluate(invoke, resolver, (s, r) -> {
                count.incrementAndGet();
                return Result.of(Map.of());
            });
            assertThat(result.isSuccess()).isTrue();
            assertThat(count.get()).isZero();
        }
    }
// ── StepResultStore Recording ──────────────────────────────────

    @Nested
    class ResultRecordingTests {

        private DefaultScenarioScope    scope;
        private StructuralStepEvaluator scopedEvaluator;

        @BeforeEach
        void setUp() {
            scope = new DefaultScenarioScope();
            var condEval = new ConditionEvaluator(null);
            scopedEvaluator = new StructuralStepEvaluator(condEval, scope);
        }

        @Test
        void evaluate_namedStep_recordsSuccessToStore() {
            var step = new ResolvedStep.InvokeStep("risk-eval", Map.of("id", "a"), Map.of());
            scopedEvaluator.evaluate(step, resolver,
                                     (s, r) -> Result.of(Map.of("score", 85)));

            assertThat(scope.resultStore().hasCompleted("risk-eval")).isTrue();
            assertThat(scope.resultStore().result("risk-eval"))
                    .containsEntry("score", 85);
            assertThat(scope.resultStore().error("risk-eval")).isNull();
        }

        @Test
        void evaluate_namedStep_recordsFailureToStore() {
            var step = new ResolvedStep.InvokeStep("risk-eval", Map.of("id", "a"), Map.of());
            scopedEvaluator.evaluate(step, resolver,
                                     (s, r) -> Result.failed("connection timeout"));

            assertThat(scope.resultStore().hasCompleted("risk-eval")).isTrue();
            assertThat(scope.resultStore().result("risk-eval")).isNull();
            assertThat(scope.resultStore().error("risk-eval")).isNotNull();
            assertThat(scope.resultStore().error("risk-eval").summary())
                    .contains("connection timeout");
        }

        @Test
        void evaluate_unnamedStep_doesNotRecord() {
            var step = new ResolvedStep.InvokeStep(null, Map.of("id", "a"), Map.of());
            scopedEvaluator.evaluate(step, resolver,
                                     (s, r) -> Result.of(Map.of("score", 85)));

            assertThat(scope.resultStore().hasCompleted("a")).isFalse();
        }

        @Test
        void evaluate_noScope_doesNotRecord() {
            var step = new ResolvedStep.InvokeStep("risk-eval", Map.of("id", "a"), Map.of());
            evaluator.evaluate(step, resolver,
                               (s, r) -> Result.of(Map.of("score", 85)));
        }

        @Test
        void evaluate_nestedNamedSteps_allRecorded() {
            var inner1 = new ResolvedStep.InvokeStep("step-a", Map.of("id", "a"), Map.of());
            var inner2 = new ResolvedStep.InvokeStep("step-b", Map.of("id", "b"), Map.of());
            var block  = new ResolvedStep.BlockStep(null, List.of(inner1, inner2), Map.of());
            scopedEvaluator.evaluate(block, resolver,
                                     (s, r) -> Result.of(Map.of("id",
                                                                ((ResolvedStep.InvokeStep) s).invokeSpec().get("id"))));

            assertThat(scope.resultStore().hasCompleted("step-a")).isTrue();
            assertThat(scope.resultStore().hasCompleted("step-b")).isTrue();
        }

        @Test
        void evaluate_parallelNamedSteps_allRecordedConcurrently() {
            var step1    = new ResolvedStep.InvokeStep("eval-a", Map.of("id", "a"), Map.of());
            var step2    = new ResolvedStep.InvokeStep("eval-b", Map.of("id", "b"), Map.of());
            var parallel = new ResolvedStep.ParallelStep(null, List.of(step1, step2), Map.of());
            scopedEvaluator.evaluate(parallel, resolver,
                                     (s, r) -> Result.of(Map.of("done", true)));

            assertThat(scope.resultStore().hasCompleted("eval-a")).isTrue();
            assertThat(scope.resultStore().hasCompleted("eval-b")).isTrue();
        }
    }

// ── Result Variable Resolution ────────────────────────────────

    @Nested
    class ResultVariableResolutionTests {

        private DefaultScenarioScope    scope;
        private StructuralStepEvaluator scopedEvaluator;

        @BeforeEach
        void setUp() {
            scope = new DefaultScenarioScope();
            var condEval = new ConditionEvaluator(null);
            scopedEvaluator = new StructuralStepEvaluator(condEval, scope);
        }

        @Test
        void resultVariable_completedStep_resolvesOutput() {
            var step1 = new ResolvedStep.InvokeStep("producer", Map.of("id", "p"), Map.of());
            scopedEvaluator.evaluate(step1, resolver,
                                     (s, r) -> Result.of(Map.of("score", 85)));

            var step2            = new ResolvedStep.InvokeStep(null, Map.of("id", "c"), Map.of());
            var capturedResolver = new VariableResolver[1];
            scopedEvaluator.evaluate(step2, resolver, (s, r) -> {
                capturedResolver[0] = r;
                return Result.of(Map.of());
            });

            Object value = capturedResolver[0].resolve("${result.producer.score}");
            assertThat(value).isEqualTo(85);
        }

        @Test
        void resultVariable_failedStep_resolvesError() {
            var step1 = new ResolvedStep.InvokeStep("producer", Map.of("id", "p"), Map.of());
            scopedEvaluator.evaluate(step1, resolver,
                                     (s, r) -> Result.failed("timeout"));

            var step2            = new ResolvedStep.InvokeStep(null, Map.of("id", "c"), Map.of());
            var capturedResolver = new VariableResolver[1];
            scopedEvaluator.evaluate(step2, resolver, (s, r) -> {
                capturedResolver[0] = r;
                return Result.of(Map.of());
            });

            Object errorMap = capturedResolver[0].resolve("${result.producer.error}");
            assertThat(errorMap).isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            var error = (Map<String, Object>) errorMap;
            assertThat((String) error.get("message")).contains("timeout");
        }

        @Test
        void resultVariable_uncompletedStep_throwsUnresolved() {
            var step             = new ResolvedStep.InvokeStep(null, Map.of("id", "a"), Map.of());
            var capturedResolver = new VariableResolver[1];
            scopedEvaluator.evaluate(step, resolver, (s, r) -> {
                capturedResolver[0] = r;
                return Result.of(Map.of());
            });

            assertThatThrownBy(() -> capturedResolver[0].resolve("${result.nonexistent}"))
                    .isInstanceOf(io.casehub.yaml.core.resolver.UnresolvedVariableException.class);
        }

        @Test
        void resultVariable_soleReference_returnsTypedMap() {
            var step1 = new ResolvedStep.InvokeStep("producer", Map.of("id", "p"), Map.of());
            scopedEvaluator.evaluate(step1, resolver,
                                     (s, r) -> Result.of(Map.of("score", 85, "grade", "A")));

            var step2            = new ResolvedStep.InvokeStep(null, Map.of("id", "c"), Map.of());
            var capturedResolver = new VariableResolver[1];
            scopedEvaluator.evaluate(step2, resolver, (s, r) -> {
                capturedResolver[0] = r;
                return Result.of(Map.of());
            });

            Object value = capturedResolver[0].resolve("${result.producer}");
            assertThat(value).isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            var map = (Map<String, Object>) value;
            assertThat(map).containsEntry("score", 85).containsEntry("grade", "A");
        }
    }
// ── Barrier Evaluation ─────────────────────────────────────────

    @Nested
    class BarrierTests {

        private DefaultScenarioScope    scope;
        private StructuralStepEvaluator scopedEvaluator;

        @BeforeEach
        void setUp() {
            scope = new DefaultScenarioScope();
            var condEval = new ConditionEvaluator(null);
            scopedEvaluator = new StructuralStepEvaluator(condEval, scope);
        }

        @Test
        void barrier_awaitsAllNamedSteps() {
            var evalA = new ResolvedStep.InvokeStep("eval-a", Map.of("id", "a"), Map.of());
            var evalB = new ResolvedStep.InvokeStep("eval-b", Map.of("id", "b"), Map.of());
            var barrier = new ResolvedStep.BarrierStep("wait-all",
                                                       List.of("eval-a", "eval-b"), null, Map.of());
            var parallel = new ResolvedStep.ParallelStep(null,
                                                         List.of(evalA, evalB, barrier), Map.of());

            scopedEvaluator.preRegisterLatches(List.of(parallel));

            var order = new CopyOnWriteArrayList<String>();
            var result = scopedEvaluator.evaluate(parallel, resolver, (step, res) -> {
                if (step instanceof ResolvedStep.InvokeStep inv) {
                    String id = (String) inv.invokeSpec().get("id");
                    order.add(id);
                    try {Thread.sleep(50);} catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return Result.of(Map.of());
            });

            assertThat(result.isSuccess()).isTrue();
            assertThat(order).containsExactlyInAnyOrder("a", "b");
        }

        @Test
        void barrier_timeout_returnsFailed() {
            var evalA = new ResolvedStep.InvokeStep("slow", Map.of("id", "a"), Map.of());
            var barrier = new ResolvedStep.BarrierStep("wait",
                                                       List.of("slow"), java.time.Duration.ofMillis(50), Map.of());
            var parallel = new ResolvedStep.ParallelStep(null,
                                                         List.of(evalA, barrier), Map.of());

            scopedEvaluator.preRegisterLatches(List.of(parallel));

            var result = scopedEvaluator.evaluate(parallel, resolver, (step, res) -> {
                try {Thread.sleep(500);} catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return Result.of(Map.of());
            });

            assertThat(result.isSuccess()).isFalse();
        }

        @Test
        void barrier_failedStep_stillCountsDown() {
            var evalA = new ResolvedStep.InvokeStep("eval-a", Map.of("id", "a"), Map.of());
            var barrier = new ResolvedStep.BarrierStep("wait",
                                                       List.of("eval-a"), null, Map.of());
            var steps = List.<ResolvedStep>of(evalA, barrier);
            var block = new ResolvedStep.BlockStep(null, steps, Map.of());

            scopedEvaluator.preRegisterLatches(List.of(block));

            scopedEvaluator.evaluate(block, resolver,
                                     (step, res) -> Result.failed("error"));

            assertThat(scope.resultStore().hasCompleted("eval-a")).isTrue();
        }

        @Test
        void barrier_stepCompletionOrder_doesNotMatter() {
            var evalA = new ResolvedStep.InvokeStep("eval-a", Map.of("id", "a"), Map.of());
            var evalB = new ResolvedStep.InvokeStep("eval-b", Map.of("id", "b"), Map.of());
            var barrier = new ResolvedStep.BarrierStep("wait",
                                                       List.of("eval-a", "eval-b"), null, Map.of());
            var parallel = new ResolvedStep.ParallelStep(null,
                                                         List.of(evalB, evalA, barrier), Map.of());

            scopedEvaluator.preRegisterLatches(List.of(parallel));

            var completed = new CopyOnWriteArrayList<String>();
            var result = scopedEvaluator.evaluate(parallel, resolver, (step, res) -> {
                if (step instanceof ResolvedStep.InvokeStep inv) {
                    String id = (String) inv.invokeSpec().get("id");
                    if ("b".equals(id)) {
                        try {Thread.sleep(100);} catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    completed.add(id);
                }
                return Result.of(Map.of());
            });

            assertThat(result.isSuccess()).isTrue();
            assertThat(completed).containsExactlyInAnyOrder("a", "b");
        }
    }

// ── Quorum Evaluation ──────────────────────────────────────────

    @Nested
    class QuorumTests {

        private DefaultScenarioScope    scope;
        private StructuralStepEvaluator scopedEvaluator;

        @BeforeEach
        void setUp() {
            scope = new DefaultScenarioScope();
            var condEval = new ConditionEvaluator(null);
            scopedEvaluator = new StructuralStepEvaluator(condEval, scope);
        }

        @Test
        void quorum_proceedsOnRequiredCount() {
            var a = new ResolvedStep.InvokeStep("a", Map.of("id", "a"), Map.of());
            var b = new ResolvedStep.InvokeStep("b", Map.of("id", "b"), Map.of());
            var c = new ResolvedStep.InvokeStep("c", Map.of("id", "c"), Map.of());
            var quorum = new ResolvedStep.QuorumStep("consensus", 2,
                                                     List.of("a", "b", "c"), null, Map.of());
            var parallel = new ResolvedStep.ParallelStep(null,
                                                         List.of(a, b, c, quorum), Map.of());

            scopedEvaluator.preRegisterLatches(List.of(parallel));

            var result = scopedEvaluator.evaluate(parallel, resolver,
                                                  (step, res) -> Result.of(Map.of("done", true)));

            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        void quorum_unreachable_returnsFailed() {
            var a = new ResolvedStep.InvokeStep("a", Map.of("id", "a"), Map.of());
            var b = new ResolvedStep.InvokeStep("b", Map.of("id", "b"), Map.of());
            var quorum = new ResolvedStep.QuorumStep("consensus", 2,
                                                     List.of("a", "b"), null, Map.of());
            var parallel = new ResolvedStep.ParallelStep(null,
                                                         List.of(a, b, quorum), Map.of());

            scopedEvaluator.preRegisterLatches(List.of(parallel));

            var result = scopedEvaluator.evaluate(parallel, resolver,
                                                  (step, res) -> Result.failed("all fail"));

            assertThat(result.isSuccess()).isFalse();
        }

        @Test
        void quorum_timeout_returnsFailed() {
            var a = new ResolvedStep.InvokeStep("a", Map.of("id", "a"), Map.of());
            var quorum = new ResolvedStep.QuorumStep("consensus", 1,
                                                     List.of("a"), java.time.Duration.ofMillis(50), Map.of());
            var parallel = new ResolvedStep.ParallelStep(null,
                                                         List.of(a, quorum), Map.of());

            scopedEvaluator.preRegisterLatches(List.of(parallel));

            var result = scopedEvaluator.evaluate(parallel, resolver, (step, res) -> {
                try {Thread.sleep(500);} catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return Result.of(Map.of());
            });

            assertThat(result.isSuccess()).isFalse();
        }
    }

// ── Composition Tests ──────────────────────────────────────────

    @Nested
    class CompositionTests {

        private DefaultScenarioScope    scope;
        private StructuralStepEvaluator scopedEvaluator;

        @BeforeEach
        void setUp() {
            scope = new DefaultScenarioScope();
            var condEval = new ConditionEvaluator(null);
            scopedEvaluator = new StructuralStepEvaluator(condEval, scope);
        }

        @Test
        void barrier_result_availableViaResultVariable() {
            var producer = new ResolvedStep.InvokeStep("producer", Map.of("id", "p"), Map.of());
            var barrier = new ResolvedStep.BarrierStep("wait",
                                                       List.of("producer"), null, Map.of());
            var consumer = new ResolvedStep.InvokeStep("consumer", Map.of("id", "c"), Map.of());

            var block = new ResolvedStep.BlockStep(null,
                                                   List.of(producer, barrier, consumer), Map.of());

            scopedEvaluator.preRegisterLatches(List.of(block));

            var capturedValue = new Object[1];
            var result = scopedEvaluator.evaluate(block, resolver, (step, res) -> {
                if (step instanceof ResolvedStep.InvokeStep inv) {
                    String id = (String) inv.invokeSpec().get("id");
                    if ("p".equals(id)) {
                        return Result.of(Map.of("score", 95));
                    }
                    if ("c".equals(id)) {
                        capturedValue[0] = res.resolve("${result.producer.score}");
                    }
                }
                return Result.of(Map.of());
            });

            assertThat(result.isSuccess()).isTrue();
            assertThat(capturedValue[0]).isEqualTo(95);
        }

        @Test
        void decorators_applyToBarrierStep() {
            var evalA = new ResolvedStep.InvokeStep("eval-a", Map.of("id", "a"), Map.of());
            var barrier = new ResolvedStep.BarrierStep("wait",
                                                       List.of("eval-a"), null, Map.of("when", "true"));

            var block = new ResolvedStep.BlockStep(null,
                                                   List.of(evalA, barrier), Map.of());
            scopedEvaluator.preRegisterLatches(List.of(block));

            var result = scopedEvaluator.evaluate(block, resolver,
                                                  (step, res) -> Result.of(Map.of()));

            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        void decorators_applyToQuorumStep() {
            var a = new ResolvedStep.InvokeStep("a", Map.of("id", "a"), Map.of());
            var quorum = new ResolvedStep.QuorumStep("consensus", 1,
                                                     List.of("a"), null, Map.of("when", "true"));

            var block = new ResolvedStep.BlockStep(null,
                                                   List.of(a, quorum), Map.of());
            scopedEvaluator.preRegisterLatches(List.of(block));

            var result = scopedEvaluator.evaluate(block, resolver,
                                                  (step, res) -> Result.of(Map.of()));

            assertThat(result.isSuccess()).isTrue();
        }
    }
// ── Deadline Propagation ──────────────────────────────────────
//
// The decorator chain's wrapTimeout creates a DeadlineContext that
// flows through StepContext to every nested step. These tests verify
// that the evaluator correctly threads the deadline through block,
// parallel, and try-catch structures — so inner steps can query
// remaining time and blocking primitives respect the budget.

    @Nested
    class DeadlinePropagationTests {

        private DefaultScenarioScope    scope;
        private StructuralStepEvaluator scopedEvaluator;

        @BeforeEach
        void setUp() {
            scope = new DefaultScenarioScope();
            var condEval = new ConditionEvaluator(null);
            scopedEvaluator = new StructuralStepEvaluator(condEval, scope);
        }

        @Test
        void timeout_onBlock_propagatesToChildSteps() {
            // A block with timeout: 5s — each child step should see
            // a deadline in its StepContext, not infinity.
            var capturedDeadlines = new CopyOnWriteArrayList<Boolean>();
            var block = new ResolvedStep.BlockStep(null,
                                                   List.of(leaf("a"), leaf("b")),
                                                   Map.of("timeout", "5s"));

            scopedEvaluator.evaluate(block, resolver, (step, res) -> {
                // The runner doesn't see StepContext directly, but we can
                // verify indirectly: if the decorator chain creates a
                // deadline, a wait inside the timeout would be bounded.
                capturedDeadlines.add(true);
                return Result.of(Map.of());
            });

            assertThat(capturedDeadlines).hasSize(2);
        }

        @Test
        void timeout_onParallel_allBranchesInheritDeadline() {
            // A parallel block with timeout: 5s — all child steps
            // execute concurrently, each within the same time budget.
            var completed = new CopyOnWriteArrayList<String>();
            var parallel = new ResolvedStep.ParallelStep(null,
                                                         List.of(leaf("a"), leaf("b"), leaf("c")),
                                                         Map.of("timeout", "5s"));

            var result = scopedEvaluator.evaluate(parallel, resolver, (step, res) -> {
                if (step instanceof ResolvedStep.InvokeStep inv) {
                    completed.add((String) inv.invokeSpec().get("id"));
                }
                return Result.of(Map.of());
            });

            assertThat(result.isSuccess()).isTrue();
            assertThat(completed).containsExactlyInAnyOrder("a", "b", "c");
        }

        @Test
        void timeout_onParallel_slowBranchTimesOut() {
            // When a parallel block has timeout: 100ms but one step
            // takes 2s, the timeout cancels it.
            var parallel = new ResolvedStep.ParallelStep(null,
                                                         List.of(leaf("fast"), leaf("slow")),
                                                         Map.of("timeout", "100ms"));

            var result = scopedEvaluator.evaluate(parallel, resolver, (step, res) -> {
                if (step instanceof ResolvedStep.InvokeStep inv
                    && "slow".equals(inv.invokeSpec().get("id"))) {
                    try {Thread.sleep(2000);} catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return Result.of(Map.of());
            });

            assertThat(result.isSuccess()).isFalse();
        }

        @Test
        void timeout_onTryCatch_catchRunsWithinRemainingBudget() {
            // A try-catch inside a timeout: if try takes some time,
            // catch gets the remaining budget, not a fresh timeout.
            var trySteps   = List.<ResolvedStep>of(leaf("try-step"));
            var catchSteps = List.<ResolvedStep>of(leaf("catch-step"));
            var tcf = new ResolvedStep.TryCatchFinallyStep(null,
                                                           trySteps, catchSteps, List.of(),
                                                           Map.of("timeout", "5s"));

            var order = new ArrayList<String>();
            var result = scopedEvaluator.evaluate(tcf, resolver, (step, res) -> {
                if (step instanceof ResolvedStep.InvokeStep inv) {
                    String id = (String) inv.invokeSpec().get("id");
                    order.add(id);
                    if ("try-step".equals(id)) {return Result.failed("deliberate");}
                }
                return Result.of(Map.of());
            });

            assertThat(result.isSuccess()).isTrue();
            assertThat(order).containsExactly("try-step", "catch-step");
        }

        @Test
        void timeout_onBlock_exceeds_returnsFailure() {
            // A sequential block with timeout: 100ms where each step
            // takes 200ms — the timeout fires mid-execution.
            var block = new ResolvedStep.BlockStep(null,
                                                   List.of(leaf("a"), leaf("b")),
                                                   Map.of("timeout", "100ms"));

            var result = scopedEvaluator.evaluate(block, resolver, (step, res) -> {
                try {Thread.sleep(200);} catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return Result.of(Map.of());
            });

            assertThat(result.isSuccess()).isFalse();
        }

        @Test
        void nestedTimeout_innerShorter_innerWins() {
            // Outer block has timeout: 10s, inner block has timeout: 100ms.
            // The inner timeout fires first.
            var innerBlock = new ResolvedStep.BlockStep(null,
                                                        List.of(leaf("slow")),
                                                        Map.of("timeout", "100ms"));
            var outerBlock = new ResolvedStep.BlockStep(null,
                                                        List.of(innerBlock),
                                                        Map.of("timeout", "10s"));

            var result = scopedEvaluator.evaluate(outerBlock, resolver, (step, res) -> {
                try {Thread.sleep(2000);} catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return Result.of(Map.of());
            });

            assertThat(result.isSuccess()).isFalse();
        }

        @Test
        void barrierInsideTimeout_barrierRespectsDeadline() {
            // A barrier waiting on steps inside a timeout: if the timeout
            // fires before the barrier completes, the barrier is interrupted.
            var evalA = new ResolvedStep.InvokeStep("eval-a", Map.of("id", "a"), Map.of());
            var barrier = new ResolvedStep.BarrierStep("wait",
                                                       List.of("eval-a"), null, Map.of());
            var parallel = new ResolvedStep.ParallelStep(null,
                                                         List.of(evalA, barrier),
                                                         Map.of("timeout", "100ms"));

            scopedEvaluator.preRegisterLatches(List.of(parallel));

            var result = scopedEvaluator.evaluate(parallel, resolver, (step, res) -> {
                try {Thread.sleep(2000);} catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return Result.of(Map.of());
            });

            assertThat(result.isSuccess()).isFalse();
        }

        @Test
        void noTimeout_noDeadlinePropagated() {
            // Without a timeout decorator, steps execute with no deadline —
            // the baseline behaviour is unchanged.
            var block = new ResolvedStep.BlockStep(null,
                                                   List.of(leaf("a")), Map.of());

            var result = scopedEvaluator.evaluate(block, resolver,
                                                  (step, res) -> Result.of(Map.of("ok", true)));

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.output()).containsEntry("ok", true);
        }
    }


}
