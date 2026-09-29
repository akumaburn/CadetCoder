# Project context for CadetCoder

CadetCoder reads `CADET.md` from the working directory and puts it into the AI
system prompt. See `context/ProjectContext.java`. This file is therefore what
the assistant knows about the repository when you point CadetCoder at itself.
Manage it with `context show`, `context create`, `context reload` and
`context clear`.

## Project overview

CadetCoder is a Java 17 command-line coding assistant. It exposes file, search,
git, web and notebook commands directly. It also runs an agentic `chat` and
`agent` loop that drives those same commands from a request in plain English.

Model access goes through 23 provider connectors backed by the models.dev
catalog, or through a local OpenAI-compatible endpoint. A connector the catalog
does not carry is asked for its own model list at `{base}/models`. A gateway
whose models do not all answer on one wire protocol chooses per model through
`ModelWire`.

Build with Maven. The entry point is `com.eonmux.cadetcoder.Main`. The artifact
is `target/CadetCoder-1.0-SNAPSHOT.jar`.

## Architecture

### One dispatch path

`CommandRegistry` discovers every `CommandRegistry.Command` in
`com.eonmux.cadetcoder.commands` by reflection and invokes `execute(String[])`.
The scan runs once per process, and every registry built later uses its result.
A loop builds a registry per pass, and a scan made after the jar changes on disk
finds nothing, so a second scan would make every command of every later pass
"Unknown command". An empty scan says the jar cannot be read. An unknown command
asked for by a model is answered with the list of commands it may run.

The command line, the interactive shell and the agentic loops all go through the
registry, so a command behaves the same on every surface. `Main` declares no
picocli subcommands; see the comment on its `@Command` annotation.

`CommandCatalog` is the single source of truth for the command list injected
into the LLM system prompt. `ChatCommand` and `AgentCommand` share it, so the
advertised tools cannot drift per entry point.

`InputRouter` makes one decision for every entry point. A leading `/` means
"command"; anything else is a message for the AI, and `//` escapes. About twenty
command names are also ordinary English words, so a decision made from the bare
first word executes plain sentences as tool calls. The command line keeps bare
dispatch, so `cadet ls src` runs `ls`. The interactive shell requires the slash.

`CommandLineTokenizer` is the one quote-aware tokenizer behind the shell, both
script runners and the ARGS of every action. It follows the shell's backslash
rules for quotes, backslashes and whitespace. It keeps a backslash before any
other character, because search patterns and paths use it. No other parser of
quotes exists, so the escaped quotes in `"case \"kernel\""` reach the command as
quotes.

### The agent harness

`harness/loop/Harness` implements the loop that `agent` runs under: observe,
deliberate, commit, observe. The harness appends what the agent saw, what it did
and what happened next to a hash-chained ledger.

The agent's beliefs about the project are an executable model. `certify` replays
the whole ledger through that model. It reports whether every transition held,
and which of the model's rules the ledger exercised.

`CommitGate` is the only channel to the world. The gate applies these rules:

- A plan needs a green certificate that covers the current ledger head.
- The gate cuts the plan off at the first step that turns on a rule nothing
  exercised.
- The gate measures the plan against a policy that belongs to the caller. The
  agent does not own that policy.

Each run gets `.cadet/runs/<stamp>-<unique>/`. The harness creates that
directory itself and does not compute its name in advance, so workers that start
inside the same second cannot collide. The record opens with what the run was
asked to do and closes with how it ended. `runs` reads one back long afterwards
without a write to it.

`agent -c <command>` makes the exit code of that command decide when the task is
done, in place of the agent's opinion. `agent` itself exits 0 only for a run
that finished. `--classic` selects the classic loop, which keeps no record. A
caller must ask for it by name, so no run downgrades into one quietly.

`Budget.plateau()` compares two snapshots four deliberations apart and reports
when neither certification nor mispredictions moved. An agent inside a loop
always has a reason for its next step, so the harness makes that judgement from
the record and does not ask the agent. The driver hands the session to the next
reasoner in the list, with the beliefs and the latest certificate in front of
it.

`Reasoners` builds that list from `ai.escalateTo`, with one entry when nothing
is named, so the hand-over never fires by accident. The run reports two
misconfigurations when it starts: a named model already in use, and a model on a
provider that is not configured. A plateau with nobody left to hand to reaches
the watch once.

### Loops and progress

#### Loop guards

`ActionLoopGuard` refuses repetition that cannot produce new information. It
compares the full action, which is the command and its normalized arguments, and
also the result that action produced. `ChatCommand` and `AgentCommand` share it.
Detection that keys on command names alone blocks ordinary work, so CadetCoder
uses none of it. That covers three greps in a row, `read` and `grep`
alternation, and "similar" filenames.

There is no default iteration or step ceiling. A ceiling cuts off long tasks
that work and does nothing for a run stuck early. Two guards cover the two loop
shapes:

- `ActionLoopGuard` watches the actions that chat and agent propose. It ends the
  run once several in a row are refused as non-productive.
- `IterationProgressGuard` watches the responses that `IterativeExecutor`
  receives. That is the only signal available for the ten commands that never
  propose an action.

Explicit budgets remain available through `agent -m/-t` and
`-Dcadet.iterative.maxIterations`.

Identical bytes in can legitimately mean identical bytes out, at temperature
zero or behind a provider cache. `IterationProgressGuard` therefore appends a
nudge that carries a unique token to the next request before it stops a run. The
request then differs from the one that was answered deterministically, and the
text says why the nudge is there. The guard appends it after the transcript is
rendered and never records it into the transcript, which keeps it compatible
with the append-only prompt rule. It sits past the shared prefix, so it costs
its own tokens and invalidates nothing.

#### Timers

`timers/` holds repeated check-ins that the model creates with the `timer`
command. An agent has no clock, so it gets one it can set. Every loop appends
the timers that are due to the prompt it is about to send, with the instruction,
the firing count and the time. Missed firings collapse into one: a run that
spent twenty minutes inside a command gets one reminder in place of ten. A
firing leaves the register as it is reported, so the count always means how many
times the thing happened.

`TimerScope` keeps the timers of concurrent agents apart. A note a worker left
itself must not arrive in a sibling's prompt. A named scope is discarded with
the work that opened it, because worker names repeat between runs. Like the
progress nudge, a firing is appended after the transcript is rendered and never
recorded into it.

#### Background jobs

`jobs/` holds work that the step which started it does not wait for. `bash`
waits, so a build longer than its timeout is killed before it ends and the step
is spent on the wait. `job start` runs the same command without the wait: the
call returns at once, `JobOutput` accumulates what the command prints, and the
ending reaches the model in a later prompt.

`JobOutput` is bounded and counts what it dropped. A reader handed a transcript
that begins in the middle concludes that the build printed no errors when the
errors scrolled off. A read carries a cursor and answers what is new, so a job
watched over ten steps does not spend the context window ten times on the same
lines.

`JobRegistry` takes an owner from `TimerScope.current()` and hands endings out
through take, delivered and return-undelivered, for the reason timers do. An
ending announced twice tells the model the build finished again several steps
after it did. An ending taken by a prompt that is never sent is never announced
at all.

`JobNotice` writes the `[job]` lines. It quotes the last twenty lines of a job
that failed, because a run told only that the build failed acts without a
reason. Eight jobs may run at once (`JobRegistry.MAX_RUNNING`), and an exit hook
stops what is left, so an exit leaves no processes behind the session.
`JobRegistry` keeps its jobs in the order they started, so every list of jobs
comes out in the same order on every run.

A `job wait` that runs out, and a `job output` with nothing new, both say how
long the job ran. The wait also says how many lines the job printed and quotes
the latest. `ActionLoopGuard` refuses a command repeated with the same result,
so each answer carries the job's progress. A repeated wait on a long job
therefore differs from the last one and is not refused as a loop.

Jobs run at the same time, and the model is told so, because a model that does
not know that starts independent jobs one after another. The loop allows one
action per step, so one `job start` action takes a command on each line.
`JobCommand.argvFor` turns such an action into `start-each`, which screens and
starts each line as a `job start` of its own. A line that is refused or does not
fit under the limit does not stop the others. `startedCommands` gives every line
to the screen in `ActionRun`, because a screen shown only the first line would
let the rest through unread.

`JobNotice.stillRunning` lists the scope's jobs that still run at the end of
every chat and agent prompt, with the number in use against the limit. It is
read and not taken. It is not written into the Cadet transcript, where it would
be stored again every round.

A run often needs only some of its jobs before it can go on, so `job wait` waits
for the jobs it names:

- It returns when the first of them ends, or with `--all` when every one ended.
- With no ids it waits for the caller's own jobs that still run.
- It skips a job whose ending was already announced
  (`JobRegistry.wasAnnounced`).

The model can therefore repeat the same wait after each ending, and each repeat
waits for the rest. Each answer names the jobs that ended, gives the progress of
the others, and quotes the wait that continues. A wait may last an hour, so a
forty-minute fit costs one wait. The catalog states the machine's cores and
memory, because a model that does not know the machine's size starts more work
than it can hold.

A run that says it is done while its jobs still run is sent back once for each
set of jobs that still run, whether or not uber mode is on. `JobsLeftRunning`
does this from `CompletionChallenge` and `AgentCommand.questionedCompletion`.
Nothing resumes a run after it ends, so the model chooses then. It waits for the
jobs whose results it needs, stops the ones it does not need, or says it is done
again and leaves them to run. The ids it was asked about are kept in the run's
context between steps.

