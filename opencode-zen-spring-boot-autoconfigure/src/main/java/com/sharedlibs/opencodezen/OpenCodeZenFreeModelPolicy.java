package com.sharedlibs.opencodezen;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Fail-closed model pricing, retirement, and protocol rules shared by consumers. */
public final class OpenCodeZenFreeModelPolicy {

    private static final List<String> RETIRED_STATUSES =
            List.of("deprecated", "retired", "retiring", "disabled");
    private static final List<String> RETIREMENT_DATE_FIELDS =
            List.of("deprecation_date", "retired_at", "deprecated_at", "retirement_date");

    private OpenCodeZenFreeModelPolicy() {
    }

    /**
     * Uses explicit zero pricing when the catalog is available. A suffix fallback is
     * allowed only when no catalog is configured or the catalog is unavailable.
     */
    public static boolean isFreeModel(
            String modelId,
            boolean catalogConfigured,
            boolean catalogAvailable,
            ZenModelMetadata metadata) {
        if (modelId == null || modelId.isBlank()) {
            return false;
        }
        if (!catalogConfigured || !catalogAvailable) {
            return modelId.endsWith("-free");
        }
        return metadata != null && metadata.free() && !metadata.retired();
    }

    /**
     * Directory listing preserves existing candidates while authoritative metadata is
     * unavailable. An available catalog fails closed for unregistered or non-Chat models.
     */
    public static boolean isChatModelForListing(
            String modelId,
            boolean catalogConfigured,
            boolean catalogAvailable,
            ZenModelMetadata metadata) {
        if (modelId == null || modelId.isBlank()) {
            return false;
        }
        if (!catalogConfigured || !catalogAvailable) {
            return true;
        }
        return metadata != null && metadata.chatExecutable();
    }

    /** Runtime execution always requires an available catalog and a verified Chat model. */
    public static boolean isVerifiedChatModel(
            String modelId,
            boolean catalogConfigured,
            boolean catalogAvailable,
            ZenModelMetadata metadata) {
        return modelId != null && !modelId.isBlank()
                && catalogConfigured
                && catalogAvailable
                && metadata != null
                && metadata.chatExecutable();
    }

    /**
     * V2.4-compatible runtime admission: a model MUST be present in the latest real Zen
     * listing first. Metadata with known paid price or retirement status rejects it;
     * missing pricing may use a "-free" suffix only after live confirmation.
     * Caller must still route the ID to the correct Chat/Responses/Anthropic endpoint.
     */
    public static boolean isFreeInLiveListing(
            String modelId, Set<String> liveZenIds, JsonNode catalogEntry) {
        if (modelId == null || modelId.isBlank()
                || liveZenIds == null || !liveZenIds.contains(modelId)) {
            return false;
        }
        if (isRetired(catalogEntry)) {
            return false;
        }
        JsonNode cost = catalogEntry == null ? null : catalogEntry.path("cost");
        boolean inputKnown = cost != null && cost.isObject() && cost.path("input").isNumber();
        boolean outputKnown = cost != null && cost.isObject() && cost.path("output").isNumber();
        if (inputKnown && outputKnown) {
            return hasExplicitZeroPrice(catalogEntry);
        }
        if ((inputKnown && cost.path("input").asDouble() > 0)
                || (outputKnown && cost.path("output").asDouble() > 0)) {
            return false;
        }
        return modelId.endsWith("-free") || hasExplicitZeroPrice(catalogEntry);
    }

    /** Explicitly free means numeric input and output prices are both exactly zero. */
    public static boolean hasExplicitZeroPrice(JsonNode entry) {
        JsonNode cost = entry == null ? null : entry.get("cost");
        return cost != null && cost.isObject()
                && isZero(cost.get("input"))
                && isZero(cost.get("output"));
    }

    /** Detects retirement flags and recognized status/date fields from the catalog. */
    public static boolean isRetired(JsonNode entry) {
        if (entry == null) {
            return false;
        }
        if (entry.path("deprecated").asBoolean(false)) {
            return true;
        }
        for (String field : RETIREMENT_DATE_FIELDS) {
            if (entry.hasNonNull(field)) {
                return true;
            }
        }
        String status = entry.path("status").asText("").trim().toLowerCase(Locale.ROOT);
        return RETIRED_STATUSES.contains(status);
    }

    private static boolean isZero(JsonNode value) {
        return value != null && value.isNumber() && value.asDouble() == 0d;
    }
}