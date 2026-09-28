package com.eonmux.cadetcoder.test;

import com.eonmux.cadetcoder.ai.AIClient;
import com.eonmux.cadetcoder.ai.AIManager;
import com.eonmux.cadetcoder.ai.PromptData;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * A provider that answers with what the test says, and remembers what it was asked.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Several loops in this project reach the model through {@link AIManager#getInstance()} rather
 * than through anything injectable, so a test of what they SEND has no seam other than the singleton
 * itself. {@code AIManager.setClientFactory} is package-private to its own package and unreachable
 * from a test that lives anywhere else, so the singleton is replaced by reflection here -- in one
 * place, with one way to put it back, rather than once per test class with a different way each
 * time.</p>
 *
 * <h2>Why it must be closed</h2>
 *
 * <p>The singleton is process-wide: a test that installed a stub and did not restore it would leave
 * every later test in the same JVM talking to it. {@link #close()} puts back whatever was there, so
 * a try-with-resources or an {@code @After} is all that is needed.</p>
 */
public final class StubbedProvider implements AutoCloseable {

    private final Recorder recorder;
    private final Object   previousInstance;

    private StubbedProvider(Recorder recorder, Object previousInstance) {
        this.recorder         = recorder;
        this.previousInstance = previousInstance;
    }

    /**
     * Installs a provider that gives these replies in order, repeating the last one for as long as
     * it is asked.
     *
     * @param replies what the model says, in order; at least one
     * @return the installed stub, which must be closed
     */
    public static StubbedProvider answering(String... replies) {
        return install(new Recorder(replies == null ? List.of() : Arrays.asList(replies), null));
    }

    /**
     * Installs a provider whose every call fails, the way an unreachable one does.
     *
     * @param why what the failure says
     * @return the installed stub, which must be closed
     */
    public static StubbedProvider failing(String why) {
        return install(new Recorder(List.of(), new IllegalStateException(why)));
    }

    /**
     * Installs a provider whose every call fails with this exact failure.
     *
     * <p>For the cases where WHICH failure it is decides what happens: a run tells a provider that
     * refused the request apart from one that was never reached, and only the real exception types
     * carry that.</p>
     *
     * @param failure what every request throws
     * @return the installed stub, which must be closed
     */
    public static StubbedProvider failingWith(RuntimeException failure) {
        return install(new Recorder(List.of(), failure));
    }

    private static StubbedProvider install(Recorder recorder) {
        if (recorder.replies.isEmpty() && recorder.failure == null) {
            throw new IllegalArgumentException("a stubbed provider needs something to answer with");
        }
        try {
            Field instanceField = AIManager.class.getDeclaredField("instance");
            instanceField.setAccessible(true);
            Object previous = instanceField.get(null);

            Constructor<AIManager> constructor = AIManager.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            AIManager manager = constructor.newInstance();

            set(manager, "activeClient", recorder);
            set(manager, "initialized", Boolean.TRUE);
            instanceField.set(null, manager);
            return new StubbedProvider(recorder, previous);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not stand a stubbed provider in for AIManager", e);
        }
    }

    /** @return every request made through the singleton while this stub was installed, in order */
    public List<PromptData> asked() {
        return List.copyOf(recorder.asked);
    }

    /** @return the most recent request, or {@code null} if the stub was never asked anything */
    public PromptData lastAsked() {
        return recorder.asked.isEmpty() ? null : recorder.asked.get(recorder.asked.size() - 1);
    }

    /** @return the user prompt of the most recent request, or {@code ""} if there was none */
    public String lastUserPrompt() {
        PromptData last = lastAsked();
        return last == null ? "" : last.getUserPrompt();
    }

    @Override
    public void close() {
        try {
            Field instanceField = AIManager.class.getDeclaredField("instance");
            instanceField.setAccessible(true);
            instanceField.set(null, previousInstance);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not put the real AIManager back", e);
        }
    }

    private static void set(Object target, String name, Object value)
            throws ReflectiveOperationException {
        Field field = AIManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /** The client itself: answers from the script, keeps what it was given. */
    private static final class Recorder implements AIClient {

        private final List<String>      replies;
        private final RuntimeException  failure;
        private final List<PromptData>  asked = new ArrayList<>();

        private Recorder(List<String> replies, RuntimeException failure) {
            this.replies = replies;
            this.failure = failure;
        }

        @Override
        public String complete(PromptData promptData, Map<String, Object> parameters) {
            asked.add(promptData);
            if (failure != null) {
                throw failure;
            }
            int turn = asked.size() - 1;
            return replies.get(Math.min(turn, replies.size() - 1));
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String getModelName() {
            return "test/stub";
        }
    }
}