`CommandGate` holds the screen and the confirmation that `bash` and `job start`
share. One copy means that a job, which runs unwatched for as long as it likes,
gets the same screen as `bash`. A job's command line is never split and
rejoined: `ActionArguments` passes it through as one argument, and `ActionRun`
screens the command itself and skips the `start` in front of it. Tokenized and
joined with single spaces, `grep -r "foo bar" .` becomes a different command,
which would run without complaint and be reported as the one asked for.

#### Uber mode and completion

With `ai.uberMode` on, CadetCoder tells the model what "finished" means here:
every part of the request, verified by a run of the project's own checks, with
the code around it still in agreement. It then sends a claim of completion back
to be checked against the request. It does not check the claim against the
summary, because the same understanding that decided the work was done wrote the
summary.

There are two closing questions, asked in turn. One is about the request the run
started from, and one is about what the run changed. A claim that answers a
question, with no work done since, moves to the next. A run that answers them
all in a row is believed and ends. Any action sets the count back to the first
question.

The questions are therefore unbounded over a run. A model that keeps finding
work is asked again each time, and the way out is to answer the questions. All
three loops honour it:

- chat through `CompletionChallenge`;
- the classic agent loop through a correction carried in its context;
- the harness through `harness/loop/RunSignals`.

`RunSignals` is an interface in `harness/loop`, and `harness/cadet/CadetSignals`
implements it. Only `harness/cadet` imports the rest of CadetCoder, so the
harness loop stays independent of the tool it runs inside. Uber mode is
deliberately absent from `CommandCatalog`.

#### `loop` and `loopfresh`

`loop` and `loopfresh` run one goal as a full chat run, a hundred times by
default and up to a thousand, with `--times=<n>`. Every pass runs: nothing stops
early because the model says the goal is met, which is the whole difference from
an ordinary run. Ctrl-C is the way out. Each pass is its own conversation, so no
transcript grows across them and `TranscriptCompactor` still bounds each one.
What carries between passes is the project itself.

`loop` also tells a pass the tail of what the last one said. `loopfresh`
withholds it, for a goal where the last attempt is a rut to avoid. Both are in
`ModelDispatch.STARTS_A_LOOP`, so a model inside a run cannot start one.
`OutputCapture.collectAlongside` lets a pass be recorded and watched at once.

A pass that FAILED still counts, because it did work and left it where the next
pass reads it. A pass that could not reach the model at all does not count, and
stops the loop. `IterativeExecutor` answers `ExitCode.UNREACHABLE` for a request
that never arrived, which tells the two apart. So a provider that cuts the
account off part way through a loop stops the loop. The passes left do not fail
in a few seconds and count as done.

A pass the provider cut short after it did work is reported with its iterations
and is not counted among the passes left unrun. `LoopCommand.cutShort` says "did
no work" only of a pass that never reached the model.

`commands/LoopPass` names the iterations. Every pass is its own run that counts
its turns from one. Without the pass in the name, a long loop prints
`Iteration 1` again after `Iteration 31`, with no other sign that a pass ended.
`IterativeExecutor` asks `LoopPass.opening` what to call each turn. That gives
`Pass 2 of 100, iteration 3 (114 overall)` inside a loop and the bare
`Iteration 3` outside one.

One call opens a turn and names it, so a second site cannot take the name and
skip the count. The name is held per thread and deliberately not carried by
`ThreadHandover`: work handed to a thread of its own inside a pass is a run of
its own with turns of its own. The closing line reports the passes and the
iterations together.

### Prompts and caching

`IterativeExecutor.buildPromptWithHistory` renders the whole transcript in
order, so turn N+1 is turn N plus the new exchange. Caching matches on a shared
prefix, so prompts must stay append-only. The executor owns the transcript list
and re-asserts ownership after every `context.putAll`, so no step can swap in a
shorter one. Nothing volatile may go anywhere but the end.
`PromptCachePrefixTest` pins the invariant.

`ai/SystemPromptProvider` loads the `system` prompt template, and
`AIManager.complete` puts it in front of whatever system prompt the caller
supplied. Every completion passes through that one point, so a command cannot
forget it, and the six commands that pass no system prompt still get it. The
principles lead and the command's contract follows, for two reasons:

- A model weights format instructions nearest the user turn most heavily.
- A constant prefix is what a provider can cache.

The template engine reports a load failure: it returns the failure message as
though it were the template. The provider recognises those and contributes
nothing, so no request starts with an error string.

`net/PromptCachePolicy` decides whether to mark a request with a cache
breakpoint:

- Anthropic and OpenRouter use `cache_control`.
- Bedrock uses `cachePoint`.
- The OpenAI and Google families get nothing, because they match a shared prefix
  automatically.

Support varies by model. A rejection that names a cache field therefore latches
breakpoints off for that provider and retries once without them, so a request
that would work without them does not fail.

`TranscriptCompactor` folds the run's transcript when the prompt reaches
`compaction.trigger` of the usable input budget, 0.80 by default. It keeps a
verbatim head, because caching matches a leading prefix. It targets well below
the trigger, so a run does not fold every turn. It latches off when a fold
reclaims less than a fifth, or when head and tail are already the whole
transcript.

The compactor refuses while `IterationProgressGuard` sees repeats, because a
rewrite of history under a model that goes in circles removes the context that
would let it notice. A drop is safe here specifically because `ActionLoopGuard`
and the action history live in the context map and stay out of the transcript.

The chat loop builds one string that embeds a command's output and another that
embeds it again. An append of both puts every result in the prompt twice.
`IterativeExecutor.condenseForTranscript` drops the first copy only when the
entry appended straight after it provably contains the body, so nothing is lost.
It removes text that is not sent yet and leaves sent text alone, so the prompt
stays append-only. On a four-turn run, prompts are 28% smaller from turn 2, and
turn 0 is byte-identical.

`PromptBuilder` appends `CADET.md` before the code files. An oversized context
would push the files past the budget, `truncateToTokenBudget` would drop them,
and the prompt would describe the project and contain none of its code. The
context is capped at a share of the budget for that reason.

`ai/ContextWindow` resolves the input budget. It takes the first of these that
gives a window:

1. the window given by hand for the provider and model (`ai.modelContextTokens`,
   keyed `provider/model`);
2. `ai.contextTokens`, which applies to every model;
3. the model's published context length from the models.dev catalog;
4. the window that catalog publishes for the model's name anywhere in it;
5. 64K for an endpoint nothing publishes at all.

CadetCoder announces the 64K fallback when it uses it, because a window that is
only assumed is the usual cause of a prompt "too large to send".

The catalog is keyed by the provider that serves a model and does not list
gateways. `ModelCatalog.findServedModel` therefore asks the provider the request
goes to, and then the vendor the model id names. `commandcode` with
`deepseek/deepseek-v4-flash` resolves to deepseek's own entry. When the prefix
is a brand and no provider id, as `z-ai/glm-5.3-flash` is, that misses too, and
`ai/catalog/ContextByModelName` looks the model's own name up across every
provider.

The name is listed once per provider that serves it, so a pair nobody publishes
is still a model many providers describe. The window published most often is
taken, and ties go to the wider one. The provider called is believed over the
rest whenever it publishes anything under that name.

`ai.maxTokens` is what the model may generate and goes out as `max_tokens`.
`ai.contextTokens` is what the model can accept and is what the prompt is
budgeted against.

### Images and message shape

A dropped file whose bytes are a picture is read into a `PromptImage`, under a
marker that says `image`, so the line shows what was offered.
`ShellPastes.picturesIn` reads the pictures back out of the line the user
submitted, because a deletion of the marker is the only way back out of a drop.
`InteractiveShell.onEnter` hands them to `PromptAttachments` for the duration of
the turn's thread.

`AIManager.attemptCompletion` attaches the pictures to the first request that
goes out. That is the same single point that composes the system prompt, so a
command added later carries an image without any code for one. They are cleared
once a completion comes back, so later iterations of the agent loop do not pay
for the picture again. They are cleared again when the turn ends.

`PromptData` carries the images beside the prompts and keeps them out of the
prompt text, because an image is not text on any wire that takes one. Each
backend encodes it in its own shape:

| Backend | Image shape |
| --- | --- |
| `AnthropicBackend` | a base64 `source` block |
| `GoogleGenerativeAIBackend` | an `inlineData` part |
| `AmazonBedrockBackend` | a Converse `image` block |
| `OpenAICompatibleBackend`, `OpenAIBackend`, `LlamaServerBackend` (Chat Completions) | an `image_url` part that holds a `data:` URL |

Each backend also names the formats its API documents. It overrides
`LLMBackend.imageMediaTypes` with one of the sets in `net/ImageMediaTypes`:

- `GEMINI` (PNG, JPEG, WebP, HEIC and HEIF, no GIF) for
  `GoogleGenerativeAIBackend`;
- `PNG_JPEG_GIF_WEBP` (no HEIC or HEIF) for every other backend:
  `AnthropicBackend`, `AmazonBedrockBackend` and the three Chat Completions
  backends;
- `NONE`, the default in `LLMBackend`, which is the safe answer for a backend
  added later.

