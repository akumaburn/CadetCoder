package com.eonmux.cadetcoder.ai.parsing;

import com.eonmux.cadetcoder.commands.ChatCommand;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A command's arguments reach it in the order the model wrote them, however many there are.
 *
 * <p><b>The defect</b>: a command with no arm of its own keeps its arguments as {@code arg0},
 * {@code arg1}, ... and they were re-emitted in the key order of a {@link java.util.TreeMap}, which
 * sorts text. Up to ten arguments text order and counting order agree, so this never showed. From
 * the eleventh on they diverge -- {@code arg1 < arg10 < arg2} -- and the command line was silently
 * rebuilt in the wrong order: the model asked for one thing and a different thing ran, with no
 * error to say so.</p>
 */
public class TheArgumentsArriveInTheOrderTheyWereWrittenTest {

    private final ActionBlockParser parser = new ActionBlockParser();

    private String[] argsOf(ChatCommand.AIAction action) throws Exception {
        Field field = ChatCommand.AIAction.class.getDeclaredField("arguments");
        field.setAccessible(true);
        return (String[]) field.get(action);
    }

    private List<String> argv(String command, String argsLine) throws Exception {
        String block = "ACTION_START\n"
                + "COMMAND: " + command + "\n"
                + "ARGS: " + argsLine + "\n"
                + "REASON: because the task needs it\n"
                + "ACTION_END";
        ParsedResponse response = parser.parse(block, new ParsingContext.Builder("do the work").build());
        assertThat(response.getActions()).hasSize(1);
        return Arrays.asList(argsOf(response.getActions().get(0).toLegacyAction()));
    }

    /** Twelve arguments, each naming its own position, so any reordering is visible. */
    @Test
    public void aCommandWithMoreThanTenArgumentsKeepsThemInOrder() throws Exception {
        assertThat(argv("frobnicate", "a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11"))
                .containsExactly("a0", "a1", "a2", "a3", "a4", "a5",
                                 "a6", "a7", "a8", "a9", "a10", "a11");
    }

    /** Ten or fewer never showed the defect, and must keep working. */
    @Test
    public void aShortArgumentListIsUnchanged() throws Exception {
        assertThat(argv("frobnicate", "a0 a1 a2")).containsExactly("a0", "a1", "a2");
    }

    /**
     * The ordering rule itself: names are ordered as text, except that a run of digits inside one
     * is ordered by the number it spells. Names carrying no number keep the text order that made
     * the emission deterministic to begin with.
     */
    @Test
    public void digitsAreOrderedByValueAndTheRestByName() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("arg10", "ten");
        parameters.put("arg2", "two");
        parameters.put("beta", "b");
        parameters.put("alpha", "a");

        List<String> args = new ArrayList<>();
        ActionArguments.appendRemainingPositionals(args, parameters);

        assertThat(args).containsExactly("a", "two", "ten", "b");
    }
}
