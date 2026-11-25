package com.rushpromise.booking;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flagsmith.flagengine.Engine;
import com.flagsmith.flagengine.EvaluationContext;
import com.flagsmith.flagengine.FlagResult;
import com.flagsmith.mappers.EngineMappers;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Map;

final class FlagsmithDocument {
    private static final String RUSH_FLAG = "rush_mode";
    private static final String PROFILE_FLAG = "promise_profile";
    private static final Map<String, String> REASONS = Map.of(
            "NORMAL", "STANDARD", "RUSH", "RUSH_HOUR", "BUSY_SLOT", "SLOT_NEAR_CAPACITY");
    private final ObjectMapper json;
    private final EvaluationContext context;
    private final String projected;
    private final String digest;
    private final Instant revision;
    private final boolean rush;

    private FlagsmithDocument(ObjectMapper json, EvaluationContext context, String projected, Instant revision) {
        this.json = json;
        this.context = context;
        this.projected = projected;
        this.digest = digest(projected);
        this.revision = revision;
        this.rush = Boolean.TRUE.equals(evaluateFlag(context, RUSH_FLAG).getEnabled());
    }

    static FlagsmithDocument read(ObjectMapper json, String body, String revisionHeader) throws JsonProcessingException {
        JsonNode raw = json.readTree(body);
        require(raw != null && raw.isObject(), "environment-document");
        Instant revision = Instant.parse(raw.path("updated_at").asText());
        require(revisionHeader == null || revision.equals(parseHeader(revisionHeader)), "revision mismatch");
        JsonNode identities = raw.path("identity_overrides");
        require(identities.isArray() && identities.isEmpty(), "identity overrides");

        JsonNode defaults = raw.path("feature_states");
        JsonNode rush = onlyState(defaults, RUSH_FLAG);
        JsonNode profile = onlyState(defaults, PROFILE_FLAG);
        require(rush.path("enabled").isBoolean() && !rush.hasNonNull("feature_segment"), RUSH_FLAG);
        require(enabled(profile) && !profile.hasNonNull("feature_segment"), PROFILE_FLAG);
        var normal = readProfile(json, profile.path("feature_state_value").textValue(), "NORMAL");

        JsonNode segments = raw.path("project").path("segments");
        require(segments.isArray(), "project segments");
        var known = new ArrayList<JsonNode>();
        for (JsonNode segment : segments) {
            JsonNode states = segment.path("feature_states");
            require(states.isArray(), "segment feature states");
            for (JsonNode state : states) {
                String flag = flagName(state);
                require(!RUSH_FLAG.equals(flag), "rush_mode segment override");
                if (PROFILE_FLAG.equals(flag)) known.add(segment);
            }
        }
        require(known.size() == 2, "promise_profile requires two segment overrides");
        JsonNode busy = namedSegment(known, "busy-slot");
        JsonNode rushHour = namedSegment(known, "rush-hour");
        var busyState = validateOverride(json, busy, "slot_load_pct", "GREATER_THAN_INCLUSIVE", "BUSY_SLOT");
        var rushState = validateOverride(json, rushHour, "rush", "EQUAL", "RUSH");
        String cutoff = busyState.condition().value();
        require(cutoff.matches("[1-9][0-9]{0,2}") && Integer.parseInt(cutoff) <= 100, "slot_load_pct threshold");
        require("true".equals(rushState.condition().value()), "rush condition value");
        int threshold = Integer.parseInt(cutoff);
        require(busyState.priority() < rushState.priority(), "busy-slot must have higher priority");

        // Store only this approved subset of the SDK shape, with synthetic IDs and no hosted keys or identity data.
        ObjectNode clean = json.createObjectNode().put("updated_at", revision.toString())
                .put("api_key", "local-evaluation-only").put("name", "Development");
        clean.putArray("identity_overrides");
        clean.putArray("feature_states").add(state(json, 1, RUSH_FLAG, rush.path("enabled").booleanValue(), null, null))
                .add(state(json, 2, PROFILE_FLAG, true, normal, null));
        clean.putObject("project").putArray("segments")
                .add(segment(json, 1, "busy-slot", busyState)).add(segment(json, 2, "rush-hour", rushState));

        var document = new FlagsmithDocument(json, EngineMappers.mapEnvironmentDocumentToContext(clean),
                json.writeValueAsString(clean), revision);
        require(document.evaluate("validation", 0, false).equals(normal), "NORMAL default evaluation");
        require(document.evaluate("validation", threshold - 1, false).equals(normal), "below threshold must be NORMAL");
        require(document.evaluate("validation", threshold, false).equals(busyState.profile()), "threshold must be BUSY_SLOT");
        require(document.evaluate("validation", threshold - 1, true).equals(rushState.profile()), "rush must be RUSH");
        require(document.evaluate("validation", threshold, true).equals(busyState.profile()), "BUSY_SLOT must win overlap");
        return document;
    }

