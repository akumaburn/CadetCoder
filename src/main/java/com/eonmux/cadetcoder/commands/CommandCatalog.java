package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.agents.WorkerPool;
import com.eonmux.cadetcoder.jobs.JobRegistry;

import java.lang.management.ManagementFactory;

/**
 * Single source of truth for the human-readable, usage-annotated list of commands that the agentic
 * harness advertises to the LLM. Both {@link ChatCommand} and {@link AgentCommand} inject this text
 * into their system prompt's {@code {{available_commands}}} slot so the model always sees the same,
 * accurate set of options (previously each command hard-coded its own list, which drifted).
 */
public final class CommandCatalog {

    private CommandCatalog() {
    }

    /**
     * The curated command reference (name, arguments, one-line purpose) shown to the model. Kept as a
     * pure constant so it is identical across every agentic entry point.
     */
    public static String coreCommands() {
        return "Every option below may be written as --flag=value or --flag value; quote any single argument that contains spaces.\n" +
               "Emit the bare command name (e.g. `read`), not a slash-prefixed one; a leading slash is tolerated but unnecessary.\n" +
               "- read <filepath> [--limit=<lines>] [--offset=<line>]: Read a file's contents (optionally a line range)\n" +
               "  Each line is shown as its number, a bar (\u2502) and the line; everything after the bar,\n" +
               "  leading spaces and tabs included, is the line exactly as it is in the file.\n" +
               "- multiread <filepath> [<filepath>...] [--limit=<lines-per-file>] [--offset=<line>] [--max-files=<n>] [--max-total-lines=<n>]:\n" +
               "  Read SEVERAL files in one call. Prefer this over repeated `read` calls whenever you need more than one file.\n" +
               "  Paths may be literal or globs (e.g. src/**/*.java). Files that cannot be read are reported without failing the rest.\n" +
               "  Example: multiread src/A.java src/B.java pom.xml\n" +
               "- write <filepath> <content> [-f]: Write content to a file. <content> is ONE argument, written verbatim; -f overwrites an existing file without confirmation\n" +
               "- edit <request>: Edit files with AI assistance (returns SEARCH/REPLACE blocks)\n" +
               "- multiedit <filepath> <edits>: Apply multiple edits to one file. <edits> is one or more blocks:\n" +
               "  each marker starts its own line, and the whole is given between ARGS_BEGIN and ARGS_END:\n" +
               "    ARGS_BEGIN\n" +
               "    <filepath>\n" +
               "    EDIT_START\n" +
               "    OLD: <the exact text to find, with its whitespace>\n" +
               "    NEW: <replacement>\n" +
               "    REPLACE_ALL: <true|false>\n" +
               "    EDIT_END\n" +
               "    ARGS_END\n" +
               "  Blocks written on one line, or after ARGS:, are refused.\n" +
               "  READ THE FILE FIRST. write, multiedit, patch and notebookedit all refuse to change a\n" +
               "  file this session has not read, because OLD: text written from memory does not match\n" +
               "  what is actually there. Creating a new file needs no read.\n" +
               "- grep <pattern> [--path=<path>] [--include=<glob>] [--exclude=<glob>] [--no-line-number] [--ignore-case]\n" +
               "       [-A <n>] [-B <n>] [-C <n>] [--column]: Search file contents\n" +
               "  Example: grep \"class MyClass\" --path=/project/src --include=*.java\n" +
               "  The pattern is a regular expression. | and \\| both mean \"or\"; a literal bar is [|].\n" +
               "  -A/-B/-C show that many lines after/before/either side of each match, so you can see\n" +
               "  what a match sits in without a second read. Those lines are numbered with a dash;\n" +
               "  matching lines keep the colon. --column adds where in the line the match starts.\n" +
               "  Notes: recursive by default (no --recursive flag); include/exclude match relative to --path, use **/ for nested files (e.g. --include=**/*.java)\n" +
               "- glob <pattern> [--path=<path>]: Find files by name pattern (* matches within one path segment, ** is recursive)\n" +
               "  Example: glob \"**/*.java\" --path=/project\n" +
               "- search \"<query>\": Find code by meaning rather than by exact text, across the whole project.\n" +
               "  Complements grep: use grep when you know the string or regex, search when you know what the\n" +
               "  code does but not what it is called. The index is built and refreshed automatically, so this\n" +
               "  works without running index first.\n" +
               "  Example: search \"where the retry backoff is computed\"\n" +
               "- ls <path> [-a] [-l] [-R] [--max-depth=<n>]: List files in a directory\n" +
               "- stat <path> [<path>...]: What a file IS, without reading it: kind, size, line count,\n" +
               "  permissions, last modification. Use it to budget a read, to judge whether a build is\n" +
               "  stale, or to check a file exists before working on it.\n" +
               "- diff <fileA> <fileB> [-U <n>] [--stat]: What two files differ by, as a unified diff.\n" +
               "  Far cheaper than reading both files and comparing them yourself. --stat reports only\n" +
               "  how many lines changed. The output is exactly what `patch` reads back.\n" +
               "- patch <diff> [-n] [--file=<path>]: Apply a unified diff to the files it names.\n" +
               "  Write the diff the way `git diff` prints it, as ONE argument. It can ADD, EDIT and\n" +
               "  REMOVE files: write `--- /dev/null` to add a file and `+++ /dev/null` to remove one.\n" +
               "  Every file is worked out before any is changed, so a patch that does not fit changes\n" +
               "  nothing at all. -n says what it would do without doing it. Use this when you know\n" +
               "  exactly which lines change; use multiedit when you know the text but not the lines.\n" +
               "- bash <command>: Execute a single shell command and wait for it. Pipes, redirects, ; & $(...) and backticks are NOT allowed; run separate commands instead\n" +
               "  Use it for anything that answers quickly. For work that takes longer than a step -- a full build,\n" +
               "  a test suite, a server you need up while you try something against it -- use `job start` instead:\n" +
               "  waiting here spends the step and then the timeout, and the command is killed before it finishes.\n" +
               "- job start [-d <description>] <command>: Run a shell command WITHOUT waiting for it.\n" +
               "  Returns an id immediately, and you carry on with other work while it runs. When it ends you are\n" +
               "  told in a later step, with the exit code and, if it failed, its last lines. Screened exactly as\n" +
               "  `bash` is.\n" +
               "  `job list` shows every job, how long it has run and how many lines are waiting to be read.\n" +
               "  `job output <id>` shows what it has printed SINCE YOU LAST LOOKED (`--all` for everything,\n" +
               "  `-n <lines>` for a different amount).\n" +
               "  `job stop <id>` ends one you no longer need.\n" +
               "  WAITING: wait only for the jobs whose results you need next; the others keep running.\n" +
               "  `job wait <id>...` returns when the first of the named jobs ends, and `job wait --all <id>...`\n" +
               "  when every one of them has. With no ids it waits on all of your running jobs. A job whose ending\n" +
               "  you were already told is skipped, so after one ends, the same `job wait` again waits for the rest;\n" +
               "  each answer says which ended, how far the others have got, and the exact wait that continues.\n" +
               "  `-t <seconds>` sets how long to wait (default " + JobCommand.DEFAULT_WAIT_SECONDS + ", at most " + JobCommand.MAX_WAIT_SECONDS + "). If you say the task is done\n" +
               "  while your jobs still run, you are asked whether the answer needs them before the run ends.\n" +
               "  Jobs run AT THE SAME TIME, up to " + JobRegistry.MAX_RUNNING + " at once. When you have several independent\n" +
               "  long commands (two experiments, a build and a separate test run), start them all in ONE action,\n" +
               "  one command per line, each with its own optional -d:\n" +
               "    ARGS_BEGIN\n" +
               "    start\n" +
               "    -d \"fit with cap 60\" python fit.py --cap 60\n" +
               "    -d \"fit with cap 120\" python fit.py --cap 120\n" +
               "    ARGS_END\n" +
               "  Do not start one, wait for it, and only then start the next, unless the next needs its result.\n" +
               "  Jobs that write the same files or build output get in each other's way; run those one at a time.\n" +
               "  " + machine() + " Every job shares them, so the memory and threads the jobs\n" +
               "  ask for (a JVM's -Xmx or heap setting, a worker count) must add up to less than that.\n" +
               "  Every step lists your jobs that are still running, so you need not ask. Start the long work, do\n" +
               "  the work that does not need it, and read its output when you are told it has ended.\n" +
               "  The description comes BEFORE the command, because everything from the command onwards\n" +
               "  belongs to the command: a -d written after it is passed to the program, not read here.\n" +
               "  Example: job start -d \"the full suite\" mvn -o test\n" +
               "- analyze <filepath>: Analyze code structure\n" +
               "- explain <filepath>: Explain code functionality\n" +
               "- suggest <type> <filepath>: Suggest improvements; <type> is one of improvements|tests|refactoring|documentation|performance\n" +
               "- refactor <filepath> [instructions]: Refactor code\n" +
               "- todowrite <add|update|remove|clear> [content] [-s <status>] [-p <priority>]: Manage the todo list\n" +
               "  Example: todowrite add \"write unit tests\" -p high\n" +
               "- todoread: Read current todos\n" +
               "- timer create --every <interval> <what to check> [--limit <n>]: Set a repeating check-in.\n" +
               "  It fires into a LATER step, carrying what you wrote, how many times it has fired, and the\n" +
               "  time. Use it when something will take a while and you want to carry on meanwhile: start\n" +
               "  the long thing, set a timer, do other work, and act on the reminder when it arrives.\n" +
               "  Intervals are 30s, 5m, 2h or 1h30m. Example: timer create --every 2m \"check whether the\n" +
               "  workers have finished\"\n" +
               "  `timer list` shows what is set, `timer cancel <id>` stops one when it is no longer needed,\n" +
               "  and `timer wait [<seconds>]` waits for the next one instead of spending a step on nothing.\n" +
               "- commit [-a] [-n] <message>: Commit changes to git (-a stages all modified files; -n skips the pre-commit lint)\n" +
               "- webfetch <url> [prompt]: Fetch a web page and answer a prompt about it (prompt optional; defaults to summarizing the page)\n" +
               "- websearch <query> [-n <num>]: Search the web and summarize results\n" +
               "- notebookread <notebook_path> [--cell=<id>]: Read a Jupyter (.ipynb) notebook's cells and outputs\n" +
               "- notebookedit <notebook_path> <cell_id> [source] [--type=<code|markdown|raw>] [--mode=<replace|insert|delete>]: Edit a notebook cell\n" +
               "- index [path]: Build/refresh the project file index for faster search\n" +
               "- context <show|create|reload|clear>: Inspect or manage the working context\n"
               + workers();
    }