The answer is a set, because the wires disagree about formats as well as about
images. A format a wire does not take is refused along with the request it
arrived in. The sets live in one file so that the backends that agree cannot
drift apart.

`PromptImage.MAX_BYTES` is the largest file that can be attached. It is three
quarters of `MAX_ENCODED_BYTES`, because every provider states its limit on the
base64 string and not on the file. The lowest published limit is the 5 MB that
Anthropic takes on Amazon Bedrock and on Google Cloud.

`ImageChannel.fit` is asked before every request. It drops the images and warns
once in any of these cases:

- the wire has nowhere to put one;
- models.dev says the model does not read them;
- the wire does not take the format a particular image is in. This case drops
  only the images it names and sends the rest.

A model the catalog does not know is allowed to try, because the catalog is
cached and never complete. That is also what lets a local multimodal model on
llama-server receive a picture.

`ChatCompletionMessages` is the one place the Chat Completions message shape
lives. `OpenAIBackend` and `LlamaServerBackend` build their bodies as a map and
let Jackson serialize it, so they can carry a content part and need no escaper
of their own. Both log the whole payload, so they log a copy built by
`ChatCompletionMessages.elided`, which keeps the shape and drops the bytes.
Otherwise several megabytes of base64 would be redacted, measured and written to
a file that outlives the session.

On the Chat Completions protocol the roles are the template, so no chat template
is rendered into a request. The server reads `system` and `user` and applies the
control tokens the model was trained on. That is the one rendering that can be
right: OpenAI knows its own models, and llama-server reads the template out of
the GGUF it loaded.

A chat template rendered into a single `user` message would break a request in
three ways, and `ai.chatTemplate` defaults to `chatml`:

- The model gets its own control tokens as literal text, such as
  `<|im_start|>system ... <|im_end|>`.
- The system prompt arrives with the user's role, and the server templates the
  result a second time.
- An attached image turns the first message's content into a parts array, and a
  provider that joins message contents as strings refuses the request.

`ai/templates` and the `ai.chatTemplate` setting exist for a local model that is
fed a raw prompt in place of a `messages` array. Nothing on these three wires
reads them. `PromptData.useFormattedTemplate` and `getFormattedPrompt` therefore
have no caller in `src/main`; a wire that sends one string would be their
caller. No command sets the template, because a choice of template changes
nothing a request carries.

### Requests, retries and timeouts

Every HTTP request is built with `net/HttpRequests.to(uri)`, and a test holds
every call site to it. A plain `http://` address is sent HTTP/1.1. On such an
address `HttpClient` otherwise asks for HTTP/2 with `Upgrade: h2c`. A model
server run by uvicorn (vLLM, SGLang) then never reads the body and refuses every
completion with "Field required ... 'loc': 'body'". Over TLS the version is
agreed in the handshake, so HTTPS keeps HTTP/2.

`ai/metrics/` records the cached and new input split, output tokens, duration
and a trailing-window throughput rate. It hooks into `AIManager.complete`,
because every caller passes through that single point. Backends publish whatever
usage the provider reported through `ReportedUsage`, and CadetCoder uses those
counts verbatim. `TokenEstimator`, at roughly four characters per token, is the
fallback for providers that report none. A leading `~` marks an estimate, so
nobody mistakes it for a billed figure.

`TokenUsage` holds the per-provider arithmetic, which genuinely differs:

- OpenAI and Gemini report a total input that already includes the cached part.
- Anthropic and Bedrock report the cached figures separately and additively.

Three separate limits bound a request:

| Limit | Default | Setting | What it bounds |
| --- | --- | --- | --- |
| Connect timeout | 10 s, at most 300 s | `cadet.llm.connectTimeoutSeconds` property | the connection, so an endpoint that is down is reported quickly |
| Completion timeout | 300 s | `ai.completionTimeoutSeconds` | the wait for response headers |
| Response deadline | 600 s, at most 3600 s | `cadet.llm.responseDeadlineSeconds` property | the whole exchange, through `BoundedHttp` |

For a completion the header wait is the whole generation, because nothing
streams and a provider sends no headers until the model finishes its reply. The
response deadline is the only thing that stops a body that stalls mid-flight.
`AbstractLLMBackend` reads both properties and ignores a value outside its
range.

A reasoning model charges its thinking to `ai.maxTokens`, the same budget as its
answer. When it spends it all before it writes anything, the reply comes back
with `finish_reason: length`, empty content and a reasoning field that holds an
unfinished thought. `ChatCompletionContent` reports that and does not hand the
thought back as the answer. How long a model thinks varies with what it is
asked. A budget that is too small therefore fails some turns and not others,
which reads as an unreliable provider.

`ai.maxTokens` is therefore `0` by default, which means no ceiling is sent.
`net/OutputBudget` holds that rule, and every wire that may omit the field omits
it. Anthropic's Messages API is the exception, because it requires `max_tokens`
and refuses a request without one. That backend sends the model's own published
output limit instead, read from `ai/OutputWindow`.

`ai/OutputWindow` answers how many tokens the model may produce, which is a
separate question from what the request asks for. `TranscriptCompactor` needs it
to hold room back in the input window for a reply nobody wrote yet. The reserve
is capped at half the window, so a model whose published output limit is its
whole context cannot leave the prompt nothing.

An interruption is `LLMException.Kind.STOPPED`, never `TRANSPORT`. A transport
failure is retryable, and a run the user stopped must never be offered back to
them to try again. `ManualRetry` declines whenever a stop is asked, before it
puts the question and again after the answer arrives. A request to stop cancels
a prompt that waits for input, and a cancelled prompt answers with an empty
line, which is what Enter sends.

`TransientPayload` decides whether a 200 that yielded no completion is a
transient failure. It reads failure wording only inside a top-level `error`
node, never across the body. A completion's text is arbitrary: a correct answer
about rate limits contains every word a naive scan looks for, and a retry would
discard a good response and bill it again.

`TransientPayload` splits an unparseable body the same way. A body that opens as
JSON was truncated in flight, so CadetCoder retries. A body that never was JSON
is the wrong endpoint, which is terminal, and
`unparseableSuccessBodyThrowsProtocolFailure` still holds.

The JDK's request timeout bounds the headers and leaves the body unbounded: a
two-second `HttpRequest.timeout` lets a six-second body through. The header wait
and the body wait are therefore two separate budgets in `AbstractLLMBackend`.
Without the second, a body that stalls after the headers blocks forever, and the
run freezes with no timeout and no retry. `sendAsync` is used solely so that the
body wait can be bounded at all. Cancellation closes the socket, so no
connection leaks.

Retries happen in exactly one place. `jdk.httpclient.enableAllMethodRetry` is
deliberately left unset. It would repeat failed POSTs inside `send()`, invisibly
and without regard for `Retry-After`, on top of the loop in
`AbstractLLMBackend`. That would turn a budget of N attempts into an
unpredictable multiple of it.

A request made inside a loop pass never asks the terminal to retry, because a
loop runs unattended and a question at the terminal would stall it until someone
answers. `AIManager` asks `LoopPass.isInAPass`. Inside a pass, `ai/OutageWait`
waits out a 5xx or a failure to connect: 1, 1, 2, 4, 8, then 15 minutes at a
time, until two hours pass since the request first failed. The
`cadet.loop.outage.*` properties set those limits. After that the request fails
and the loop stops.

`OutageWait` does not wait for a rate limit or a rejected key, because a
provider that cut the account off answers that way for as long as anyone asks.
Ctrl-C ends a wait at once, as a stop.

### Output

`ui/Glyphs` owns the console markers. Each has an ASCII fallback for a terminal
that cannot render Unicode:

| Marker | Unicode | Fallback | Method |
| --- | --- | --- | --- |
| success | `✓` | `[OK]` | `successMarker` |
| warning | `⚠` | `[WARN]` | `warningMarker` |
| error | `✗` | `[ERR]` | `errorMarker` |
| info | `ℹ` | `[i]` | `infoMarker` |
| header | `▎` | `=== … ===` | `headerMarker`, `headerCloser` |
| sub-header | `▸` | `-- … --` | `subheaderMarker`, `subheaderCloser` |
| iteration | `•` | `[*]` | `iterationMarker` |

`printInfo` emits a notice alone, without `ℹ`. That marker has one job, which is
to make a Markdown fence inert (see rule 3 below and `indented`). A notice
therefore classifies as ordinary text, so in the shell it is drawn in the normal
text colour.

`ui/ThemedOutputFormatter` is the only thing that puts a marker on a line, and
`ui/OutputLineStyler.classify` is the only thing that reads one back. Two other
places depend on that contract and must move with it:

- `commands/ShellTranscript.cleanSubheader` strips the sub-header marker to
  title a nested section.
- `ui/MarkdownRenderer` lets a classified line through untouched and does not
  parse it as Markdown.

A sub-header is structural, because it opens a `Tab`-navigable section in the
shell. A change to its shape without a change to both parsers silently makes
agent steps impossible to browse, with no error.

Four rules set the layout of an agent run's transcript. Keep them together,
because together they keep a run's structure visible.

1. `ui/ProseMeasure.READABLE_COLUMNS` caps how wide prose is drawn, whatever the
   terminal offers. Code, tables and captured command output keep the full
   width, because their line breaks are part of what they say.
2. `printIteration` prints a blank line above the marker. That blank line
   separates one turn from the next.
