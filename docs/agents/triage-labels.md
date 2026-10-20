# Triage Labels

Canonical issue-label vocabulary. Skills speak in these exact strings.

> Historical (pre-devloop): `ready-for-agent` fired the old Task workflow —
> it is currently inert; `ready-for-human` unchanged. See AGENTS.md.

| Label              | Meaning                                  |
| ------------------ | ---------------------------------------- |
| `needs-triage`     | Maintainer needs to evaluate this issue  |
| `needs-info`       | Waiting on reporter for more information |
| `ready-for-agent`  | Fully specified, ready for an AFK agent  |
| `ready-for-human`  | Requires human implementation            |
| `wontfix`          | Will not be actioned                     |

Trigger labels (`ai-fix`, `ai-new-site`, `ai-remove-site`) are maintainer-only —
see AGENTS.md. Since ADR-0018 the 95-orchestrator machinery auto-applies
`ai-fix` to `status/queued` issues; `triage/from-agent` findings still wait
for a human-applied label.