    /**
     * What the model is told about spawning other agents.
     *
     * <p>Held apart from {@link #coreCommands()} because it is the one entry that must NOT reach a
     * worker: a worker offered this would spend a turn trying to spawn its own workers and be
     * refused, having learned nothing. {@link #workerCommands()} is the same catalog without it.</p>
     */
    private static String workers() {
        return
              "- workers: YOU CAN RUN OTHER AGENTS IN PARALLEL. Each worker is a full agent with its\n"
            + "  own tools and its own steps. Every worker sees the same briefing and one task of its\n"
            + "  own; no worker sees another's output.\n"
            + "  WHEN: the work is WIDE -- several independent questions about the same material\n"
            + "  (review this for correctness / for security / for test coverage; search four\n"
            + "  subsystems for one defect). NOT for steps that depend on each other: a worker\n"
            + "  cannot see what another found, so a second worker cannot build on a first.\n"
            + "  Put the shared material in -b so every worker starts from the same place.\n"
            + "  START and wait for all of them (the usual choice -- results come back in one step):\n"
            + "    workers \"review error handling\" \"review test coverage\" -b \"the net package\"\n"
            + "  START in the background, to do your own work while they run (they keep running\n"
            + "  across your later steps, so you can start them, look at something else, then read\n"
            + "  the results):\n"
            + "    workers start \"<task>\" \"<task>\" [-b <briefing>] [-m <steps per worker>]\n"
            + "  PROGRESS -- who has finished, who is still going:   workers status\n"
            + "  WAIT for them to finish (optionally at most N seconds): workers wait [<seconds>]\n"
            + "  RESULTS -- summary of the run:                     workers list\n"
            + "  RESULTS -- one worker's full output:               workers show <n>\n"
            + "  TERMINATE them (what has already finished is kept): workers stop\n"
            + "  LIMITS: at most " + WorkerPool.MAX_WORKERS + " workers, " + howManyAtOnce()
            + ", and only ONE run at a time -- start a\n"
            + "  second while one is going and it is refused, so `workers wait` or `workers stop`\n"
            + "  first. A worker cannot start workers of its own.";
    }

