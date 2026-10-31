# Conversation primitives

See the [adversarial verification matrix](../ominicat-providers/DEEP_VERIFICATION.md) for seeded interval, revision, evidence and snapshot checks.

`ominicat-conversation` adds bounded transcript state, configurable policies and prompt composition to the microkernel. It depends only on `ominicat-core`; it requires no network service or model credentials. State is transient and confined to one session. This module does not provide durable storage, recovery or delivery guarantees.

## Optional media references

`io.ominicat.conversation.reference` provides immutable source/producer/segment-version values independently of the configured stages. `MediaReference` distinguishes half-open ranges, points and unknown positions; its PCM adapter counts samples per channel. `FrameBoundaryMap` uses explicit, strictly increasing integer-millisecond boundaries including the final end boundary. `MediaReferenceIO` offers bounded version-1 binary import/export with full Java `long` precision and strict UTF-8.

See the [API guide and examples](../docs/media-references.md) for source/fingerprint checks, limits and failure cases. Applications supply identities, verified source metadata and mappings. These types neither edit committed transcripts nor change the default WebSocket codec or kernel dispatch.

## Configured stages

The JAR registers three `ProcessorFactory` implementations through Java `ServiceLoader`. Each factory creates fresh state for each session.

```yaml
stages:
  - type: conversation-evaluator
    options:
      speaker: agent
      policyVersion: "document-v1"
      requiredPhrases: "hello,thank you"
      prohibitedPhrases: "unsupported guarantee"
      minimumQuestionRatio: "0.2"
      maxTurns: "256"
      maxTextChars: "8192"
      evaluateOnTurnEnd: "false"
```

This stage forwards input frames unchanged. A final `Transcript` commits a turn to the retained window; partial transcripts never enter it. Deduplication uses stable `segmentId`. Repeated finals with identical speaker, interval, text and role do not add a turn; confidence or revision changes do not overwrite the committed final. Conflicting final facts for a retained segment ID are rejected. Deduplication covers retained turns only. Speaker identity and domain role remain distinct fields. Confidence `-1` means unavailable. The convenience transcript constructor derives an ID from speaker and interval; adapters handling revisions should supply explicit IDs.

Before forwarding `Control(END)`, the stage emits an `EvaluationFrame`. Setting `evaluateOnTurnEnd: "true"` also emits a current-window evaluation before each `Control(TURN_END)`. Repeated boundary frames produce repeated evaluations. A control frame itself does not close a kernel session; the caller owns `finish()` and `close()`.

Policy matching is literal, case-insensitive, speaker-specific and confined to the retained window. Commas separate configured phrases; use the Java API for phrases containing commas. Each phrase option accepts up to 64 distinct entries. The optional question-ratio rule counts turns containing `?` or `？`; it is a punctuation heuristic. Rule scores range from zero to one. The aggregate is the mean of applicable scores. A missing target speaker or empty rule list produces `score: null`, rather than a passing score. Evidence includes up to eight excerpts per rule, speaker labels and transcript intervals.

`EvaluationFrame.policyVersion` is the caller-supplied policy configuration label. `retainedTurns` and `evictedTurns` make the bounded scope explicit. Preserve the application version, configuration and input transcripts externally if evaluations must be reproduced later.

```yaml
stages:
  - type: prompt-template
    options:
      inputSpeaker: user
      role: assistant
      context: "Discuss the shared document."
      instruction: "Respond concisely and ask a relevant question."
      template: |
        Role: {{role}}
        Context: {{context}}
        Instruction: {{instruction}}
        Transcript:
        {{history}}
        Latest {{speaker}} turn: {{transcript}}
      maxTurns: "32"
      maxTextChars: "8192"
      maxPromptChars: "65536"
  - type: openai-chat
    options:
      model: "your-model"
```

`prompt-template` retains final transcripts from all speakers. Each new final transcript from `inputSpeaker` is consumed and replaced by one `Frame.Text` prompt; duplicate finals from that speaker emit nothing. Other frames, including partial transcripts and other speakers' transcripts, pass through. Placeholders are replaced in one pass, so transcript text containing `{{role}}`, dollar signs or backslashes stays literal data.

Supported variables are `role`, `context`, `instruction`, `transcript`, `speaker` and `history`. Unknown variable names fail when the stage is created. History uses `speaker: text` lines. It drops the oldest complete lines when necessary to fit `maxPromptChars`; a latest turn or static template that cannot fit causes an explicit error. `Processor.onInterrupt()` clears prompt history and deduplication state at the serialized session interruption boundary. The evaluator retains its committed transcript window across an interruption.

## Java extension points

```java
PolicyRule rule = (snapshot, targetSpeaker) -> {
    var turns = snapshot.turnsBySpeaker(targetSpeaker);
    return new RuleResult("custom-rule", RuleResult.Status.PASS, 1.0,
            "Evaluated " + turns.size() + " retained turns", List.of());
};

var stage = new ConversationEvaluatorProcessor("agent", List.of(rule), 256);
```

`PolicyRule` receives an immutable `ConversationSnapshot`, so a custom rule can use an independent rules engine or model adapter without changing the kernel. Rule exceptions propagate to the session error boundary. `EvaluationFrame` implements the open `Frame` interface; transports can serialize it without adding a kernel dependency on this module.

`ConversationEvaluatorProcessor.snapshot()` and `.restore(snapshot)` let a caller retain and restore the committed window externally. `ConversationWindow` also accepts a snapshot in its constructor. Restore validates limits, unique segment IDs and interval-derived metrics before replacing existing state; it performs no storage I/O. Replaying the same committed snapshot with the same rules and policy version produces the same built-in evaluation. Custom rules own their determinism.

`ConversationWindow` retains at most `maxTurns` final turns and rejects final text exceeding `maxTextChars` UTF-16 code units. Speaker labels may contain at most 128 characters. Default bounds are 256 turns and 8192 text characters per turn. Bounds apply to each stage independently; stage instances must belong to one session.

`ConversationMetrics` merges transcript intervals to report retained-window duration, speech interval union, interval gaps and per-speaker interval unions. Overlap is not double-counted in total speech duration. These metrics use provided transcript timestamps; they do not measure acoustic silence, identify speakers or infer intent. Arrival order is preserved in snapshots and prompts; timestamp sorting is used only for interval calculations.

## Addressable step flows

`step-flow` emits a `StepEvent` and the configured text when it enters a step. Send `START` once; repeated START is idempotent. Each new final transcript matching `inputRole` advances through the configured sequence. Partials and other roles do not advance it. Final inputs are consumed so they cannot trigger a second model request downstream.

```yaml
stages:
  - type: step-flow
    options:
      steps: "welcome,details,complete"
      definitionVersion: "v1"
      inputRole: user
      step.welcome.prompt: "What would you like to discuss?"
      step.details.prompt: "Please share one more detail."
      step.complete.prompt: "Thank you."
```

Use `step.<id>.next` for an explicit successor or an empty value for a terminal step. The Java `StepFlowProcessor.Step`/`Transition` API also supports ordered predicates and branches. Definitions are validated before use. `snapshot()` and `restore()` retain step identity, version, revision and bounded committed segment IDs. Restore rejects another definition version; applications must persist and validate the matching definition. Interrupted media does not reset committed flow progress. Deduplication covers `maxRememberedSegments` retained IDs (default 1024). Atomic storage, replay of external side effects and reconnection authorization belong to the calling application.
