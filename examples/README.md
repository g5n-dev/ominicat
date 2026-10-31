# Embedded replay

Build the distribution and run a deterministic local transcript replay:

```bash
./mvnw package
java --class-path ominicat-server/target/ominicat-server.jar \
  examples/Replay.java examples/transcripts.jsonl reports/local/replay-checkpoint.json
```

Run it again with the same checkpoint to restore the committed conversation window and resume at the next input line. The input digest and policy version must match. The example emits the retained-window evaluation at END; timestamps and IDs are deterministic. A new checkpoint path starts a fresh replay.

This is a transcript driver, not a long-audio decoder or distributed job scheduler. It streams JSONL input, writes a local atomic checkpoint after each accepted line and demonstrates the snapshot contract. The example's input should be trusted local data. It does not atomically commit downstream effects with checkpoints or deduplicate a repeated END evaluation; a durable outlet must use application-owned idempotency and acknowledgment.

For audio replay, a driver supplies PCM chunks with stable `streamId`/`startSample`, groups bounded utterances, and records its file/sample cursor in the same external checkpoint. Provider limits still apply; no entire recording is accumulated in the kernel.