    Profile evaluate(String identity, int slotLoadPercent) {
        return evaluate(identity, slotLoadPercent, rush);
    }

    private Profile evaluate(String identity, int slotLoadPercent, boolean rushTrait) {
        EvaluationContext local = EngineMappers.mapContextAndIdentityDataToContext(context, identity,
                Map.of("slot_load_pct", slotLoadPercent, "rush", rushTrait));
        FlagResult result = evaluateFlag(local, PROFILE_FLAG);
        require(Boolean.TRUE.equals(result.getEnabled()) && result.getValue() instanceof String, "invalid evaluated profile");
        try {
            return readProfile(json, (String) result.getValue(), null);
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("invalid evaluated JSON profile");
        }
    }

    private static FlagResult evaluateFlag(EvaluationContext context, String name) {
        var result = Engine.getEvaluationResult(context).getFlags().getAdditionalProperties().get(name);
        require(result != null, "local flag missing");
        return result;
    }

    private static Override validateOverride(ObjectMapper json, JsonNode segment, String property, String operator,
                                             String expectedProfile) throws JsonProcessingException {
        JsonNode rule = only(segment.path("rules"), "segment rules");
        for (int depth = 0; depth < 6; depth++) {
            require(rule.path("conditions").isArray() && rule.path("rules").isArray()
                    && ("ALL".equals(rule.path("type").asText()) || "ANY".equals(rule.path("type").asText())), "segment rule shape");
            if (!rule.path("conditions").isEmpty()) break;
            rule = only(rule.path("rules"), "segment rule nesting");
        }
        JsonNode condition = only(rule.path("conditions"), "segment condition");
        var observed = new Condition(condition.path("property_").asText(), condition.path("operator").asText(),
                condition.path("value").asText());
        require(rule.path("rules").isEmpty() && property.equals(observed.property()) && operator.equals(observed.operator()), "segment condition");
        JsonNode state = onlyState(segment.path("feature_states"), PROFILE_FLAG);
        require(enabled(state), "disabled override");
        int priority = integer(state.path("feature_segment"), "priority", 0, Integer.MAX_VALUE);
        return new Override(priority, readProfile(json, state.path("feature_state_value").textValue(), expectedProfile), observed);
    }

    private static Profile readProfile(ObjectMapper json, String value, String expected) throws JsonProcessingException {
        require(value != null && value.length() <= 2048, "profile value");
        JsonNode config = json.readTree(value);
        require(config != null && config.isObject() && config.size() == 8, "profile fields");
        integer(config, "schema", 1, 1);
        String profile = config.path("profile").textValue();
        String reason = config.path("reason_code").textValue();
        require(profile != null && reason != null && reason.equals(REASONS.get(profile))
                && (expected == null || expected.equals(profile)), "profile and reason_code");
        return new Profile(profile, reason, integer(config, "approval_above_g", 0, 5000),
                integer(config, "lead_time_min", 0, 120), integer(config, "quote_ttl_s", 60, 600),
                integer(config, "grace_min", 5, 60), integer(config, "hold_min", 5, 60));
    }

