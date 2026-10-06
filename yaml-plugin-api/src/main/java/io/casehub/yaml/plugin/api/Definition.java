package io.casehub.yaml.plugin.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record Definition(
        String name,
        String description,
        Map<String, Parameter> inputs,
        Map<String, Parameter> outputs,
        Portability portability,
        Action action,
        String capability) {

    public Definition {
        if (name == null || name.isBlank()) {throw new IllegalArgumentException("Definition requires a name");}
        if (inputs == null) {inputs = Map.of();}
        if (outputs == null) {outputs = Map.of();}
        if (portability == null) {portability = Portability.JAVA;}
        if (action == null) {throw new IllegalArgumentException("Definition requires an action");}
        if (capability == null || capability.isBlank()) {capability = "steps";}
    }

    public static Builder of(String name) {return new Builder(name);}

    public static final class Builder {
        private final String                           name;
        private       String                           description;
        private final LinkedHashMap<String, Parameter> inputs      = new LinkedHashMap<>();
        private final LinkedHashMap<String, Parameter> outputs     = new LinkedHashMap<>();
        private       Portability                      portability = Portability.JAVA;
        private       Action                           action;
        private       String                           capability;

        private Builder(String name)                   {this.name = name;}

        public Builder description(String description) {
                                                           this.description = description;
                                                           return this;
                                                       }

        public Builder input(String name, ParameterType type, boolean required) {
            inputs.put(name, new Parameter(type, required, null, null, null, null));
            return this;
        }

        public Builder input(String name, Parameter parameter) {
            inputs.put(name, parameter);
            return this;
        }

        public Builder output(String name, ParameterType type) {
            outputs.put(name, new Parameter(type, false, null, null, null, null));
            return this;
        }

        public Builder output(String name, Parameter parameter) {
            outputs.put(name, parameter);
            return this;
        }

        public Builder portability(Portability portability) {
                                                                this.portability = portability;
                                                                return this;
                                                            }

        public Builder capability(String capability)        {
                                                                this.capability = capability;
                                                                return this;
                                                            }

        public Builder execute(Action action)               {
                                                                this.action = action;
                                                                return this;
                                                            }

        public Definition build() {
            return new Definition(name, description,
                                  Collections.unmodifiableMap(new LinkedHashMap<>(inputs)),
                                  Collections.unmodifiableMap(new LinkedHashMap<>(outputs)),
                                  portability, action, capability);
        }
    }
}
