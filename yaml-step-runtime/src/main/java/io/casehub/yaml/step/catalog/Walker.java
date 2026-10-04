package io.casehub.yaml.step.catalog;

import io.casehub.yaml.plugin.api.PluginRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

public final class Walker {

    private static final Logger LOG = Logger.getLogger(Walker.class.getName());

    private static final Set<String> RESERVED_KEYS = Set.of(
            "step", "invoke",
            "if", "then", "else", "match", "cases", "block",
            "try", "catch", "finally", "select",
            "on-success", "on-failure", "forEach", "loop",
            "retry", "timeout", "delay", "on-error", "trigger",
            "transform", "signal", "publish", "transition",
            "parallel", "semaphore", "barrier", "quorum", "race");

    private static final Set<String> STRUCTURAL_COMPANIONS = Set.of("then", "else", "cases", "catch", "finally");
    private static final Set<String> REMOVED_KEYS = Set.of("steps", "do");
    static final         int         MAX_DEPTH             = 32;


    private Walker() {}

    private static Map<String, Object> stripKeys(
            Map<String, Object> map, Set<String> keysToStrip) {
        var result = new LinkedHashMap<String, Object>();
        for (var e : map.entrySet()) {
            if (!keysToStrip.contains(e.getKey())) {
                result.put(e.getKey(), e.getValue());
            }
        }
        return result;
    }

    public static List<ResolvedStep> resolve(
            List<Map<String, Object>> steps, PluginRegistry registry) {
        Set<String>        seenNames = new java.util.HashSet<>();
        List<ResolvedStep> result    = resolve(steps, registry, 0, "root", seenNames);
        validateBarrierQuorumReferences(result, seenNames);
        return result;
    }

