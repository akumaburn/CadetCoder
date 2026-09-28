package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.commands.TodoReadCommand.TodoItem.Status;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A todo's status is read in the forms it is commonly written in.
 *
 * <p>A model closed a todo with {@code -s done}, was refused, and spent a request of 25,000 input
 * tokens to write {@code completed}. Each form accepted here names one status only.</p>
 */
class AtodoStatusIsReadAsItIsCommonlyWrittenTest {

    @Test
    void theNamesAreReadInAnyCase() {
        assertThat(Status.named("Completed")).isEqualTo(Status.COMPLETED);
        assertThat(Status.named("PENDING")).isEqualTo(Status.PENDING);
    }

    @Test
    void doneAndCompleteMeanCompleted() {
        assertThat(Status.named("done")).isEqualTo(Status.COMPLETED);
        assertThat(Status.named("complete")).isEqualTo(Status.COMPLETED);
    }

    @Test
    void inProgressMayBeWrittenWithAhyphenOrAspace() {
        assertThat(Status.named("in-progress")).isEqualTo(Status.IN_PROGRESS);
        assertThat(Status.named("in progress")).isEqualTo(Status.IN_PROGRESS);
    }

    @Test
    void anythingElseIsRefused() {
        assertThatThrownBy(() -> Status.named("maybe")).isInstanceOf(IllegalArgumentException.class);
    }
}
