package org.eyman_.keyword;


import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public record KeywordDefinition(String id,
                                String word,
                                boolean enabled,
                                List<String> aliases,
                                String permission,
                                float minConfidence,
                                Duration cooldown,
                                Actions actions,
                                Map<String, HookConfig> hooks) {

    public enum Executor { CONSOLE, PLAYER }

    public record Actions(Executor executor, List<String> commands) {
    }

    public record HookConfig(String id, boolean enabled, Map<String, Object> values) {

        public String string(String key, String fallback) {
            Object v = values.get(key);
            return v == null ? fallback : String.valueOf(v);
        }

        public List<String> stringList(String key) {
            Object v = values.get(key);
            if (v instanceof List<?> list) {
                List<String> out = new ArrayList<>(list.size());
                for (Object o : list) {
                    out.add(String.valueOf(o));
                }
                return List.copyOf(out);
            }
            return List.of();
        }

        public Executor executor(Executor fallback) {
            try {
                return Executor.valueOf(string("executor", fallback.name()).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    public List<String> spokenForms() {
        List<String> forms = new ArrayList<>(aliases.size() + 1);
        forms.add(word);
        forms.addAll(aliases);
        return forms;
    }
}
