# The registry never travels in an agent diff

Two identical stranded deliveries (Film1k trigger #707, runs 39000844973
and 39123797220) concluded success while the PR never existed: the run's
`audits/findings.json` edit died during gh-aw's signed-commit rebase onto
moved main (`CONFLICT (content)` — the append-only registry conflict
class), and the work parked in a fallback request-review issue. The
registry edit in the agent diff was the only thing that failed, and the
run's own state transition (`planned → in-review`) still fired — so the
sweeper re-fired a delivery that could never land.

Decided: **agent diffs carry no registry.** `audits/findings.json` is
conveyor-owned. An Agent run that produced a registry update posts it as
an `add-comment` starting with `<!-- findings-registry-entry -->`
followed by a fenced ```json``` object; the orchestrator's
`registry_sync` step (ADR-0008 non-AI machinery, owner actor) unions it
into the registry on main within its normal sweep and confirms with a
dedupe marker comment. Per-issue `FINDINGS-*.md` files stay allowed —
they are uniquely named and have no conflict class.

Considered and rejected:
- **Union-merge driver for `findings.json` in `.gitattributes`**: one
  line, but git's union strategy concatenates both sides of the conflicted
  region — safe for append-per-line files, corrupting for JSON (braces and
  dueling scalars don't concatenate). The orchestrator already owns one
  JSON-aware union semantic (`_union_findings`); a second, dumber one for
  the same file is a trap.
- **Letting implement runs keep pushing the registry and relying on
  14-rebase**: the failure is at the run's own push, before any PR exists
  for conflict_watch to see — the rebase lane never gets a chance.

Consequences:
- Registry updates land on main ≤ one orchestrator sweep (10 min) after
  the verdict comment, not atomically with the PR.
- A later anchored entry on an already-applied issue is skipped until the
  dedupe marker is cleared (union makes the common retry a no-op anyway).
- `add-comment` budget in 05-implement grows to 3 slots (stamp +
  narration + registry entry).
