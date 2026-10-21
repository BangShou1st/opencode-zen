package com.sharedlibs.opencodezen;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Fail-closed model pricing, retirement, and protocol rules shared by consumers.
 *
 * <p>Aligned with OpenCode-Zen-FreeX v0.3.1's {@code isFree} / {@code isRetired}
 * (and the Python V3.0 proxy's {@code _free}):
 *
 * <ul>
 *   <li><b>The name wins.</b> An id that names itself free is offered even while
 *       the catalogue still prices it, because the catalogue is the side that
 *       lags when a model turns free. The live Zen listing is what decides
 *       which models exist at all.</li>
 *   <li><b>{@code deprecated} is not an ending word.</b> The catalogue marks
 *       many models deprecated while Zen still serves them (47 opencode entries
 *       carry {@code status: "deprecated"} as of 2026-10-21, among them
 *       {@code space-bunny-free} and {@code muse-spark-1.3-contributor-free}).
 *       Treating that as a retirement hides a model that is being served.</li>
 *   <li><b>An empty value is not a date.</b> {@code deprecated_at: ""} is not a
 *       retirement date, and neither is a container where a date belongs.</li>
 *   <li><b>{@code status} wins over {@code lifecycle}.</b> A status that is set
 *       is read; the lifecycle is not consulted beside it.</li>
 * </ul>
 */
public final class OpenCodeZenFreeModelPolicy {

    /** The only status values that end a model's life. */
    private static final List<String> RETIRED_STATUSES = List.of("retired", "disabled");

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
            return hasFreeName(modelId);
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
     * Runtime admission: a model MUST be present in the latest real Zen listing
     * first. A model the catalogue has retired is refused; otherwise the free
     * name decides, because the catalogue is the side that lags.
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
        // The name wins: a `-free` id is a free model even while the catalogue
        // still prices it. A model priced at zero is free even without the name.
        return hasFreeName(modelId) || hasExplicitZeroPrice(catalogEntry);
    }

    /**
     * True when the id names a free model. The suffix is read without regard to
     * case, and the name is never evidence on its own: the live Zen listing is
     * what decides which models exist.
     */
    public static boolean hasFreeName(String modelId) {
        return modelId != null && !modelId.isBlank()
                && modelId.toLowerCase(Locale.ROOT).endsWith("-free");
    }

    /** Explicitly free means numeric input and output prices are both exactly zero. */
    public static boolean hasExplicitZeroPrice(JsonNode entry) {
        JsonNode cost = entry == null ? null : entry.get("cost");
        return cost != null && cost.isObject()
                && isZero(cost.get("input"))
                && isZero(cost.get("output"));
    }

    /**
     * Detects retirement flags and recognized status/date fields from the catalog.
     *
     * <p>{@code status} is read first and {@code lifecycle} only when no status
     * is set: a status of {@code active} beside a lifecycle of {@code retired}
     * is not a retirement. A date field counts only when it carries a value —
     * an empty string and a container are not dates.
     */
    public static boolean isRetired(JsonNode entry) {
        if (entry == null) {
            return false;
        }
        // A strict boolean: `deprecated: "true"` is not the flag.
        JsonNode deprecated = entry.get("deprecated");
        if (deprecated != null && deprecated.isBoolean() && deprecated.asBoolean()) {
            return true;
        }
        for (String field : RETIREMENT_DATE_FIELDS) {
            if (isSet(entry.get(field))) {
                return true;
            }
        }
        String status = text(entry.get("status"));
        String lifecycle = text(entry.get("lifecycle"));
        String ended = status != null ? status : lifecycle;
        return ended != null && RETIRED_STATUSES.contains(ended);
    }

    /**
     * Whether a value counts as set: present, not a container, and not blank.
     *
     * <p>The shape guards matter. An empty string is not a date, and an array or
     * an object where a date belongs is not a date either; reading either as one
     * retires a model that is still being served.
     */
    private static boolean isSet(JsonNode value) {
        if (value == null || value.isNull()) {
            return false;
        }
        if (value.isContainerNode()) {
            return false;
        }
        String text = value.asText("");
        return !text.isBlank();
    }

    private static String text(JsonNode value) {
        if (value == null || value.isNull() || !value.isTextual()) {
            return null;
        }
        String trimmed = value.asText().trim().toLowerCase(Locale.ROOT);
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean isZero(JsonNode value) {
        return value != null && value.isNumber() && value.asDouble() == 0d;
    }
}
