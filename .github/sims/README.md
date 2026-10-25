# Pipeline sims

Mechanical, offline assertions on the agent-pipeline **prompts** — the
parts of the lane that no provider unit test can reach (this repo has no
prompt-behavior harness; JUnit covers provider `Parse` functions only).

| Sim | Guards against | Parent spec |
| --- | --- | --- |
| `cross_contamination_sim.py` | #787's leak shape: a repair dispatched for story A pushing story B's deliverables (or pushing into B's PR) | #796 / story #894 |

Each sim is stdlib-only Python, runs in seconds, needs no network and no AI
step. `load-check.yml` runs them on its daily schedule (ADR-0012 lane); they
also run standalone:

```bash
python3 .github/sims/cross_contamination_sim.py       # exit 0 = pass
python3 .github/sims/cross_contamination_sim.py -v    # per-case evidence
python3 .github/sims/cross_contamination_sim.py --root /path/to/tree   # mutant check
```

## How the cross-contamination sim works

Two halves, both reading the real prompt text:

1. **Prompt assertion** — the `## Rule 0 — pre-push identity guard` block
   must exist in *both* `07-fix-loop.md` and `14-rebase.md`, carry the
   derivation inputs / two assertions / abort-stamp language, and be
   worded identically (whitespace-insensitive, so re-wrapping is fine).
   `14-rebase.md` rule 2 must keep the three-way merge classification —
   test files keep-BOTH, agent-state markdown merge-on-content with no
   wholesale sibling import — and `07-fix-loop.md` must keep its
   story-scope discipline. Delete or weaken any of it and the sim fails
   before a single case runs.

2. **Dispatch sim** — `fixtures/contamination-787.json` replays the
   recorded #787 evidence (real story numbers, branch names, commit and
   file paths). Each case is executed twice through a scripted decision
   procedure that mirrors the prompt:

   - `baseline` — the pre-guard pipeline (no identity guard, keep-BOTH /
     wholesale import). It must reproduce the leak; that is the proof the
     detector has teeth.
   - `guarded` — the pipeline derived from the current prompt text. Each
     case must end `abort` (no push, one evidence-carrying stamp on the
     owner issue, no state-label churn) or `clean` (a push containing only
     story A's deliverables, with a sibling note never imported into
     `FINDINGS-767.md`).

   A **vacuity control** re-runs the guarded cases with the identity guard
   disabled and fails if no case flips to `leak` — an always-abort
   detector can't pass for the wrong reason.

**Scope of the claim:** the sim asserts that the prompt's rule set is present,
complete and consistently worded, and that its *stated* decision procedure
turns the recorded leak shape into abort-or-scoped-push. It cannot prove an
LLM obeys the prompt; its failure mode is a deleted or weakened rule, which is
exactly the regression class (a repair round that drops the guard text).

Adding a case = adding an entry to the fixture's `cases` array; the sim
classifies outcomes (`abort` / `leak` / `clean`) against the declared
`expect_baseline` / `expect_guarded` values.