    /**
     * How many workers run at once, said the way the tool will actually behave.
     *
     * <p>Read rather than written out: {@code performance.threads} and
     * {@code performance.parallelProcessing} decide this, so a number in the prose here was right
     * only for whoever had not changed either. A model planning around three workers when one runs
     * at a time plans a shape the run cannot take.</p>
     *
     * @return the phrase that completes "at most N workers, ..."
     */
    /**
     * How large this machine is, in one sentence.
     *
     * <h2>Why the model is told</h2>
     *
     * <p>A run started seven fits at once, each with a 32 GB heap, on a machine with 125 GB of
     * memory. Nothing it had been shown said how large the machine was. The numbers do not change
     * during a session, so stating them in the system prompt costs nothing per turn.</p>
     */
    private static String machine() {
        int cores = Runtime.getRuntime().availableProcessors();
        if (ManagementFactory.getOperatingSystemMXBean()
                instanceof com.sun.management.OperatingSystemMXBean os) {
            long gigabytes = os.getTotalMemorySize() / (1024L * 1024 * 1024);
            return "This machine has " + cores + " cores and " + gigabytes + " GB of memory.";
        }
        return "This machine has " + cores + " cores.";
    }

    private static String howManyAtOnce() {
        int atOnce = WorkerPool.concurrency();
        return atOnce == 1 ? "1 at a time" : atOnce + " run at a time";
    }

    /**
     * The catalog as shown to a worker.
     *
     * @return every core command except the one a worker is not allowed to use
     */
    public static String workerCommands() {
        return coreCommands().replace("\n" + workers(), "");
    }
}
