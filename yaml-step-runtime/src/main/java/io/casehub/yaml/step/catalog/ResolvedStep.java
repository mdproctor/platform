package io.casehub.yaml.step.catalog;

import java.util.List;
import java.util.Map;

public sealed interface ResolvedStep permits
                                     ResolvedStep.PluginStep,
                                     ResolvedStep.InvokeStep,
                                     ResolvedStep.BlockStep,
                                     ResolvedStep.IfElseStep,
                                     ResolvedStep.MatchStep,
                                     ResolvedStep.ParallelStep,
                                     ResolvedStep.TryCatchFinallyStep,
                                     ResolvedStep.SelectStep,
                                     ResolvedStep.BarrierStep,
                                     ResolvedStep.QuorumStep {

    Map<String, Object> decorators();

    default String name() {return null;}

    record PluginStep(
            String name,
            String actionName,
            Map<String, Object> params,
            Map<String, Object> decorators) implements ResolvedStep {

        public PluginStep {
            params     = Map.copyOf(params);
            decorators = Map.copyOf(decorators);
        }
    }

    record InvokeStep(
            String name,
            Map<String, Object> invokeSpec,
            Map<String, Object> decorators) implements ResolvedStep {

        public InvokeStep {
            invokeSpec = Map.copyOf(invokeSpec);
            decorators = Map.copyOf(decorators);
        }
    }

    record BlockStep(
            String name,
            List<ResolvedStep> steps,
            Map<String, Object> decorators) implements ResolvedStep {

        public BlockStep {
            steps      = List.copyOf(steps);
            decorators = Map.copyOf(decorators);
        }
    }

    record IfElseStep(
            String name,
            String condition,
            List<ResolvedStep> thenSteps,
            List<ResolvedStep> elseSteps,
            Map<String, Object> decorators) implements ResolvedStep {

        public IfElseStep {
            thenSteps  = List.copyOf(thenSteps);
            elseSteps  = elseSteps != null ? List.copyOf(elseSteps) : List.of();
            decorators = Map.copyOf(decorators);
        }
    }

    record MatchStep(
            String name,
            String scrutinee,
            List<ResolvedMatchCase> cases,
            Map<String, Object> decorators) implements ResolvedStep {

        public MatchStep {
            cases      = List.copyOf(cases);
            decorators = Map.copyOf(decorators);
        }
    }

    record ParallelStep(
            String name,
            List<ResolvedStep> steps,
            Map<String, Object> decorators) implements ResolvedStep {

        public ParallelStep {
            steps      = List.copyOf(steps);
            decorators = Map.copyOf(decorators);
        }
    }

    record TryCatchFinallyStep(
            String name,
            List<ResolvedStep> trySteps,
            List<ResolvedStep> catchSteps,
            List<ResolvedStep> finallySteps,
            Map<String, Object> decorators) implements ResolvedStep {

        public TryCatchFinallyStep {
            trySteps     = List.copyOf(trySteps);
            catchSteps   = catchSteps != null ? List.copyOf(catchSteps) : List.of();
            finallySteps = finallySteps != null ? List.copyOf(finallySteps) : List.of();
            decorators   = Map.copyOf(decorators);
        }
    }

    enum SelectBranchType {SUBSCRIBE, WAIT}

    record SelectBranch(
            SelectBranchType type,
            String name,
            List<ResolvedStep> steps) {

        public SelectBranch {
            if (type == null) {
                throw new IllegalArgumentException("SelectBranch type must not be null");
            }
            steps = List.copyOf(steps);
        }
    }

    record SelectStep(
            String name,
            List<SelectBranch> branches,
            Map<String, Object> decorators) implements ResolvedStep {

        public SelectStep {
            branches   = List.copyOf(branches);
            decorators = Map.copyOf(decorators);
        }
    }

    record BarrierStep(
            String name,
            List<String> awaitSteps,
            java.time.Duration timeout,
            Map<String, Object> decorators) implements ResolvedStep {

        public BarrierStep {
            if (awaitSteps == null || awaitSteps.isEmpty()) {
                throw new IllegalArgumentException("barrier 'await' must be non-empty");
            }
            awaitSteps = List.copyOf(awaitSteps);
            decorators = Map.copyOf(decorators);
        }
    }

    record QuorumStep(
            String name,
            int required,
            List<String> ofSteps,
            java.time.Duration timeout,
            Map<String, Object> decorators) implements ResolvedStep {

        public QuorumStep {
            if (required <= 0) {throw new IllegalArgumentException("quorum 'required' must be > 0");}
            if (required > ofSteps.size()) {
                throw new IllegalArgumentException(
                        "quorum 'required' (" + required + ") > 'of' (" + ofSteps.size() + ")");
            }
            ofSteps    = List.copyOf(ofSteps);
            decorators = Map.copyOf(decorators);
        }
    }
}
