package com.sharedlibs.opencodezen;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The shared V1 Zen free admission policy is the single definition both deployables execute.
 *
 * <p>It must stay an allow-list closed under the three attested facts (Chat family, official V1 Zen
 * {@code Free} pricing row, current V1 catalog service). A {@code -free} suffix is never evidence,
 * and paid/unknown/unsupported/delisted ids fail closed.
 */
class OpenCodeZenFreeAdmissionPolicyTest {

    @Test
    void onlyReviewedFreeChatModelsAreAdmitted() {
        assertThat(OpenCodeZenFreeAdmissionPolicy.ADMITTED_FREE_CHAT_MODELS)
                .containsExactlyInAnyOrder("big-pickle", "longcat-2.5-preview-free",
                        "mimo-v2.6-flash-free", "nemotron-3.5-lightning-free", "space-bunny-free");
        for (var model : OpenCodeZenFreeAdmissionPolicy.ADMITTED_FREE_CHAT_MODELS) {
            assertThat(OpenCodeZenFreeAdmissionPolicy.admitsFreeChatModel(model)).isTrue();
        }
    }

    @Test
    void suffixAndUnknownIdsNeverImplyAdmission() {
        for (var model : List.of("glm-5.3-free", "qwen3.8-max-free", "totally-new-model-free",
                "deepseek-v4.1-flash-free", "cat-jam-free", "future-unknown-model",
                "deepseek-v4-pro", "minimax-m3", "gpt-5.6-sol", "claude-sonnet-5",
                "mimo-v2.5-free", "deepseek-v4-flash-free")) {
            assertThat(OpenCodeZenFreeAdmissionPolicy.admitsFreeChatModel(model))
                    .as(model + " has no confirmed, currently valid free evidence")
                    .isFalse();
        }
        assertThat(OpenCodeZenFreeAdmissionPolicy.admitsFreeChatModel(null)).isFalse();
        assertThat(OpenCodeZenFreeAdmissionPolicy.admitsFreeChatModel("")).isFalse();
        assertThat(OpenCodeZenFreeAdmissionPolicy.admitsFreeChatModel("   ")).isFalse();
    }

    @Test
    void policyVersionIsPinnedAndTraceable() {
        assertThat(OpenCodeZenFreeAdmissionPolicy.POLICY_VERSION).isEqualTo("2026-10-16-v1");
    }
}