3. `printOutputBlock` indents what a command printed and adds no marker. The
   text already carries the markers of the command that printed it. In the
   shell, the block is sent as program output (see below). Elsewhere, a block
   that holds a Markdown fence is marked on every line instead, so no fence can
   pair with a later one and swallow what follows.
4. A step's stated reason goes on a continuation line of its announcement and
   never on a line of its own. A step's outcome is marked as a success or a
   warning. Between them, a run's marked lines are its structure and its status,
   and everything else it prints is unmarked prose.

`ui/CollapsedOutput` defines the two markers that wrap what the live console
leaves out. `commands/StepOutput.printForConsole` writes them around a command's
output. `CollapsedOutput.hiding` writes them around the run's bookkeeping:

- the `Iteration <n>` line in `commands/IterativeExecutor`;
- the request metrics line in `ai/AIManager`;
- the record of a step that worked in `ChatCommand` and `AgentCommand`.

A step that FAILED keeps its record on screen. That record is marked as a
status, so that one column can be scanned for the place a run went wrong.

`ui/ProgramOutput` defines two more markers. They wrap what a program printed or
what a file holds, and `MarkdownRenderer` reads nothing between them as
Markdown. The shell renders a result as Markdown because the model writes its
answers in Markdown. A program does not: in `git diff` output a line that starts
with `-` or `+` is a removed or an added line, and read as Markdown both became
the same bullet. A line inside the run that carries one of the tool's own
markers keeps its style. These write program output through `ProgramOutput`:

- `printOutputBlock`, which covers a step's output and `job output`;
- `bash`, `diff`, the git status in `commit`, `context show` and
  `notebookread`;
- the lines of a file in `read` and `multiread`, and the matching lines in
  `grep`;
- the echo of a step's captured output in `ActionRun` and `AgentCommand`.

A command that prints line by line opens a run with `ProgramOutput.begin()` and
closes it with `ProgramOutput.end(...)` in a `finally` block. A code block that
a model left open ends at either marker.

Both marker pairs follow the same rules. They are printed only when
`CollapsedOutput.isSupported()`, so neither a plain console nor output collected
for the model ever sees them. `CollapsedOutput.strip` removes both from what is
logged. `ShellTranscript` opens no section inside either run.

`commands/ShellTranscript.routeCompleteLine` stops the filing of a sub-header as
a new section while a collapsed run is open. A command prints sub-headers of its
own; `multiread` heads every file it reads with one. Each such sub-header would
otherwise open a section that takes the rest of the output out of the segment
with the opening marker. The body would then show in full while the marker had
nothing left to hide.

`commands/ShellConsoleRenderer.bodyLines` is the only thing that acts on the
markers. The live view drops what lies between them, and a focused result keeps
it, which is what makes a result worth a click. Markers are written only when
`CollapsedOutput.isSupported()` says a renderer will read them back, so a plain
console and a worker's collected output never carry them.
`logging/SessionLogger` strips them before it writes. A plain console keeps the
head of the output and counts the rest instead, because there is nothing there
to open.

A display call reads the configuration to decide verbosity, colour and theme.
Any part of that can be absent while the application starts or under a test
double. Every path that reads configuration in order to decide how to print
falls back to defaults and does not propagate the failure, so output never fails
its caller.

The cleanup blocks in `InteractiveShell.runShell` and `Main`'s shutdown hook
catch `Throwable`. Shutdown is where a `LinkageError` surfaces, because a class
that nothing loaded before then may fail to load at that point. An `Error` is
not an `Exception`, so a narrower guard lets it escape, and the session ends
with a stack trace over a terminal still in raw mode. Nothing in a cleanup step
is worth a failed exit. What went wrong goes to the debug log.

A step's output prints through `OutputFormatter.printOutputBlock`. It is console
text and no source, so a Markdown fence is wrong twice: the shell renders an
unlabelled fence as a block titled `code`, and the fence is copied along with
the text. Verbosity never suppresses the block either, because a step's own
diagnosis of a failure leads it.

A failure names the invocation once. `ChatActions.announce` prints the command
and its arguments before the dispatch, and `CommandOutputVisibility.summarize`
repeats them only when the command's whole output stands in between. The step
result carries the reason alone. For the same reason a nested run prints no run
title of its own, which is why `IterativeExecutor.announce` checks
`isModelDrivenWork`.

`ui/CommandOutputVisibility` decides how much of a command's output reaches the
console. `commands/CommandOutputBudget` decides how much reaches the model.
Elision for a reader must never shorten what is sent, and the console's elision
notice is never part of the captured text.

The model-facing backstop sits above every per-command limit, so those stay the
limits that normally apply. That covers the line count of `read`, the total of
`multiread` and the character cap of `bash`. The backstop catches pathological
output alone. It says how much it dropped and that a re-run will not help.

Two console routes carry AI-run command output, and both are gated:

- the echo in `ActionRun.dispatch` for chat and in `AgentCommand.executeCommand`
  for the classic agent loop;
- the step-output block in `IterativeExecutor`.

The step block is truncated and never suppressed, because a step's own
diagnostics lead that same message. The step result also carries the command's
own diagnosis, so a reader sees the reason a step failed under the same elision
rules as any other command output.

`OutputRouter` captures output by replacement of `System.out` for the whole
process. That suits one run and is useless for several, because interleaved
lines cannot be sorted back out afterwards. `OutputCapture` installs a
thread-local sink for the duration of a worker, so each worker's transcript is
its own. It restores the previous sink and does not clear it, so a nested
capture cannot detach its owner's.

`wrapToks` drops whitespace at the start of a line. That is right for a line the
wrap created and wrong for the indent the source line had. A lost indent turns
every aligned block the CLI prints into ragged prose.

### Model-driven commands

A command a model dispatched never stops to ask the user anything.
`ModelDispatch.run` wraps the dispatch in
`InteractivePrompts.asModelDrivenWork`, which makes `InteractivePrompts.isOn()`
false for that thread. `edit`, `multiedit` and `refactor` then take the path
they already have for a run with nobody to ask. The reason is mechanical: the
command's console output is collected, so its question reaches the transcript
while the bare input prompt reaches the screen. The run would then block on a
prompt the user cannot read.

`isOn()` answers the same way whenever `OutputCapture.isCapturing()`, whoever
asked. A question nobody is shown is not a question, and an unanswered
confirmation is denied, so the exchange would only cancel the step.
`InteractivePrompts.carrying` also carries the scope across a thread handover,
and `ThreadHandover` applies it alongside the other three.

Every interruptible command runs on a thread of its own. Without the carried
scope, that thread would read the `cadet.interactive` property, which is true
inside the interactive shell where an agent loop runs. `commit`, `edit` and
`refactor`, all interruptible, would then put a confirmation to a user who never
sees it. The confirmation would be answered no on that user's behalf, and the
model would hear that its commit was declined.

Two more rules hold the same line:

- `ActionRun` collects through `CapturedRun` and never swaps `System.out`, so
  every `OutputCapture.isCapturing()` check answers correctly.
- `UserAsk.inputPrompt` and `ManualRetry` put the question on the input line
  itself, so no surface can show a prompt without its question.

### Interactive shell

Live request state is rendered once, on the top line. It carries the spinner,
the command in progress and the elapsed time of the request. Nothing else
repeats it. Where you are in the view is a separate question: live at the tail,
scrolled back, focused on a result or pane, or in select mode. That question
stays useful while a request is in flight. `ShellStatusBar.state` answers it,
and the console draws that answer in its own top-right corner.

The bottom rows hold the keys, which are reference material and the same shape
every frame. A full-width rule opens that region, as one opens the input line
below it, so the keys do not read as one more line of the transcript. A region
only one row high keeps the keys and drops the rule.

Nothing in the transcript is drawn to the terminal's width. Borders, full-width
rules and per-line gutters all read well on screen, and all of them are copied
along with the text. The code draws the shell as follows:

- Each console region carries a one-row heading, drawn by
  `ShellWidgets.sectionTitle`, and no border. That covers the live transcript, a
  focused result, the list of panes, a job pane and a worker pane.
- A result opens with its own heading (`ShellConsoleRenderer.resultHeading`):
  the status mark and the request as typed. A blank line above the heading
  separates it from the result before; the first result has no blank line above
  it.
- A code block shows its language, dim, on a line of its own, with a blank line
  above and below (`MarkdownRenderer.renderCodeBlock`). Code lines carry no
  gutter. A Markdown thematic break renders as an empty line.
- Only the input line (`ShellInputLine`) and the status bar (`ShellStatusBar`)
  draw a full-width rule, `glyphs.rule(width)`. Both sit outside the transcript,
  so nothing copied out of the console carries them.

The modal help overlay (`ShellHelp`) is the exception to all of this, because it
floats over the transcript and needs edges to read as a panel.

`/clear` and Ctrl+L clear the console through `ShellTranscript.clearView`, which
hides every region so far and keeps it. The scrollback saved with the session is
read from the same transcript and ignores the mark, so a resumed session still
shows what was cleared. A transcript emptied on clear would erase that output
from the saved session too. The session log is written as lines arrive and is
never touched by the console. `ClearCommand` refuses a model, because the
console is the user's view of what the model did. A switch of sessions still
empties the transcript, through `InteractiveShell.clearOutput`.

