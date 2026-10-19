# Budget stamps (Ledger protocol — imported by agent workflows)

The attempt budget lives in the issue comment history, not in any state
store. A **budget stamp** is a comment whose body **begins** with:

```
gh-aw budget: attempt=N kind=<fix|new|remove|review|repair|spec> run=<url or id> credits=<n>
```

Rules (ported from `devloop/ledger.py`):

- **Producers only**: an agent workflow posts a stamp via `add-comment`
  whenever it completes a work attempt (build, review round, repair
  cycle) or observes a reset.
- **Reset**: a human `/retry` posts `gh-aw budget: reset` — stamped
  resets clear the attempt count for that issue.
- **Parsers only count stamped comments**: lines beginning
  `gh-aw budget:` are protocol; everything else on the timeline is
  prose that can never move an attempt budget. The sweeper's parser
  (90-sweeper.yml env) counts stamps back out of the history —
  `MAX_ATTEMPTS` 20 lifetime and `DAILY_ATTEMPT_CAP` 10 per issue per
  day read the same scan; the repair session escalates to the human at
  cycle 4 (07-fix-loop), whichever bound bites first.
- **Never edit or reformat** an existing stamp; only append.
- When posting results, always close your turn with a stamp if your
  workflow's body instructs it (attempt-bearing work only — narration
  comments carry no stamps).
