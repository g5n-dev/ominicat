package io.ominicat.conversation.flow;

import io.ominicat.core.Processor;
import io.ominicat.core.spi.ProcessorFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A small declarative sequential flow; programmatic definitions support conditional branches. */
public final class StepFlowFactory implements ProcessorFactory {
    @Override public String type() { return "step-flow"; }
    @Override public Processor create(Map<String, String> options) {
        String[] ids = options.getOrDefault("steps", "start,end").split(",", -1);
        var allowed = new java.util.HashSet<>(Set.of("steps", "initialStep", "inputRole", "definitionVersion", "maxRememberedSegments"));
        for (String id : ids) { allowed.add("step." + id + ".prompt"); allowed.add("step." + id + ".next"); }
        for (String key : options.keySet()) if (!allowed.contains(key)) throw new IllegalArgumentException("unknown step-flow option: " + key);
        var steps = new ArrayList<StepFlowProcessor.Step>();
        for (int index = 0; index < ids.length; index++) {
            String id = ids[index];
            String target = options.getOrDefault("step." + id + ".next", index + 1 < ids.length ? ids[index + 1] : "");
            var transitions = target.isBlank() ? List.<StepFlowProcessor.Transition>of()
                    : List.of(new StepFlowProcessor.Transition(target, transcript -> true));
            steps.add(new StepFlowProcessor.Step(id, options.getOrDefault("step." + id + ".prompt", ""), transitions));
        }
        return new StepFlowProcessor(options.getOrDefault("definitionVersion", "1"), options.getOrDefault("initialStep", ids[0]),
                options.getOrDefault("inputRole", "user"), steps, Integer.parseInt(options.getOrDefault("maxRememberedSegments", "1024")));
    }
}