    private static List<ResolvedStep> resolve(
            List<Map<String, Object>> steps, PluginRegistry registry, int depth, String path, Set<String> seenNames) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException(
                    "Maximum nesting depth (" + MAX_DEPTH + ") exceeded at: " + path);
        }
        List<ResolvedStep> result = new ArrayList<>(steps.size());
        for (int i = 0; i < steps.size(); i++) {
            result.add(resolveOne(steps.get(i), registry, i, depth, path, seenNames));
        }
        return result;
    }


    @SuppressWarnings("unchecked")
    private static ResolvedStep resolveOne(
            Map<String, Object> step, PluginRegistry registry, int index, int depth, String path,
            Set<String> seenNames) {
        if (step.isEmpty()) {
            throw new IllegalArgumentException(
                    path + " → Step " + index + ": empty step map");
        }

        for (String key : step.keySet()) {
            if (REMOVED_KEYS.contains(key)) {
                throw new IllegalArgumentException(
                        path + " → Step " + index + ": '" + key
                        + "' is no longer valid — use inline sibling keys, or 'block' for multiple actions");
            }
        }

        Map<String, Object> decorators    = new LinkedHashMap<>();
        Map<String, Object> companions    = new LinkedHashMap<>();
        Map<String, Object> invokeSpec    = null;
        String              matchedAction = null;
        Map<String, Object> actionParams  = null;

        String  structuralType  = null;
        Object  structuralValue = null;
        Object  ifValue         = null;
        boolean hasIf           = false;
        Object  matchValue      = null;
        boolean hasMatch        = false;
        String  stepName        = null;

        for (Map.Entry<String, Object> e : step.entrySet()) {
            String key = e.getKey();

            if ("step".equals(key)) {
                stepName = (String) e.getValue();
            } else if ("invoke".equals(key)) {
                invokeSpec = (Map<String, Object>) e.getValue();
            } else if ("block".equals(key) || "parallel".equals(key) || "try".equals(key) || "select".equals(key) || "barrier".equals(key) || "quorum".equals(key)) {
                structuralType  = key;
                structuralValue = e.getValue();
            } else if ("if".equals(key)) {
                hasIf   = true;
                ifValue = e.getValue();
                decorators.put("if", ifValue);
            } else if ("match".equals(key)) {
                hasMatch   = true;
                matchValue = e.getValue();
            } else if (STRUCTURAL_COMPANIONS.contains(key)) {
                companions.put(key, e.getValue());
            } else if (RESERVED_KEYS.contains(key)) {
                decorators.put(key, e.getValue());
            } else {
                var entry = registry.resolve(key);
                if (entry.isPresent()) {
                    if (matchedAction != null) {
                        throw new IllegalArgumentException(
                                path + " → Step " + index + ": ambiguous — multiple action keys matched: '"
                                + matchedAction + "' and '" + key + "'");
                    }
                    matchedAction = key;
                    actionParams  = e.getValue() instanceof Map
                                    ? (Map<String, Object>) e.getValue()
                                    : Map.of();
                } else {
                    throw new IllegalArgumentException(
                            path + " → Step " + index + ": unknown key '" + key
                            + "'. Available actions: " + registry.availableActions());
                }
            }
        }

        if (stepName != null && !seenNames.add(stepName)) {
            throw new IllegalArgumentException(
                    path + " → Step " + index + ": duplicate step name '" + stepName + "'");
        }

        if (hasIf && companions.containsKey("then")) {
            decorators.remove("if");
            structuralType = "if";
        }

        if (hasMatch && companions.containsKey("cases")) {
            if (structuralType != null) {
                throw new IllegalArgumentException(
                        path + " → Step " + index + ": ambiguous — structural keyword '" + structuralType
                        + "' and 'match' both present");
            }
            structuralType = "match";
        }

        if (structuralType != null && matchedAction != null) {
            throw new IllegalArgumentException(
                    path + " → Step " + index + ": ambiguous — structural keyword '" + structuralType
                    + "' and action key '" + matchedAction + "' both present");
        }
        if (structuralType != null && invokeSpec != null) {
            throw new IllegalArgumentException(
                    path + " → Step " + index + ": ambiguous — structural keyword '" + structuralType
                    + "' and 'invoke' both present");
        }

        if (companions.containsKey("then") && !hasIf) {
            throw new IllegalArgumentException(
                    path + " → Step " + index + ": 'then' requires an 'if' condition");
        }
        if (companions.containsKey("else") && !companions.containsKey("then")) {
            throw new IllegalArgumentException(
                    path + " → Step " + index + ": 'else' requires 'then'");
        }
        if (companions.containsKey("cases") && !"match".equals(structuralType)) {
            throw new IllegalArgumentException(
                    path + " → Step " + index + ": 'cases' requires 'match'");
        }
        if (hasMatch && !companions.containsKey("cases")) {
            throw new IllegalArgumentException(
                    path + " → Step " + index + ": 'match' requires 'cases'");
        }
        if (companions.containsKey("catch") && !"try".equals(structuralType)) {
            throw new IllegalArgumentException(
                    path + " → Step " + index + ": 'catch' requires 'try'");
        }
        if (companions.containsKey("finally") && !"try".equals(structuralType)) {
            throw new IllegalArgumentException(
                    path + " → Step " + index + ": 'finally' requires 'try'");
        }
        if ("try".equals(structuralType) && !companions.containsKey("catch") && !companions.containsKey("finally")) {
            LOG.warning(path + " → Step " + index + ": 'try' without 'catch' or 'finally' — "
                        + "the try block has no error handling or cleanup");
        }

        String stepPath = path + " → Step " + index;
        if ("if".equals(structuralType)) {
            var thenSteps = (List<Map<String, Object>>) companions.get("then");
            var elseSteps = (List<Map<String, Object>>) companions.get("else");
            return new ResolvedStep.IfElseStep(
                    stepName,
                    (String) ifValue,
                    resolve(thenSteps, registry, depth + 1, stepPath + " → then", seenNames),
                    elseSteps != null ? resolve(elseSteps, registry, depth + 1, stepPath + " → else", seenNames) : null,
                    decorators);
        }
        if ("block".equals(structuralType)) {
            return new ResolvedStep.BlockStep(
                    stepName,
                    resolve((List<Map<String, Object>>) structuralValue, registry, depth + 1, stepPath + " → block", seenNames),
                    decorators);
        }
        if ("match".equals(structuralType)) {
            var cases = (List<Map<String, Object>>) companions.get("cases");
            return new ResolvedStep.MatchStep(
                    stepName,
                    (String) matchValue,
                    resolveMatchCases(cases, registry, index, depth, stepPath, seenNames),
                    decorators);
        }
        if ("parallel".equals(structuralType)) {
            return new ResolvedStep.ParallelStep(
                    stepName,
                    resolve((List<Map<String, Object>>) structuralValue, registry, depth + 1, stepPath + " → parallel", seenNames),
                    decorators);
        }
        if ("try".equals(structuralType)) {
            var trySteps     = (List<Map<String, Object>>) structuralValue;
            var catchSteps   = (List<Map<String, Object>>) companions.get("catch");
            var finallySteps = (List<Map<String, Object>>) companions.get("finally");
            return new ResolvedStep.TryCatchFinallyStep(
                    stepName,
                    resolve(trySteps, registry, depth + 1, stepPath + " → try", seenNames),
                    catchSteps != null ? resolve(catchSteps, registry, depth + 1, stepPath + " → catch", seenNames) : null,
                    finallySteps != null ? resolve(finallySteps, registry, depth + 1, stepPath + " → finally", seenNames) : null,
                    decorators);
        }
        if ("select".equals(structuralType)) {
            var branchMaps = (List<Map<String, Object>>) structuralValue;
            return new ResolvedStep.SelectStep(
                    stepName,
                    resolveSelectBranches(branchMaps, registry, depth, stepPath, seenNames),
                    decorators);
        }
        if ("barrier".equals(structuralType)) {
            @SuppressWarnings("unchecked")
            var barrierMap = (Map<String, Object>) structuralValue;
            @SuppressWarnings("unchecked")
            var awaitList = (List<String>) barrierMap.get("await");
            if (awaitList == null || awaitList.isEmpty()) {
                throw new IllegalArgumentException(
                        stepPath + ": barrier 'await' must be a non-empty list of step names");
            }
            var timeoutStr = (String) barrierMap.get("timeout");
            return new ResolvedStep.BarrierStep(stepName, awaitList,
                    io.casehub.yaml.core.orchestration.DurationParser.parseOrNull(timeoutStr), decorators);
        }
        if ("quorum".equals(structuralType)) {
            @SuppressWarnings("unchecked")
            var quorumMap = (Map<String, Object>) structuralValue;
            var required = ((Number) quorumMap.get("required")).intValue();
            @SuppressWarnings("unchecked")
            var ofList = (List<String>) quorumMap.get("of");
            if (ofList == null || ofList.isEmpty()) {
                throw new IllegalArgumentException(
                        stepPath + ": quorum 'of' must be a non-empty list of step names");
            }
            var timeoutStr = (String) quorumMap.get("timeout");
            return new ResolvedStep.QuorumStep(stepName, required, ofList,
                    io.casehub.yaml.core.orchestration.DurationParser.parseOrNull(timeoutStr), decorators);
        }
        if (matchedAction != null) {
            return new ResolvedStep.PluginStep(stepName, matchedAction, actionParams, decorators);
        }
        if (invokeSpec != null) {
            return new ResolvedStep.InvokeStep(stepName, invokeSpec, decorators);
        }

        throw new IllegalArgumentException(
                stepPath + ": no step type identified. Available actions: "
                + registry.availableActions());
    }

    @SuppressWarnings("unchecked")
    private static List<ResolvedMatchCase> resolveMatchCases(
            List<Map<String, Object>> cases, PluginRegistry registry, int stepIndex, int depth, String path,
            Set<String> seenNames) {
        List<ResolvedMatchCase> result = new ArrayList<>(cases.size());
        for (int i = 0; i < cases.size(); i++) {
            Map<String, Object> caseMap    = cases.get(i);
            boolean             isDefault  = caseMap.containsKey("default");
            boolean             hasPattern = caseMap.containsKey("pattern");

            if (isDefault && hasPattern) {
                throw new IllegalArgumentException(
                        path + ", case " + i
                        + ": pattern and default are mutually exclusive in a case entry");
            }
            if (!isDefault && !hasPattern) {
                throw new IllegalArgumentException(
                        path + ", case " + i
                        + ": case entry must contain either 'pattern' or 'default'");
            }

            if (isDefault && i < cases.size() - 1) {
                throw new IllegalArgumentException(
                        path + ": default must be the last case");
            }

            String casePath = path + " → case " + i;
            if (isDefault) {
                var steps = (List<Map<String, Object>>) caseMap.get("default");
                result.add(new ResolvedMatchCase(
                        new io.casehub.yaml.core.step.MatchPattern.DefaultPattern(),
                        null,
                        resolve(steps, registry, depth + 1, casePath + " → default", seenNames)));
            } else {
                var                                    patternObj = caseMap.get("pattern");
                io.casehub.yaml.core.step.MatchPattern pattern;
                if (patternObj instanceof Map) {
                    pattern = new io.casehub.yaml.core.step.MatchPattern.StructuralPattern(
                            (Map<String, Object>) patternObj);
                } else if (patternObj instanceof List) {
                    pattern = new io.casehub.yaml.core.step.MatchPattern.AnyOfPattern(
                            (List<Object>) patternObj);
                } else {
                    pattern = new io.casehub.yaml.core.step.MatchPattern.ValuePattern(patternObj);
                }
                String guard = caseMap.containsKey("guard")
                               ? String.valueOf(caseMap.get("guard")) : null;
                var rest = stripKeys(caseMap, Set.of("pattern", "when", "guard"));
                List<ResolvedStep> caseSteps = rest.isEmpty()
                        ? List.of()
                        : List.of(resolveOne(rest, registry, i, depth + 1, casePath, seenNames));
                result.add(new ResolvedMatchCase(pattern, guard, caseSteps));
            }
        }

        boolean hasDefault = result.stream()
                                   .anyMatch(c -> c.pattern() instanceof io.casehub.yaml.core.step.MatchPattern.DefaultPattern);
        if (!hasDefault) {
            LOG.warning(path + ": match/cases has no default case — "
                        + "unmatched values will be silently skipped");
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<ResolvedStep.SelectBranch> resolveSelectBranches(
            List<Map<String, Object>> branches, PluginRegistry registry, int depth, String path,
            Set<String> seenNames) {
        List<ResolvedStep.SelectBranch> result = new ArrayList<>(branches.size());
        for (int i = 0; i < branches.size(); i++) {
            Map<String, Object>           branchMap  = branches.get(i);
            String                        branchPath = path + " → select[" + i + "]";
            ResolvedStep.SelectBranchType type;
            String                        name;
            if (branchMap.containsKey("subscribe")) {
                type = ResolvedStep.SelectBranchType.SUBSCRIBE;
                Object subSpec = branchMap.get("subscribe");
                if (subSpec instanceof Map<?, ?> subMap) {
                    name = String.valueOf(subMap.get("channel"));
                } else {
                    name = String.valueOf(subSpec);
                }
            } else if (branchMap.containsKey("wait")) {
                type = ResolvedStep.SelectBranchType.WAIT;
                name = String.valueOf(branchMap.get("wait"));
            } else {
                throw new IllegalArgumentException(
                        branchPath + ": select branch must contain 'subscribe' or 'wait'");
            }
            var rest = stripKeys(branchMap, Set.of("subscribe", "wait"));
            var branchSteps = rest.isEmpty()
                    ? List.<ResolvedStep>of()
                    : List.of(resolveOne(rest, registry, 0, depth + 1, branchPath, seenNames));
            result.add(new ResolvedStep.SelectBranch(type, name, branchSteps));
        }
        return result;
    }

    private static void validateBarrierQuorumReferences(
            List<ResolvedStep> steps, Set<String> knownNames) {
        for (ResolvedStep step : steps) {
            switch (step) {
                case ResolvedStep.BarrierStep b -> {
                    for (String name : b.awaitSteps()) {
                        if (!knownNames.contains(name)) {
                            throw new IllegalArgumentException(
                                    "barrier '" + b.name() + "' awaits unknown step '" + name + "'");
                        }
                    }
                }
                case ResolvedStep.QuorumStep q -> {
                    for (String name : q.ofSteps()) {
                        if (!knownNames.contains(name)) {
                            throw new IllegalArgumentException(
                                    "quorum '" + q.name() + "' references unknown step '" + name + "'");
                        }
                    }
                }
                case ResolvedStep.BlockStep b -> validateBarrierQuorumReferences(b.steps(), knownNames);
                case ResolvedStep.ParallelStep p -> validateBarrierQuorumReferences(p.steps(), knownNames);
                case ResolvedStep.TryCatchFinallyStep t -> {
                    validateBarrierQuorumReferences(t.trySteps(), knownNames);
                    validateBarrierQuorumReferences(t.catchSteps(), knownNames);
                    validateBarrierQuorumReferences(t.finallySteps(), knownNames);
                }
                default -> {}
            }
        }
    }


}
