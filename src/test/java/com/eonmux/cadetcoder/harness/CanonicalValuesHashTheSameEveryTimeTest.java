package com.eonmux.cadetcoder.harness;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The ledger is a hash chain, so "the same value" has to mean one sequence of bytes.
 *
 * <p>Every guarantee the harness makes rests on this. A transition's hash covers its observation; a
 * world model is addressed by the digest of its source; certification decides a prediction was right
 * by comparing a predicted observation with a recorded one. If two values that are equal can
 * serialise differently -- because a map remembered its insertion order, or because a count arrived
 * as {@code 3} once and {@code 3.0} the next time -- then a correct model is reported as
 * contradicted and a chain nobody touched fails to verify.</p>
 */
public class CanonicalValuesHashTheSameEveryTimeTest {

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((String) pairs[i], pairs[i + 1]);
        }
        return m;
    }

    @Test
    public void insertionOrderIsNotPartOfTheValue() {
        assertThat(Json.canonical(map("b", 1, "a", 2)))
                .isEqualTo(Json.canonical(map("a", 2, "b", 1)))
                .isEqualTo("{\"a\":2,\"b\":1}");
    }

    @Test
    public void nestedObjectsAreOrderedToo() {
        Object one = map("outer", map("z", 1, "a", map("y", 2, "b", 3)));
        Object two = map("outer", map("a", map("b", 3, "y", 2), "z", 1));

        assertThat(Json.canonical(one)).isEqualTo(Json.canonical(two));
    }

    /**
     * The model language computes in {@code double}, so a count a model reached by arithmetic and
     * the same count read straight out of an observation have to be one value.
     */
    @Test
    public void aWholeNumberIsOneValueHoweverItWasComputed() {
        assertThat(Json.canonical(3.0)).isEqualTo("3");
        assertThat(Json.canonical(3)).isEqualTo("3");
        assertThat(Json.canonical(0.5 + 0.5)).isEqualTo("1");
        assertThat(Json.equal(map("hp", 3), map("hp", 1.0 + 2.0))).isTrue();
    }

    @Test
    public void fractionsSurvive() {
        assertThat(Json.canonical(0.25)).isEqualTo("0.25");
        assertThat(Json.equal(0.25, 0.5)).isFalse();
    }

    /** A model that divided by zero has a bug, and the record has to be able to say so. */
    @Test
    public void aNumberJsonCannotWriteIsRecordedRatherThanThrown() {
        assertThat(Json.canonical(Double.NaN)).isEqualTo("\"NaN\"");
        assertThat(Json.canonical(Double.POSITIVE_INFINITY)).isEqualTo("\"Infinity\"");
    }

    @Test
    public void thereIsNoInsignificantWhitespace() {
        assertThat(Json.canonical(List.of(1, 2, map("a", "b"))))
                .isEqualTo("[1,2,{\"a\":\"b\"}]");
    }

    @Test
    public void controlCharactersAndQuotesAreEscaped() {
        String awkward = "a\"b\\c\n" + (char) 1 + "de";
        String written = Json.canonical(awkward);

        assertThat(written).isEqualTo("\"a\\\"b\\\\c\\n\\u0001de\"");
        assertThat(Json.parse(written)).isEqualTo(awkward);
    }

    @Test
    public void whatIsWrittenCanBeReadBack() {
        Object value = map("s", "text", "n", 4, "f", 1.5, "b", true, "nul", null,
                           "l", List.of(1, 2), "m", map("k", "v"));

        assertThat(Json.canonical(Json.parse(Json.canonical(value))))
                .isEqualTo(Json.canonical(value));
    }

    @Test
    public void aDigestIdentifiesTheValueAndNotTheSpelling() {
        assertThat(Json.digest(map("b", 1, "a", 2), 16))
                .hasSize(16)
                .isEqualTo(Json.digest(map("a", 2, "b", 1), 16));

        assertThat(Json.digest(map("a", 2, "b", 1), 16))
                .isNotEqualTo(Json.digest(map("a", 2, "b", 2), 16));
    }

    @Test
    public void digestsAreStableAcrossRuns() {
        assertThat(Json.digestOfText("harness", 12))
                .as("a hash chain written by one run has to verify in the next")
                .isEqualTo("49f756463ad9");
    }

    /** {@code step} is handed a state; a copy is what keeps the caller's own out of reach. */
    @Test
    @SuppressWarnings ("unchecked")
    public void aCopySharesNothingWithTheOriginal() {
        Map<String, Object> original = map("inner", new ArrayList<>(List.of(1, 2)));

        Map<String, Object> copy = (Map<String, Object>) Json.deepCopy(original);
        ((List<Object>) copy.get("inner")).add(3);

        assertThat(original.toString()).isEqualTo("{inner=[1, 2]}");
        assertThat(copy.toString()).isEqualTo("{inner=[1, 2, 3]}");
    }

    @Test
    public void aPathNamesSomethingInsideAValue() {
        Object value = map("hud", map("score", 7), "grid", List.of(List.of("a", "b")));

        assertThat(Json.at(value, "hud.score")).isEqualTo(7);
        assertThat(Json.at(value, "grid.0.1")).isEqualTo("b");
        assertThat(Json.at(value, "")).isSameAs(value);
        assertThat(Json.has(value, "hud.lives")).isFalse();
        assertThatThrownBy(() -> Json.at(value, "hud.lives"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hud.lives");
    }

    /** A key that happens to be numeric is still a key, not an index. */
    @Test
    public void anObjectKeyWinsOverAnIndexReading() {
        assertThat(Json.at(map("0", "by-name"), "0")).isEqualTo("by-name");
    }

    @Test
    public void emptinessIsFalse() {
        assertThat(Json.truthy(null)).isFalse();
        assertThat(Json.truthy(0)).isFalse();
        assertThat(Json.truthy("")).isFalse();
        assertThat(Json.truthy(List.of())).isFalse();
        assertThat(Json.truthy(map())).isFalse();
        assertThat(Json.truthy(false)).isFalse();

        assertThat(Json.truthy(1)).isTrue();
        assertThat(Json.truthy("x")).isTrue();
        assertThat(Json.truthy(List.of(0))).isTrue();
        assertThat(Json.truthy(map("a", 1))).isTrue();
    }

    @Test
    public void textThatIsNotJsonIsRefusedRatherThanGuessedAt() {
        assertThatThrownBy(() -> Json.parse("{not json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not JSON");
    }
}