`Tab` and `Shift-Tab` walk one list of panes: every worker of the most recent
run, then every background job, from `BackgroundPanes.all`. `BackgroundPane` is
what the console holds while one fills it. It is one field of one type, so the
rule that exactly one of worker, job and list may show needs no enforcement.

A worker's output is collected and not streamed, and a job's output belongs to
another process, so neither appears in the transcript at all. `workers show <n>`
and `job output <id>` both need a free prompt. During the run that started the
work, that means an interrupt of the thing you want to watch, so the panes
exist. `F5` opens the list of both at once. Up and Down pick a line and Enter
opens it, but only with nothing typed, so Enter never opens a pane in place of a
command. `F5` opens the list because Shift-Tab already walks the cycle
backwards.

A job pane reads `job.output().since(0, 0)`, never `BackgroundJob.readNew`.
There is one cursor per job, and `job output` and the model's end-of-job notice
both read through it. A frame drawn through that cursor would consume the lines
they are about to show. A view of something must not change it.

The pane redraws on the tick whenever one is open. A worker's transcript and a
job's output both grow without a change to `dirty`: nothing in this process
writes the first, and the second is a separate process. Without the tick, a pane
that watches live work would freeze the moment the shell goes idle.

A drag selects document rows, so a selection is not limited to what is on
screen. Three pieces make that work:

- `ShellConsoleView.dragScrollLines` turns a pointer at the first or last row of
  the console into a scroll, and pulls harder the further past the edge it sits.
- `documentRowNear` resolves a pointer outside the console to the nearest
  visible row. `documentRowAt` answers "nothing" there and would leave the
  selection stuck.
- `scrollWhileHeld` moves the view without release of the hold a drag put on it,
  and moves the hit map with it, so the far end of the selection does not trail
  the view by a tick.

The shell calls `scrollWhileHeld` on every drag report and again on every render
tick. A pointer that stops moving sends no more reports, and a pointer held
against an edge must keep the scroll in motion.

`ShellPastes` keeps what a paste stands for. A paste of more than one line, or
longer than `MAX_INLINE_CHARS`, is kept there, and a marker (`[#1: 42 lines]`)
goes into the input line. A dropped file is kept as its path under a marker that
names the file. `InteractiveShell.onEnter` expands the markers it issued and
runs that, while the console, the history and the session log keep the line as
typed. Only markers this session issued are substituted, so a line that merely
looks like one is sent as it reads. See "Images and message shape" for a dropped
picture.

`InputEditing` answers where the caret lands when it moves a word at a time. A
word is a run of letters, digits and underscores, which lets a path be crossed
segment by segment. `Ctrl` and `Alt` are both accepted on the arrows, because
terminals disagree about which one carries a word-wise key.

`ShellKeys.dismissal` decides what one press of Escape backs out of, in this
order:

1. the prompt;
2. select mode;
3. a selection;
4. a focused result;
5. the line under edit.

One press backs out of one thing, in that order, because the press that cancels
a question must not also drop a selection made before the question appeared. The
line comes last, because it is the only one that is not a mode. `Ctrl+U` clears
the line whatever else is in progress, which is the key the shells use. `Ctrl+C`
cannot be that key here, because on an idle shell it asks whether to quit.

F4 select mode gives the mouse over to selection. A drag selects in either mode,
so the only job of select mode is to stop a click that never moved from opening
the result under it.

The help has one source, `HelpContent`. It returns sections of rows, and the two
surfaces render them their own way: `HelpCommand` through `OutputFormatter`, and
`ShellHelp` as lines in the overlay. One source means the overlay and `/help`
cannot disagree about which commands exist or what they do. The invocation
prefix decides what is included: at the shell's prompt the keys, the mouse and
the slash rule apply, and on the command line they do not.

### Workers

`WorkerTask.prompt()` puts the shared briefing first and the per-worker task
last, so workers share a cacheable prefix. Workers never see each other's
output. Shared context makes findings comparable. Shared output makes workers
converge on whatever the first one said. `WorkerPool` returns results in task
order regardless of completion order, so "Worker 2" means the same worker every
time.

`WorkerActivity` is the one thing the shell polls directly. Every other activity
reports itself by a print, and the header reads the transcript's active section.
Workers break that by design. Without `WorkerActivity` the bar would hold the
launch line for minutes, which looks the same as a hang.

### Sessions and resumed context

`ResumedContext.forCurrentSession()` is the shared gate that seeds a continued
run's transcript. Restored context leads every call that opens a resumed
session, the loop's turns and the calls before them alike. A seed inside
`IterativeExecutor` alone would leave the first call of `ChatCommand` without
it, and that call decides what to do with the user's follow-up. The seed is
bounded to a quarter of the prompt budget and marked as prior context. It is
gated on `isResumed()`, because an unconditional seed would drag the previous
invocation's conversation into every unrelated command that follows.

Conversation history is an exchange, recorded where each half is knowable.
`ChatCommand` records the user's request, because the user's actual words are
still separable there from the prompt they are rendered into. A record in
`AIManager` would store the whole rendered transcript and grow quadratically.
CadetCoder drops consecutive duplicates, because a retried turn records the same
text again.

### Providers and credentials

`ai/providers/` declares the connectors and the four wire protocols they speak,
as `ConnectorProtocol`:

- `OPENAI_CHAT`, OpenAI Chat Completions, implemented by
  `net/OpenAICompatibleBackend`;
- `ANTHROPIC_MESSAGES`, Anthropic Messages, implemented by
  `net/AnthropicBackend`;
- `GOOGLE_GENERATIVE_AI`, Google Generative AI, implemented by
  `net/GoogleGenerativeAIBackend`;
- `BEDROCK_CONVERSE`, Bedrock Converse, implemented by
  `net/AmazonBedrockBackend`.

`ai/catalog/` loads and caches the models.dev catalog. `auth/CredentialResolver`
resolves a connector's key, and takes the first of these that is set:

1. the saved key for that provider;
2. the legacy key field in the configuration;
3. the first environment variable, in order, that the provider declares and that
   is set.

`LocalAIClient` and `APIClient` serve as the fallback when no connector is
selected.

The `models` command counts a connector by what it will actually list, whatever
the catalog carries. A connector the catalog does not carry is asked at
`{base}/models` through `ProviderModelListing`. That class caches each answer
for the life of the process and logs a failure at debug level. A connector that
publishes its own list and did not answer prints `-`; a connector with no models
prints `0`. `models find` searches those connector-published lists before the
catalog.

The summary of `models` ends with the two numbered steps that select a model,
and a listing of one connector ends with the `models use` line for it.
`models current` names the input window beside the provider and model, with the
source `ContextWindow` resolved it from. So a window that came from the catalog
at large can be seen before a request is refused as too large.

A choice of model for a connector whose default address is on this machine,
which is `local`, first asks where its server runs. `models select`,
`models use local` without a model, and `login local` all ask.
`commands/ServerAddress` reads the answer as `host:port`, a bare host or a full
URL, and takes any part left out from the address in use. So `192.168.1.20`
means the same port and path on another machine, and Enter keeps the address.

The model list is fetched from the address given, and
`ConnectorSupport.applyActiveModel` saves it as
`ai.providerOptions.<id>.baseURL` together with the model.
`models use local <model>` asks nothing.

The picker lists every model the connector offers. Any model id can be typed in
place of a number, since a server may serve models it does not list. When the
catalog publishes no window for the model chosen and none was given before, the
picker asks for it, and Enter keeps the assumed 64K. The answer is saved in
`ai.modelContextTokens`, so it applies only to that model.

These commands set a model's window:

- `models context <tokens>` gives the window of the active model, and
  `models context clear` removes it.
- `models use <provider> <model> --context=<tokens>` gives the model and its
  window on one line.
- `config ai.modelContextTokens.<provider>/<model> <tokens>` gives any model its
  window.

Everything after `modelContextTokens.` is the key, because a model id such as
`qwen2.5-coder` holds dots of its own. The warnings about an assumed window and
the compactor's advice name `models context`.

Lists the user chooses from are shown whole. `session list` lists every session
unless a count is given. A path that is not found lists every file that matches
it, and any of them can be chosen by number.

Instructions are printed whole too, and the console wraps them. That covers the
step line for each command the model runs (`CommandOutputVisibility.describe`),
a session's first instruction in `session list`, a run's task in `runs`, and a
worker's task. Only the one-row bars fit text to their width: the header, the
status bar, the activity line and region headings.

`ai.zeroDataRetention` defaults to on. It applies each connector's
`DataRetentionControl` on every protocol the connector speaks, as headers, body
fields, or both. The control records what the provider can actually promise, as
`ZERO_RETENTION`, `NO_STORAGE` or `NONE`. OpenAI's `store: false` does not give
the guarantee that `x-cmd-zdr` of Command Code and `provider.zdr` of OpenRouter
give. A report of it as one would be worse than a report of nothing.

`AuthHeaders.apply` is the one place a credential becomes a header, and it reads
the connector's declared `AuthScheme`. A second copy would let a provider be
authenticated correctly for one request and not for another. `AnthropicBackend`
therefore takes the scheme and does not hardcode `x-api-key`, because
`ModelWire` lets a gateway speak the Messages shape for the Claude models it
resells. Command Code declares `BEARER` and routes `claude-*` to that protocol
through `CommandCodeWire`.

