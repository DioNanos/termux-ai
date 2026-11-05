# tests/compat — contract harness

What lives here:

* `MATRIX.md` — the contract matrix: 14 upstream methods × 3 observable
  entries (native `termux-ai`, libexec `termux-api <Method>`, app aliases
  `termux-*`) plus wrapper ownership, each cell with stdout/stderr/exit/
  options·stdin/byte-preservation, a motivated PASS/FAIL/N-A baseline and
  its source (measured on the shim+stub, or read from source with an
  anchor).
* `UPSTREAM_PIN.md` + `fixtures/upstream-pin/` — the upstream wrapper
  scripts vendored byte-identically at the pinned commits; they are the
  contract source for the compat profile.
* `GOLDEN_CONTRACT.md` — the normative schema of every golden: pin anchors,
  represented branches, mandatory keys/types/casing and the serialization
  decisions (2-space indent, trailing newline).
* `fixtures/golden/` — synthetic goldens only. `native/` holds envelope
  samples (also used by the stub as canned bridge answers), `compat/` holds
  the naked upstream target payloads. Nothing here is real user data.
* `harness/termux-api-compat.test.sh` — the executable matrix measurement
  (bash 4+, python3; no Android, no Gradle).
* `harness/mutation_selftest.sh` — the negative counter-proof: mutates a
  sandbox copy of the asset (unexpected exit, unexpected stderr, empty
  stdout, malformed JSON, wrong payload, command never executed) and
  asserts each mutation turns the harness red while the control stays
  green. The repository tree is never touched.
* `harness/run_all.sh` — sandboxed entry point (private HOME/TMPDIR on
  disk) running the harness and the mutation selftest.

## Running

The harness executes the shipped CLI asset with a stub `termux-am` on PATH,
so nothing touches a phone, the app, or `$PREFIX`. Run it sandboxed as the
The contract requires:

```sh
cd <repo-root>
systemd-run --user --scope -p MemoryMax=2G \
  bash tests/compat/harness/run_all.sh
```

`run_all.sh` creates a private `HOME` and `TMPDIR` under `/tmp` (on disk),
runs `termux-api-compat.test.sh` and then `mutation_selftest.sh` inside
them, and exits nonzero if the unmutated harness fails or if any mutation
unexpectedly stays green.

Every case asserts expected stdout, stderr **and** exit code: a
characterization case (`[baseline-defect]`) accepts only the exact measured
defect it documents — e.g. "cmd is `-e` and rc is 1" — never "any
failure". Exit code 0 therefore means "the whole measured behaviour matches
the matrix, deviations included and bounded"; the mutation selftest is the
proof that an uncharacterized deviation cannot pass.

The JVM side lives in
`app/src/test/java/com/termux/app/terminal/ai/AiCompatContractTest.java`
and is **written, not executed, locally** — no Gradle or Android builds run
on this host; CI owns execution.
