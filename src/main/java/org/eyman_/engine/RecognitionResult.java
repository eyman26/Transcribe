package org.eyman_.engine;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Set;

public record RecognitionResult(@NotNull String text, float confidence) {

    public static final RecognitionResult NONE = new RecognitionResult("", 0f);

    public RecognitionResult {
        confidence = Math.max(0f, Math.min(1f, confidence));
    }

    public boolean isEmpty() {
        return text.isEmpty();
    }

    @NotNull
    public static RecognitionResult fromVoskJson(@NotNull String json, @NotNull Set<String> allowedForms) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            if (!obj.has("text") || obj.get("text").isJsonNull()) {
                return NONE;
            }
            String text = obj.get("text").getAsString().strip().toLowerCase(Locale.ROOT);
            if (text.isEmpty() || text.equals("[unk]") || !allowedForms.contains(text)) {
                return NONE;
            }
            double sum = 0;
            int n = 0;
            if (obj.has("result") && obj.get("result").isJsonArray()) {
                for (JsonElement e : obj.getAsJsonArray("result")) {
                    if (e.isJsonObject() && e.getAsJsonObject().has("conf")) {
                        sum += e.getAsJsonObject().get("conf").getAsDouble();
                        n++;
                    }
                }
            }
            return new RecognitionResult(text, n == 0 ? 1f : (float) (sum / n));
        } catch (RuntimeException malformed) {
            return NONE;
        }
    }
}
