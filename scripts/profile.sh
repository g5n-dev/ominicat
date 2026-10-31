#!/usr/bin/env bash
# Reproduce JMH throughput/allocation, queue-inclusive latency, JFR, and GC evidence.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
MODE="${1:-quick}"
DESTINATION="${2:-reports/latest}"
case "$MODE" in
  quick) JMH_ARGS=(-wi 3 -i 5 -w 1s -r 1s -f 2); FRAMES=25000000 ;;
  full) JMH_ARGS=(-wi 5 -i 8 -w 2s -r 2s -f 3); FRAMES=50000000 ;;
  *) printf 'Usage: %s [quick|full] [output-directory]\n' "$0" >&2; exit 2 ;;
esac
# A failed rerun must never leave an old successful report beside a new source hash.
if [[ -e "$DESTINATION" ]] && [[ ! -d "$DESTINATION" || -n "$(ls -A "$DESTINATION")" ]]; then
  printf 'Refusing nonempty profile destination: %s\nUse a new output directory for each run.\n' "$DESTINATION" >&2
  exit 2
fi
mkdir -p "$DESTINATION"
DESTINATION="$(cd "$DESTINATION" && pwd)"
if [[ -x ./mvnw ]]; then MVN=./mvnw; else MVN=mvn; fi
python3 scripts/report.py --fingerprint "$DESTINATION"
"$MVN" -B -pl ominicat-benchmarks -am clean package -DskipTests > "$DESTINATION/build.log" 2>&1
JAR="$ROOT/ominicat-benchmarks/target/ominicat-benchmarks.jar"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
JFR_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}jfr"
JFR_STACK_DEPTH=64
"$JAVA_BIN" -jar "$JAR" "io.ominicat.benchmarks.*Benchmark.*" -lp > "$DESTINATION/jmh-plan.txt"
python3 scripts/report.py --prepare-run "$MODE" --artifact "$JAR" "$DESTINATION"
"$JAVA_BIN" -Xms512m -Xmx512m -jar "$JAR" "io.ominicat.benchmarks.*Benchmark.*" \
  "${JMH_ARGS[@]}" -foe true -t 1 -prof gc -rf json -rff "$DESTINATION/jmh.json" \
  > "$DESTINATION/jmh.log" 2>&1
"$JFR_BIN" configure --input profile.jfc --output "$DESTINATION/safe-profile.jfc" \
  'jdk.InitialEnvironmentVariable#enabled=false' 'jdk.InitialSystemProperty#enabled=false' \
  'jdk.InitialSecurityProperty#enabled=false' 'jdk.SecurityPropertyModification#enabled=false' \
  'jdk.SystemProcess#enabled=false' 'jdk.ProcessStart#enabled=false' \
  'jdk.OSInformation#enabled=false' 'jdk.JVMInformation#enabled=false' \
  > "$DESTINATION/jfr-config.log" 2>&1
"$JAVA_BIN" -Xms512m -Xmx512m \
  "-XX:FlightRecorderOptions=stackdepth=$JFR_STACK_DEPTH" \
  "-XX:StartFlightRecording=filename=$DESTINATION/kernel.jfr,settings=$DESTINATION/safe-profile.jfc,dumponexit=true" \
  "-Xlog:gc*:file=$DESTINATION/gc.log:uptime,level,tags" \
  -cp "$JAR" io.ominicat.benchmarks.CoreLoad \
  --sessions 16 --frames "$FRAMES" --warmup 100000 --inflight 32 --stages 4 \
  --output "$DESTINATION/load.json" > "$DESTINATION/load.log" 2>&1
"$JFR_BIN" summary "$DESTINATION/kernel.jfr" > "$DESTINATION/jfr-summary.txt"
"$JFR_BIN" print --json --stack-depth "$JFR_STACK_DEPTH" --events jdk.ExecutionSample,jdk.ObjectAllocationSample,jdk.GarbageCollection,jdk.JavaMonitorEnter,jdk.VirtualThreadPinned \
  "$DESTINATION/kernel.jfr" > "$DESTINATION/jfr-events.json"
python3 scripts/report.py --strict --check-source --artifact "$JAR" "$DESTINATION"
python3 scripts/summarize_profiles.py "$DESTINATION"
mkdir -p "$ROOT/reports/local/$(basename "$DESTINATION")"
LOCAL_EVENTS_DESTINATION="$ROOT/reports/local/$(basename "$DESTINATION")/jfr-events.json"
if [[ ! "$DESTINATION/jfr-events.json" -ef "$LOCAL_EVENTS_DESTINATION" ]]; then
  mv "$DESTINATION/jfr-events.json" "$LOCAL_EVENTS_DESTINATION"
fi
printf 'Performance evidence saved to %s/report.md\n' "$DESTINATION"