`OpenAICompatibleBackend` holds a `Supplier<String>` in place of a key, and
resolves it in `applyAuth`. A fixed key is a supplier that returns the same
string. A GitHub Copilot token lives about twenty-five minutes, while the client
stays cached until the model or the login changes. `ConnectorAIClient` therefore
hands down a supplier that mints a new token through
`GitHubCopilotAuth.getValidCopilotToken` when the token comes within
`REFRESH_MARGIN_SECONDS` of expiry. Without it, any run longer than the token's
life fails with 401s, which `RetryPolicy` rightly refuses to retry.

`ChatCompletionContent` reads the reply out of a Chat Completions response, for
both backends that speak that protocol. `message.content` is documented as a
string and frequently is not one:

- It arrives as an array of content parts, which is the shape this client itself
  sends for a cache breakpoint.
- It arrives null beside the text, which then sits under `reasoning_content`,
  the field DeepSeek's API documents for a reasoning model.
- It arrives null with the text under `reasoning`, the spelling OpenRouter and
  several gateways use.

Content wins whenever it carries text, and a reasoning field is read only when
content does not. `AnthropicBackend` makes the same call for a reply of thinking
blocks alone. When no text exists anywhere, the error names the reason from
`finish_reason` or `stop_reason`. An exhausted output budget, a filtered reply
and a tool call each want a separate action from whoever reads it.

A connector that was never told where it lives is unavailable, and never
available at a nonsense URL. Both Cloudflare connectors read `accountId` and
`gatewayId`, and fall back to `CLOUDFLARE_ACCOUNT_ID` and
`CLOUDFLARE_GATEWAY_ID`, the way `region` reads `AWS_REGION`. They fail closed
when neither is set, as `azure` does. An explicit `baseURL` is enough on its
own.

### Search and context gathering

`ContextEngine.find` takes what a user or a model wrote as plain text, and no
query language. The text goes through the index's own analyzer, and the terms it
yields become one `SHOULD` clause each, so Lucene's `QueryParser` never sees it.
That settles two failures at once:

- No character is syntax, so a `/` in a file path is plain text and does not
  open a regular expression.
- The clauses are counted as they are made and capped at `MAX_QUERY_TERMS` and
  at Lucene's own clause limit, so a request longer than 1,024 terms is searched
  and not refused.

Do not escape the text in a caller, because the analyzer already reads it as
plain text.

`ContextEngine.find` returns `ContextMatch`, which carries a project-relative
path, the score, and the matching lines with their numbers. `RelevantLines`
picks those lines by a tokenisation of the content with the index's own
analyzer, so what is shown is what Lucene actually hit. The bounds live where
the content does, as `MAX_LINES_PER_FILE` and `MAX_LINE_CHARS`, so the three
callers cannot disagree about them. `SearchReport` renders a result in the shape
`grep` uses, because the two commands answer the same question by separate
means.

`GrepCommand.compilePattern` searches for a pattern that does not compile as a
regular expression as plain text instead, and says so. A pattern that does not
compile is by definition not a valid expression, so no search that works changes
meaning, and what is left is the text the caller wrote. So `grep placeBuy(`
searches for the text `placeBuy(` in one turn.

`\|` in a `grep` pattern means "or", as in GNU grep
(`GrepPattern.withBarsAsAlternation`); a literal bar is `[|]`. Java reads `\|`
as a literal bar, and models write `\|` for alternation, as in
`tv.args\|tv.heap`.

`MultiReadCommand` reads N files in one call and is the advertised preference
over repeated `read`. It delegates each file to `ReadCommand` on purpose, so a
batch cannot bypass path resolution, project-boundary enforcement or the
credential-file refusal. Its three budgets always report what they dropped.

`IterativeExecutor.requiresUserInput` examines the prompt alone and never
consults command output. Output is data. It can contain any text the filesystem
or a subprocess produced, and that includes this application's own confirmation
prompts. A read of it as protocol would let a `read` or `grep` over this
repository turn the model's instruction format into a question aimed at the
user.

A prompt that names the response contract is addressed to the model whatever
else it contains, and that check runs first. The contract is `ACTION_START`,
`SUCCESS:`, and retry, skip or stop. The prompt quotes the user's own request
back to it, so a request such as "please specify the format" would otherwise
read as a question from the application.

`ai/parsing/ToolCallParser` reads a tool call the model wrote in its own trained
notation. The prompt asks for an `ACTION_START` block. A model trained to call
tools reaches past it for the notation its vendor taught it, because the command
catalog in its prompt reads to it as a list of tools. Without the parser, such a
reply counts as a format error, and the run ends after two re-prompts.

The notations live in `ai/parsing/toolcalls`, one reader each:

- DeepSeek's DSML in both its V4 and V4.1 spellings;
- the token-delimited calls V3 and R1 write;
- the `<invoke name=...>` tags Claude writes, and the many variants of them;
- the JSON call behind `<tool_call>`, `[TOOL_CALLS]`, `<|python_tag|>` or
  `<function=...>`;
- the Harmony channel header GPT-OSS writes.

Every reader produces a `ToolCall`, and the parser turns that into the same
action any other strategy produces. A new notation costs a reader and nothing
else.

An action the safety screen refuses is not a format error. The engine returns
the `SECURITY_ERROR` with `REFUSED_ACTIONS_METADATA` set, and does not pass it
to error recovery. Error recovery would replace the reason with "no structured
action format found" and send the format example back to a model whose block was
correct. `commands/RefusedAction` tells the model what was refused and why, and
clears the format count. It ends the run only after refusals in a row pass their
own limit. A reply refused as a whole, by the injection check, has no action in
it and is not reported as one.

A call that arrives in a response field, outside the reply text, is the same
answer. `ToolCalls.textFor` writes any of these back out as text, and the four
backends hand that on so the turn does not fail:

- an OpenAI `tool_calls` array;
- an Anthropic `tool_use` block;
- a Bedrock `toolUse` block;
- a Gemini `functionCall` part.

Text is the only thing the parsing engine reads, so this is the seam where a
call in any shape joins the one path.

`ai/parsing/ActionBlockParser` never folds a flag into a positional. Every
per-command argument parser recognises that command's own flags before it
assigns positionals. It drops an unrecognised `-` token and does not store it as
a path, pattern or query. A value flag also refuses a value that is itself a
flag. Consumption of one would turn a malformed pair such as `--path -p` into
`--path=-p`, which the command's picocli parser rejects, so a recoverable typo
would become a failed action and a wasted turn. The `--flag=value` form still
expresses a value that genuinely begins with `-`.

### Security

`security/SecurityValidator` holds the one credential denylist. A command that
adds its own rule makes the answer depend on which command you went through.
`commands/ProjectFile` is how a new command asks that rule. It resolves a path
and hands it to `isFileAccessAllowed`, which covers traversal, the project
boundary, system paths and the denylist together.

`security/ShellCommandLine` is the one parse of a command line. It takes the
line apart the way a shell would, through quotes, redirections, `$(...)`,
backticks and every separator. It reports three things:

- the programs the line starts;
- the files it redirects into;
- the tokens it could not read, because a variable builds them.

`SecurityValidator.screenCommand` is written against that parse and does not
inspect raw characters. A screen of characters alone would refuse
`awk '{print $1}'` for the `$` in its own script, and would say nothing about
why.

`SecurityValidator.CommandScreening` separates two answers:

- A refusal is about what the command does, and nothing overrides it.
- An unclear answer means the line cannot be read: a program named by a
  variable, or a `bash -c` whose string is built by expansion.

`ai/CommandApproval` settles the unclear ones, and `security.commandApproval`
says who is asked.

`manual` is the default and asks the user at the terminal. Inside a worker
nobody can be asked, so a command there that needs approval is refused. `auto`
asks the model, in a request of its own. Anything but `ALLOW:` or `DENY:` as the
first thing the model says is read as a refusal, so a model that deliberates out
loud gives no consent.

The model is also shown the script a command runs, where the line makes the
script certain. `security/ProgramFiles` names it only for a line of this
shape:

```text
[cd DIR &&] PROGRAM [options] SCRIPT [arguments] [> FILE] [2>&1] [| READER ...]
```

- `PROGRAM` is `python`, `node`, `ruby` or `perl` and `SCRIPT` has one of its
  extensions, as in `python3 tool.py`, or a shell and any file, or the script
  is run by its path. Interpreters that load code named in a configuration
  file, such as `bun`, are not on the list.
- `DIR` is absolute or starts with `./`, and exists.
- `READER` is `head`, `tail`, `grep`, `wc` or `cat`.
- The line holds no expansion, pattern, substitution, `~`, `;`, `<`, `..`,
  parenthesis, backslash or line break, and no `&` except that `&&` and `2>&1`.
- Each option before the script is on the program's short list of options that
  take no value, load no code and change no directory, such as `python3 -u` and
  `bash -euo pipefail`.
- No redirection writes into the script under any name, as a hard link would.
- `PATH` holds only absolute directories.

For any other line the model sees the line alone. A shell line can change a
file before it runs it, or run a different file, in more ways than a reader of
the line can follow, so the list is closed.

`ai/CommandFiles` shows the script only when its real path, through any link,
lies inside the project, whatever `security.allowOutsideProject` says. It also
applies the credential and file rules `read` applies, and reads at most what it
shows: 20,000 characters. A file with a NUL byte or a zip archive's end record
is not text, because Python runs a zip archive named `.py` by the
`__main__.py` inside it. The script sits between two lines that carry a random
value for this one request, so it cannot close its own block and address the
model as the tool. Its name is printed with control characters replaced.

