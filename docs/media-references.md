<!-- ominicat-language-nav -->
[简体中文](zh-CN/media-references.md) · **English**
<!-- /ominicat-language-nav -->

# Immutable media references

`ominicat-conversation` provides an optional Java 17 value contract for importing and exporting source-bound segment versions. It is usable independently of the conversation window, rule evaluator and transport. The kernel has no dependency on these types.

## Bind a PCM range

```java
import io.ominicat.core.Frame;
import io.ominicat.conversation.reference.MediaReference;
import java.nio.ByteBuffer;

var source = new MediaReference.Source(
        "example", "asset-42", "v1", "left", "asset-start",
        MediaReference.Unit.PCM_SAMPLES, 16_000, "");
var audio = new Frame.Audio(ByteBuffer.allocate(640), 16_000, 2, "left", 800);
var reference = MediaReference.fromPcm16(source, "producer/run-A", "segment-1", 0, audio);

// 640 bytes / (2 bytes * 2 channels) = 160 samples per channel.
assert reference.position().start() == 800;
assert reference.position().end() == 960;
assert reference.durationMillisExact() == 10;
```

The adapter checks the track and sample rate, leaves the original audio payload unchanged and retains no media bytes. A legacy audio frame with `startSample == -1` produces an unknown position. It never invents a cumulative cursor or changes the original frame.

Sources distinguish namespace, asset, version, track, coordinate origin and unit. Producer scope additionally distinguishes independently allocated segment IDs: two runs can both produce `segment-1` revision 0 without colliding. Applications supply scopes; the contract provides no global identity allocator or access control.

Positions are explicit:

| Kind | Meaning |
| --- | --- |
| `RANGE` | Nonempty `[start, end)` in the source unit |
| `POINT` | One known coordinate, with equal start and end |
| `UNKNOWN` | No known coordinate; both values are `-1` |

`requireSeekStart(resolvedSource)` returns a known sample or millisecond position after checking the caller-resolved source binding. A declared SHA-256 must match; a missing resolved fingerprint cannot satisfy it. An empty fingerprint explicitly makes no content-integrity claim. Obtaining and verifying metadata, opening media and seeking remain adapter responsibilities.

`durationMillisExact()` calculates a range duration without converting clock origins. It rejects unknown/point positions, frame indices, overflow and sub-millisecond rounding. The sample rate counts samples per channel.

## Map nonuniform frame boundaries

```java
import io.ominicat.conversation.reference.FrameBoundaryMap;

var frames = new MediaReference.Source("example", "video-1", "v1", "video",
        "frame-boundaries", MediaReference.Unit.FRAME_INDEX, 0, "");
var millis = new MediaReference.Source("example", "video-1", "v1", "video",
        "asset-start", MediaReference.Unit.MEDIA_MILLISECONDS, 0, "");
var mapping = new FrameBoundaryMap(frames, millis, 0, new long[]{0, 40, 100, 180});
var secondFrame = mapping.mapInclusiveRange("producer/run-A", "segment-2", 0, 1, 1);
assert secondFrame.position().equals(MediaReference.Position.range(40, 100));
```

The last entry supplies the final frame's end boundary. Missing boundaries, mismatched source bindings and out-of-range indices fail. `firstFrame` supports a bounded part of a larger source. The mapping owns a defensive copy of at most 65,537 boundaries (65,536 intervals).

This mapping profile supports one asset/version/track and strictly increasing, nonnegative **integer millisecond** boundaries. Sub-millisecond or coincident boundaries need another adapter representation. No average-frame-rate conversion, alignment discovery, cross-source clock mapping or decoder is supplied. For starts at 0/40/100 ms, frame 1 starts at 40 ms; average 20 fps would incorrectly predict 50 ms. A frame-index reference cannot be passed directly to `requireSeekStart`.

## Import and export without media payloads

```java
import io.ominicat.conversation.reference.MediaReferenceIO;
import java.io.*;

var bytes = new ByteArrayOutputStream();
MediaReferenceIO.write(reference, new DataOutputStream(bytes));
var restored = MediaReferenceIO.read(
        new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
assert restored.equals(reference);
```

`MediaReferenceIO` is an explicit version-1 binary adapter (`OMRF` magic), separate from the default WebSocket/JSON protocol. It retains full Java `long` precision, including values above 2^53. It reads one record at a time, allowing callers to frame multiple records. Unknown versions/units/kinds, malformed UTF-8, truncated records and invalid values fail explicitly.

Namespace is limited to 128 UTF-16 code units; source ID/version/track/time basis and producer scope/segment ID are limited to 256 each. Identifiers preserve case, spaces and separators, while rejecting blank strings, ISO control characters and unpaired surrogates. PCM source sample rates are 1..384,000 Hz; other units require `sampleRate == 0`. The optional fingerprint is empty or 64 hexadecimal characters. Each of the eight encoded strings has a 1,024-byte UTF-8 limit, checked before allocating its input buffer; `MAX_RECORD_BYTES` bounds the complete record.

Callers own stream lifetime, framing, I/O timeouts and commit policy. The adapter neither closes nor flushes a stream. A failed write can leave a partial record; no transactional or durable-publication guarantee is implied. Imported identifiers are reference data, never paths, URLs or authorization decisions.

Reuse immutable source bindings and boundary maps within a run. The API computes no media fingerprint and performs no source lookup; applications can supply that metadata at import boundaries. Kernel dispatch does not perform reference validation or serialization automatically.

The [value and mapping tests](../ominicat-conversation/src/test/java/io/ominicat/conversation/reference/MediaReferenceTest.java) and [import/export fixture](../ominicat-conversation/src/test/java/io/ominicat/conversation/reference/MediaReferenceIOTest.java) cover these positive and negative boundaries. Segment editing, annotations and dependency projections remain separate [planned profiles](temporal-annotations.md). Optional reference construction, mapping and serialization have their own costs; the recorded numeric-frame kernel benchmark does not measure them.
