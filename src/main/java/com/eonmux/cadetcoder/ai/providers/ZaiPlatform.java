package com.eonmux.cadetcoder.ai.providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * What the Z.ai platform, and the Zhipu AI platform it is the international edition of, needs from
 * this tool beyond an ordinary OpenAI-compatible connector.
 *
 * <h2>The four connectors</h2>
 *
 * <p>One company serves the GLM models from two hosts, and each host has two endpoints. Z.ai
 * ({@code api.z.ai}) is the international platform and Zhipu AI ({@code open.bigmodel.cn}) the
 * mainland one; their accounts and keys are separate. On each host, {@code /api/paas/v4} bills the
 * account balance and {@code /api/coding/paas/v4} bills a GLM Coding Plan subscription. A request
 * to the balance endpoint is charged to the balance even when the account has a Coding Plan, and
 * fails with code 1113 when the balance is empty, so the endpoint is part of what a user chooses
 * and each one gets its own connector. The four ids are the ones the
 * models.dev catalog uses, which lets the catalog describe each one's GLM models, and supply the
 * list when the platform gives none.</p>
 *
 * <h2>Why a 429 is not always a rate limit here</h2>
 *
 * <p>The platform answers HTTP 429 for two different things and tells them apart only by the
 * business code in the body ({@code {"error":{"code":"1113","message":...}}}). Codes such as 1302
 * mean too many requests, and a later attempt succeeds. Codes such as 1113 (no balance) or 1309
 * (Coding Plan expired) mean the account cannot pay for the request, and no later attempt within a
 * run can succeed. Read as a rate limit, they would be retried for about ninety seconds and then
 * reported as a rate limit. The codes are listed at https://docs.z.ai/api-reference/api-code.</p>
 */
public final class ZaiPlatform {

    /** Z.ai, billed to the account balance. */
    public static final String ZAI = "zai";

    /** Z.ai, billed to a GLM Coding Plan subscription. */
    public static final String ZAI_CODING_PLAN = "zai-coding-plan";

    /** Zhipu AI, the mainland platform, billed to the account balance. */
    public static final String ZHIPUAI = "zhipuai";

    /** Zhipu AI, billed to a GLM Coding Plan subscription. */
    public static final String ZHIPUAI_CODING_PLAN = "zhipuai-coding-plan";

    private static final Set<String> CONNECTOR_IDS =
            Set.of(ZAI, ZAI_CODING_PLAN, ZHIPUAI, ZHIPUAI_CODING_PLAN);

    /**
     * Business codes that mean the account has no allowance left for the request.
     *
     * <ul>
     *   <li>1113: no balance and no resource package.</li>
     *   <li>1308, 1310: the usage allowance for the current period is used up.</li>
     *   <li>1309: the GLM Coding Plan expired.</li>
     *   <li>1311: the subscription does not include the requested model.</li>
     *   <li>1314: the enterprise package expired.</li>
     *   <li>1315: the key is restricted to enterprise coding use.</li>
     *   <li>1316 to 1321: five-hour, weekly and monthly spending limits.</li>
     * </ul>
     *
     * <p>1302 (request rate) and 1305 (overload) are absent on purpose: both clear by themselves, so
     * they stay retryable. 1313 (fair-use throttling) is absent for the same reason.</p>
     */
    private static final Set<String> ALLOWANCE_EXHAUSTED = Set.of(
            "1113", "1308", "1309", "1310", "1311", "1314", "1315",
            "1316", "1317", "1318", "1319", "1320", "1321");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ZaiPlatform() {
    }

    /**
     * The other GLM connectors, whose catalog entries describe the same models.
     *
     * <p>The balance connector of the same platform first, because its entry names the most
     * models, then the rest. A model the catalog lists for one platform and not the other is the
     * same model either way.</p>
     *
     * @param id one of the four connector ids
     * @return the other three, in the order to ask them
     */
    static List<String> catalogIdsBesides(String id) {
        List<String> order = id.startsWith(ZHIPUAI)
                ? List.of(ZHIPUAI, ZHIPUAI_CODING_PLAN, ZAI, ZAI_CODING_PLAN)
                : List.of(ZAI, ZAI_CODING_PLAN, ZHIPUAI, ZHIPUAI_CODING_PLAN);
        return order.stream().filter(other -> !other.equals(id)).toList();
    }

    /**
     * @param providerId a connector id, may be {@code null}
     * @return whether it is one of the four connectors to this platform
     */
    public static boolean serves(String providerId) {
        return providerId != null && CONNECTOR_IDS.contains(providerId.toLowerCase(Locale.ROOT));
    }

    /**
     * The business code of a refusal that no retry can change, when the body carries one.
     *
     * <p>Only asked for this platform's connectors. A code is a number the platform owns, and
     * another provider that happened to answer {@code 1113} would mean something else by it.</p>
     *
     * @param providerId the connector that answered
     * @param body       the response body, may be {@code null}
     * @return the code, or empty when the refusal is something a retry could still change
     */
    public static Optional<String> allowanceExhaustedCode(String providerId, String body) {
        if (!serves(providerId) || body == null || body.isBlank()) {
            return Optional.empty();
        }
        String code = businessCode(body);
        return code != null && ALLOWANCE_EXHAUSTED.contains(code) ? Optional.of(code)
                                                                  : Optional.empty();
    }

    /**
     * What to check after a refusal that {@link #allowanceExhaustedCode} found.
     *
     * <p>Code 1113 on a balance connector has one common cause that the platform's message does not
     * name: the account pays through a GLM Coding Plan, and the request went to the endpoint that
     * charges the balance instead.</p>
     *
     * @param providerId the connector that answered
     * @param code       the business code
     * @return the advice, or {@code null} when there is nothing to add to the provider's message
     */
    public static String adviceFor(String providerId, String code) {
        if (!"1113".equals(code) || providerId == null) {
            return null;
        }
        String id = providerId.toLowerCase(Locale.ROOT);
        String codingPlan = ZAI.equals(id) ? ZAI_CODING_PLAN
                          : ZHIPUAI.equals(id) ? ZHIPUAI_CODING_PLAN : null;
        if (codingPlan == null) {
            return null;
        }
        return "If the account pays through a GLM Coding Plan, use the '" + codingPlan
                + "' connector: '" + id + "' charges the account balance."
                + " Run `cadet models use " + codingPlan + "`.";
    }

    /**
     * Reads {@code error.code} from a response body.
     *
     * @param body the response body
     * @return the code as text, whether the platform sent it as a string or a number; {@code null}
     *         when the body is not JSON or carries no code
     */
    private static String businessCode(String body) {
        try {
            JsonNode code = MAPPER.readTree(body).path("error").path("code");
            if (code.isTextual() || code.isNumber()) {
                String text = code.asText().trim();
                return text.isEmpty() ? null : text;
            }
        } catch (Exception e) {
            // Not JSON: an HTML page from a proxy, or a truncated body. Nothing to read a code from.
        }
        return null;
    }
}