    private static ObjectNode state(ObjectMapper json, int stateId, String name, boolean enabled, Profile profile, Integer priority) {
        ObjectNode node = json.createObjectNode().put("django_id", stateId).put("enabled", enabled);
        node.putObject("feature").put("id", name.equals(RUSH_FLAG) ? 1 : 2).put("name", name).put("type", "STANDARD");
        node.putArray("multivariate_feature_state_values");
        if (priority == null) node.putNull("feature_segment");
        else node.putObject("feature_segment").put("priority", priority);
        if (profile == null) node.putNull("feature_state_value");
        else node.put("feature_state_value", json.createObjectNode().put("schema", 1).put("profile", profile.name())
                .put("reason_code", profile.reason()).put("approval_above_g", profile.threshold())
                .put("lead_time_min", profile.lead()).put("quote_ttl_s", profile.ttl())
                .put("grace_min", profile.grace()).put("hold_min", profile.hold()).toString());
        return node;
    }

    private static ObjectNode segment(ObjectMapper json, int id, String name, Override override) {
        var node = json.createObjectNode().put("id", id).put("name", name);
        var rule = node.putArray("rules").addObject().put("type", "ALL");
        rule.putArray("rules");
        var condition = override.condition();
        rule.putArray("conditions").addObject().put("property_", condition.property())
                .put("operator", condition.operator()).put("value", condition.value());
        node.putArray("feature_states").add(state(json, id + 2, PROFILE_FLAG, true, override.profile(), override.priority()));
        return node;
    }

    private static JsonNode only(JsonNode array, String field) {
        require(array.isArray() && array.size() == 1, field);
        return array.get(0);
    }

    private static JsonNode onlyState(JsonNode array, String name) {
        require(array.isArray(), "feature states");
        JsonNode selected = null;
        for (JsonNode state : array) {
            if (!name.equals(flagName(state))) continue;
            require(selected == null, "duplicate required flag");
            selected = state;
        }
        require(selected != null && selected.path("multivariate_feature_state_values").isArray()
                && selected.path("multivariate_feature_state_values").isEmpty(), name);
        return selected;
    }

    private static JsonNode namedSegment(ArrayList<JsonNode> segments, String name) {
        return segments.stream().filter(segment -> name.equals(segment.path("name").asText())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("required segment missing"));
    }

    private static String flagName(JsonNode state) { return state.path("feature").path("name").asText(); }

    private static boolean enabled(JsonNode state) {
        return state.path("enabled").isBoolean() && state.path("enabled").booleanValue();
    }

    private static int integer(JsonNode node, String name, int min, int max) {
        JsonNode field = node.path(name);
        require(field.isIntegralNumber() && field.canConvertToInt() && field.intValue() >= min && field.intValue() <= max, name);
        return field.intValue();
    }

    private static void require(boolean condition, String field) {
        if (!condition) throw new IllegalArgumentException(field);
    }

    private static Instant parseHeader(String raw) {
        BigDecimal timestamp = new BigDecimal(raw);
        long seconds = timestamp.setScale(0, RoundingMode.FLOOR).longValueExact();
        long nanos = timestamp.subtract(BigDecimal.valueOf(seconds)).movePointRight(9).longValueExact();
        return Instant.ofEpochSecond(seconds, nanos);
    }

    static String digest(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    String projection() { return projected; }
    String digest() { return digest; }
    Instant revision() { return revision; }
    boolean rush() { return rush; }

    record Profile(String name, String reason, int threshold, int lead, int ttl, int grace, int hold) {}
    private record Override(int priority, Profile profile, Condition condition) {}
    private record Condition(String property, String operator, String value) {}
}