In `manual` mode, `CommandApproval.askThePerson` asks inside
`OutputCapture.outsideCapture`. An agent step runs with its output collected so
the model can read it. A question printed into that collection would go into the
step's transcript and never reach the screen, and the gate would answer "no" on
the user's behalf. `WorkerPool.insideWorker` separates "nobody is there" from
"the output is collected".

A model may read the saved setup but not change it. `config`, `models`, `login`,
`copilot`, `prompt`, `ubermode`, `compact` and `theme` refuse their subcommands
that change it, through `ModelDispatch.refuseSetupChange`. `CommandGate`, which
screens `bash`, ignores a model's `--force`, and so does `undo`.

`push --force` always asks the user through `CommandApproval.askThePerson`,
never the model, and is refused when nobody can be asked. `GitPushes` marks a
`git push` in a shell line that can discard commits on the remote, and the
screen returns `CommandScreening.forThePerson`. `CommandGate` then asks only the
user, whatever `security.commandApproval` and `--force` say.

The same mode governs any other question a model's step addresses to the user,
such as the confirmation of `edit`, `multiedit`, `refactor` or `commit`. In
`auto` mode, `ai/StandInAnswer` puts the question, and what the command showed
with it, to the model in a request of its own. The reply must start with
`ANSWER:`, and a confirmation accepts only yes or no. A failed request, or a
reply in another form, declines. In `manual` mode such a question reaches
nobody, and the command takes its default: a confirmation is answered no, and
`edit`, `multiedit` and `refactor` apply with a note that nobody was asked.

A confirmation asked through `OutputRouter.getConfirmation` from collected
output is answered the same way in `auto` mode, such as the one `write` asks
before it replaces a file. The model sees only the question, so the question
names its subject: `write` names the file and both line counts. A question asked
outside the collection, such as `Run it anyway?` in manual mode, still goes to
the user.

What a question is asked on is shown whole. `multiedit` shows a unified diff of
the file before and after its edits, and `edit` and `refactor` show every fenced
block of the change (`commands/ChangePreview`). A cut preview would make the
model in `auto` mode decline good edits it cannot see.

`commands/BashCommand` and `commands/ActionRun` are the two callers of the
screen. Both screen the whole command line and never only its first word. Both
read that line with `BashCommand.read`, so the options of `bash` itself are
never mistaken for the program about to run. When the shell cannot find a
program (exit code 127) and its name is a CadetCoder command, `bash` says to
give it as an action of its own, as for `bash job list`.

`commit -a` checks what it is about to stage for secret-looking and large files
before it stages them. The files are the new and changed ones git reports
(`GitIntegration.pathsStageAllWouldAdd`), so ignored build output is not
checked. A walk of the working tree would warn about files such as those in
`build/classes`, which git never stages.

A path argument that passes `SecurityValidator.isFileAccessAllowed` reaches the
command as written. `ActionRun` does not delete `..` from it or normalize it.
The access check already refuses traversal, so a rewrite would only change which
file is meant, and `.` would normalize to an empty path.

`security/AllowedActions` holds `security.allowedActions`. Both agent paths ask
it, so a narrower list narrows both:

- `ai/parsing/SecurityValidator` for the `chat` loop;
- `harness/cadet/CommandEnvironment.act` for the `agent` harness.

An empty list means no list.

`ai/parsing/SecurityValidator` does not judge a shell line itself. It asks
`security/SecurityValidator.screenCommand`, the gate `bash` runs through, and
turns a refusal into a violation that begins `Shell command refused:`. So
`git rm --cached x` passes here as it passes the gate, and the model is never
told that an ordinary command is forbidden. A line the gate cannot read, such as
a script file, is a warning here, because the approval step at execution asks
about it. A network refusal is reported once, by this screen's own network rule.

The gate reads every command on a line before it answers, and a refusal anywhere
wins over a command it cannot read. So in `./build.sh && rm -rf work` the gate
refuses the `rm`, and the line does not reach the approval step as a question
about a script.

`ui/CapturedRun.of` redacts credentials from command output. Both agent paths
collect output at that one point, so one rule covers the model, the console
echo, the debug record and a worker's transcript.

`security.requireConfirmation` decides whether a user is asked. It never decides
whether an action is allowed. Violations are the refusal.
`ai/parsing/SecurityValidator.ValidationResult` derives its own validity from
them, so no caller supplies a verdict that contradicts its evidence.

`commands/SkippedConsent` is the one place that says a change was applied with
nobody asked. `edit` and `multiedit` both call it, and it names which of three
circumstances applied:

- an agent step;
- no terminal;
- `ui.interactivePrompts` off.

A refusal in these cases would make both commands unusable for every agent run,
because `asModelDrivenWork` turns prompts off for the duration of a step.
`patch` asks nothing and announces nothing, because its caller wrote out the
change in full and the patch is refused unless the file still matches it.

`CommandRegistry.redactSensitiveArgs` masks `login` arguments before they are
logged. `ConfigCommand` redacts secrets from displayed config. `LoginCommand`
refuses to read a key at all unless a real `System.console()` can read it
without echo.

### State and configuration

State lives under `~/.cadet/`, set by `Configuration.defaultBaseDir` and
overridable with `--base-dir`. No state lives under `~/.config/cadet`.
Everything under the base directory resolves through `config.getBaseDir()`. The
one file that cannot is `config.json` itself, which must be located before it
can be read.

`ConfigManager.useConfigFile` is that one bootstrap setting, and `--config` is
its only caller. It re-points the read and the write together. A load of a named
file with a save to the default one would report a change as saved into a file
the run does not read.

Command-line flags last for the run they are given to. `Main` applies them
through `ConfigManager.applyForThisRunOnly`, which compares the configuration
before and after. It records each setting the flags changed in
`config/RunOnlySettings`. `saveConfig` writes those settings back as the file
had them, as long as they still hold the flag's value. So a run with
`--base-dir` under `/tmp` does not move the user's saved state there.

A setting the run changes after the flag, such as `models use` after `--model`,
is saved like any other change. A choice can equal the flag's own value, which
the configuration cannot show. `config set` and `models use` therefore save
through `ConfigManager.saveChoice`, which names the settings chosen.

`util/UserPath` is the single tilde expansion, applied by the path setters of
`Configuration`, so a path means the same thing whichever route it arrives by.
`Main.expandTildePath` delegates to it. Java does not expand `~` the way a shell
does, so a path built from a raw `~` string creates a real directory named `~`
in the working directory. `config/APathSettingBeginningWithATildeMeansHomeTest`
locks this.

### Changes as text

`util/UnifiedDiff` produces a unified diff from two texts with JGit's histogram
difference, which is the algorithm `git diff` uses. Nothing there needs a
repository.

`patch/PatchParse` reads a unified diff back into `PatchedFile` and
`PatchedHunk`. JGit parses patches too, but its parser wants an object database
and a checkout, and this tool edits directories that are often not repositories.
The line counts in a hunk's `@@` header decide where the hunk ends, and the
marker on each line does not. A removed line that begins `-- ` looks the same as
the `---` that opens the next file.

`patch/PatchApply` places hunks. The line number in a header is a hint: the
search starts there and widens outwards until the expected lines are found.
Every hunk is located against the original text, and the replacements are
collected as spans, so an earlier hunk that lengthens the file does not move a
later one. A file takes the whole patch or none of it.

`commands/ReadBeforeEdit` refuses a change to a file the session did not read,
for commands a model asked for. `write`, `multiedit`, `patch` and `notebookedit`
all ask it. An edit names the text to replace, and a model that did not read the
file writes that text from memory. The match then fails, and the turn is spent
on an edit that changed nothing. The same guess against `write` succeeds
instead, and replaces contents nobody saw.

A read of a file records it, and so does a write, because a run that just wrote
a file knows what is in it. A file that does not exist yet is not gated, since
its creation is a separate operation. Only model-driven commands are held to the
rule, through `ModelDispatch.isModelDriven`. A user at a terminal has the file
in front of them, and a fresh process read nothing, so the rule would refuse
every one-shot invocation.

The record is process-wide and not per run, because a run has no identity at
that depth and workers share the process. A record that is too generous never
refuses an edit that should be allowed.

`multiedit` reads edit blocks only when `EDIT_START`, `OLD:`, `NEW:`,
`REPLACE_ALL:` and `EDIT_END` each start their own line. `REPLACE_ALL:` may be
left out, which means `false`. A model gives them between `ARGS_BEGIN` and
`ARGS_END`, because an `ARGS:` line and separate tokens lose the line breaks.
Blocks that do not parse, and a model's request in plain words, are refused with
the form to use. No second model interprets them, because a second model sees
nothing of the conversation. The edits are tried on a copy before the preview,
so an edit that cannot apply fails before anybody is asked to approve it.

`commands/UnmatchedEdit` answers an edit whose OLD text is not in the file. It
names how the file differs: line endings, the spacing at the ends of lines, or
neither. It then quotes the file's own lines at the place the edit aimed at,
verbatim, for the reader to copy. A bare `String not found:` with the start of
the searched text tells the reader nothing new, and a model then guesses again.

