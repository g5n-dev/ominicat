#!/usr/bin/env bash
# Opt-in native-TCP validation; builds exclusively in an isolated source snapshot.
set -euo pipefail
task_project="$(cd "$(dirname "$0")/.." && pwd)"
task_seconds="${1:-180}"
task_clients="${2:-16}"
task_destination="${3:-$task_project/reports/server-soak}"
task_rate="${4:-100}"
mkdir -p "$task_destination"
task_destination="$(cd "$task_destination" && pwd)"
task_snapshot="$(python3 - "$task_project" <<'PY'
import io, pathlib, shutil, subprocess, sys, tarfile, tempfile
root = pathlib.Path(sys.argv[1])
snapshot = pathlib.Path(tempfile.mkdtemp(prefix="ominicat-server-soak."))
archive = subprocess.check_output(["git", "archive", "HEAD"], cwd=root)
with tarfile.open(fileobj=io.BytesIO(archive)) as source:
    source.extractall(snapshot, filter="data")
shutil.copytree(root / "ominicat-server", snapshot / "ominicat-server", dirs_exist_ok=True, ignore=shutil.ignore_patterns("target"))
print(snapshot)
PY
)"
printf 'Source snapshot: %s\n' "$task_snapshot"
printf '%s\n' "$task_snapshot" > "$task_destination/snapshot.txt"
cd "$task_snapshot"
./mvnw -B -pl ominicat-server -am test \
  -Dtest=ServerAdversarialTest,StalledProviderRecoveryTest,ServerSoakIT \
  -Dsurefire.failIfNoSpecifiedTests=false \
  "-Dominicat.soak.seconds=$task_seconds" "-Dominicat.soak.clients=$task_clients" \
  "-Dominicat.soak.connectsPerSecond=$task_rate" "-Dominicat.soak.output=$task_destination/result.json" \
  '-DargLine=-Xms128m -Xmx512m -Dio.netty.leakDetection.level=paranoid' \
  > "$task_destination/validation.log" 2>&1
printf 'Validation evidence: %s\n' "$task_destination"
