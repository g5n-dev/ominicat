# Contributing

Use Java 21+ and the checked-in Maven wrapper. Run `./mvnw -Pcoverage,sbom verify` before submitting changes. The default test suite runs entirely against local fixtures and does not require paid provider access.

Keep the kernel free of production dependencies and provider/application policy. Each stateful processor belongs to one session. Test lifecycle, overload, cancellation and failure paths when changing runtime contracts. New providers need protocol fixtures and explicit capability/limit documentation.

Use `scripts/profile.sh quick reports/local/change` when a change affects scheduling or allocation. Compare on the same JVM and machine; include uncertainty and workload details. Never use synthetic kernel throughput as an end-to-end model latency claim. Do not commit credentials, transcripts, recordings, heap dumps or raw recordings of a real application.

Changes to wire fields should preserve documented defaults and include round-trip tests. New configuration options should fail early on invalid values. Public claims must match runnable code and reproducible evidence.

The `main` branch is protected, including for administrators. Submit changes through a pull request; keep the branch up to date and resolve review conversations before merging. The required GitHub Actions checks are `verify (21)`, `verify (25)`, `source-hygiene`, and `container`. They run for every pull request to `main`, including documentation changes. Merge with squash or rebase to preserve linear history; force pushes and branch deletion are disabled.