`read` shows each line as its number, a bar (`│`) and the line exactly as it is
(`ReadCommand.numberedLine`). The bar marks where the line starts, so a model
does not count the separator as indentation when it copies text into `OLD:`.
`UnmatchedEdit` quotes the file's lines in the same form, because the transcript
indents the failure it is part of.

`patch/PatchKind` says whether a patch adds a file, edits it, or removes it. A
`/dev/null` on either side of the header decides, because that is what
`git diff` writes for a file that does not exist on that side. `PatchCommand`
works out every creation, edit and deletion before it makes any of them, so one
creation that cannot happen stops the edit beside it.

`diff` and `patch` are one interface: what the first prints, the second reads.

## Coding standards

- Java 17 language level. Use no API newer than 17. For example,
  `Thread.threadId()` (JDK 19) fails the build.
- Javadoc on public types and methods. Explain why where behaviour is
  non-obvious.
- Route errors through `ErrorHandler`. Route user-facing messages through
  `OutputFormatter` or `ThemedOutputFormatter`. Never swallow an exception
  silently.
- Validate at boundaries, and add explicit argument checks in each command's
  `execute`.
- Reach paths through `commands/ProjectFile` for reads and
  `security/WritePathPolicy` for writes, and never through a raw
  `new File(...)`. Screen shell commands through `commands/CommandGate`. All
  three rest on `security/SecurityValidator`.

## Important files

@include src/main/java/com/eonmux/cadetcoder/Main.java
@include src/main/java/com/eonmux/cadetcoder/CommandRegistry.java
@include README.md

## Development rules

- A new command needs five things: the class in `commands/`, an
  `implements CommandRegistry.Command`, a picocli `@Command(name=...)`
  annotation that names it, a `getUsage()`, and an entry in the groups of
  `HelpCommand`. Add `Callable<Integer>` plus `@Parameters` and `@Option`
  bindings only if it needs picocli parsing. The description comes from the
  annotation; do not also write a `getDescription()`.
- Advertise every command to the model in `CommandCatalog`, or record it as
  human-only in `CommandCatalogCoverageTest.WITHHELD` with the reason. The build
  fails when a command does neither. Whether the agent can call a command is a
  decision, and the default must not be "nobody noticed". A command absent from
  both does not exist as far as the model knows.
- List every option a command accepts in its `getUsage()`. `CommandUsageTest`
  enforces that.
- Anything that writes to the project asks `security/ReadOnlyGuard` first and
  validates its path with `security/WritePathPolicy`.
- A flag that means "the user already said yes", such as `--force`, counts only
  when the user typed it. `ModelDispatch.personsForce(asked)` returns true only
  for a flag the user gave, and `CommandGate` and `undo` both use it. Without
  it, a model that writes `-f` would answer the confirmation on the user's
  behalf.
- Screen any shell command with `commands/CommandGate`, which holds both the
  screen and the confirmation. A second copy of either gives one way to run a
  command a weaker check than the other.
- A process that outlives the step which started it belongs to `JobRegistry`.
  Started outside it, nothing tells the model it runs, nothing collects its
  output, and nothing stops it when the session ends.
- Registry keys come from `@Command(name=...)` when present. Otherwise they are
  the class name minus the `Command` suffix, lower-cased.
- Do not put a command outside `commands/`. The reflection scan of the registry
  covers that package alone, so a `@Command` class anywhere else is never
  discovered and does nothing.
- Do not give a command class a name with `test` in it, in any case, and do not
  make it an inner class. Discovery skips any class whose fully qualified name
  contains `$` or contains "test" in any case
  (`CommandRegistry.registerCoreCommands`). It also skips abstract classes.
- Never capture output by replacement of `System.out`. It is process-global, and
  `WorkerPool` runs up to eight agent loops at once. A save-and-restore pair
  leaves one worker to restore another's buffer and the terminal silent for the
  rest of the session. Use `ui/CapturedRun`, which collects through the
  per-thread `OutputCapture`.
- Walk the project with `util/ProjectTreeWalk.isPruned(startPath, dir)`.
  Hand-written pruning that tests `dirName.startsWith(".")` prunes the start
  directory, because `Paths.get(".").getFileName()` is `"."`. The walk then
  visits nothing and reports "not found" for every input.
- Give every settable configuration property a case in `config/ConfigOverrides`.
  A section that cannot apply a property must throw and must not fall out of its
  switch, or `cadet config` reports success over a value it discarded.
  `EverySettingIsSettableTest` drives this off the setters by reflection, so it
  covers a new property automatically.
- Put a removed setting into `config/RetiredSettings` with the reason.
  CadetCoder then tells the user the line is ignored and can be deleted, and
  does not silently read nothing.
- The section getters of `Configuration` never return null, because the setters
  substitute defaults. The file is user-edited, and a JSON `null` would
  otherwise reach every consumer as a `NullPointerException`. Read a section
  directly; do not add null checks at the call site.
- Both agentic loops parse `ACTION_START` blocks with
  `ai/parsing/ActionBlockParser`. It is the only parser that understands
  `ARGS_BEGIN` and `ARGS_END` and the per-command argument mapping, so a second
  hand-rolled scan silently loses every multi-line payload.
  `AgentPromptContractTest` and `ChatPromptContractTest` hold each prompt's own
  examples to what its parser accepts. The recovery path obeys the same rule:
  `ErrorRecoveryManager` reads a recovered block's arguments through
  `ActionBlockParser.argumentsIn`.
- Words written after the verb on the `COMMAND:` line are put in front of the
  arguments, unless the arguments already begin with them. So
  `COMMAND: job start` above arguments that begin with `start` does not run
  `start` as a job.
- A reply in one of the tool-call notations is read, never taught. The prompt
  still asks for the `ACTION_START` block, and a reply that carries both is read
  as the block. Put support for a notation in `ai/parsing/toolcalls` as a
  `ToolCallSyntax`, and never add a second place that maps a name to a command.
- `CommandRegistry.getCommands()` is a read-only view. Register and unregister
  through the methods of those names. A handout of the live map would let a
  caller that means to read add or drop a command for the whole process.

## Testing

- The suite uses JUnit 4 (through the JUnit Vintage engine) and JUnit 5, with
  AssertJ and Mockito. Run it with `mvn test`.
- Surefire (in `pom.xml`) runs the suite as follows:
  - test classes run in alphabetical order;
  - all classes share one reused fork;
  - the fork times out after 180 seconds;
  - test output goes to files (`redirectTestOutputToFile`);
  - `user.home` is set to `target/test-home`, so tests never touch the real
    `~/.cadet`.
- Anything process-wide that a test changes must be saved in `@Before` and put
  back in `@After`, because all classes share one fork. That covers `user.home`,
  `user.dir`, `Configuration.defaultBaseDir`, `System.out` and any singleton.
  Restore the saved value; never reconstruct it. A rebuilt value that differs
  sends the config tree of every later class somewhere the application never
  reads.
- Run the suite from a git checkout. `GitIntegrationTest` resolves a repository
  from the working directory and errors with `RepositoryNotFound` otherwise.
- Command tests assert on captured output through `TestOutputCapture`, and never
  only on "did not throw".
- Tests wait for conditions and never for fixed durations. `testing/Await` polls
  until the thing waited for is true. A fixed `Thread.sleep` is too long,
  because every run pays it even when the work took a millisecond. It is also
  too short on a loaded machine, and the test then fails for a reason unrelated
  to the code. Where a test asserts that something does not happen there is no
  condition to poll, so `Await.settle()` gives a short bounded pause and says
  why.
- A test whose subject is "does not block forever" carries `@Test(timeout=…)`.
  Without it, a stall test hangs and does not fail, which reports nothing while
  it stops everything.
- Test a guard apart from the action it guards. `strayPositionals` is separate
  from `execute` on both destructive commands. The only way to assert that a
  valid invocation passes the guard is to let it proceed, and that would discard
  a working tree or push to the tracked remote.
- A test that gives files distinct content must also give them distinct
  timestamps when it asserts on a time-ordered listing. Files created in a loop
  share an mtime.
- A test of a TUI region draws it. `Frame.forTesting(Buffer.empty(rect))` gives
  a frame backed by an in-memory buffer, and a read of
  `buffer.get(x, y).symbol()` says what landed on which row. Never construct
  `InteractiveShell` to reach a region, because its constructor takes over the
  process's output routing.
- Each region draws through a collaborator that takes a frame:
  `ShellConsoleRenderer`, `ShellInputLine`, `ShellHeaderBar`, `ShellStatusBar`
  and `ShellHelp`. Every decision behind one is a pure function of values:
  `ShellKeys`, `ShellPointer`, `ShellActivityLine`, `ShellWelcome` and
  `ShellWidgets`.
- `mvn verify` enforces a JaCoCo floor of 80% of lines and 67% of branches over
  the whole project. Raise it as coverage improves. Never lower it to make a
  build pass.

## Notes for the AI assistant

- The language level is JDK 17. The jar runs on 17 and newer. The shade plugin
  sets `Multi-Release: true`, so Lucene's memory-segment index provider is found
  on JDK 19 and newer.
- Run a command against the built jar before you document it or rely on it.
- Pass command arguments as separate argv entries. A single quoted string
  without a leading `/` goes to `chat`. With a leading `/`, as in `"/ls src"`,
  it is a whole command line (`InputRouter.route`).
