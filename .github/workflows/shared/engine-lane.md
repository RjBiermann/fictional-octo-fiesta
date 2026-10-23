---
# ADOPTER-OWNED FILE — this is the one fragment every adopting repo
# writes for itself (engine, model, secret lane, network). The pilot's
# lane (below) is one example, not the default. Sources in this pipeline
# never hardcode an engine; they import this file relatively.
#
# deepseek-v4.1-flash on OpenCode Go speaks ONLY Chat Completions; gh-aw
# v0.89.21's sandboxed pi gateway forces Responses for the openai/ lane
# (confirmed by runtime 400 ModelProtocolUnsupported). Per gh-aw's own
# documented escape hatch, the agent sandbox is disabled for this lane
# (MCP gateway stays on) with a explicit per-workflow strict opt-out;
# every agent-level guardrail outside the firewall remains: read-only
# agent job, safe-output allow-lists, human-only labels/merge, on.roles.
engine:
  id: pi
  version: "1.4.0"
  model: openai/deepseek-v4.1-flash
  env:
    # Validation-step alias: GenerateMultiSecretValidationStep validates
    # the user-supplied secret when engine.env overrides a credential key.
    CODEX_API_KEY: ${{ secrets.OPENCODE_API_KEY }}
    OPENAI_API_KEY: ${{ secrets.OPENCODE_API_KEY }}
pre-agent-steps:
  - name: Ship pi models.json (OpenCode Go lane - deepseek-v4.1-flash, native)
    run: |
      DIR="${PI_CODING_AGENT_DIR:-$HOME/.pi/agent}"
      mkdir -p "$DIR"
      cat > "$DIR/models.json" <<'JSON'
      {
        "providers": {
          "openai": {
            "baseUrl": "https://opencode.ai/zen/go/v1",
            "api": "openai-completions",
            "apiKey": "$OPENCODE_API_KEY",
            "models": [
              {
                "id": "deepseek-v4.1-flash",
                "name": "DeepSeek-V4.1-Flash (OpenCode Go)",
                "reasoning": false,
                "input": ["text"],
                "contextWindow": 1000000,
                "maxTokens": 12000
              }
            ]
          }
        }
      }
      JSON
env:
  OPENCODE_API_KEY: ${{ secrets.OPENCODE_API_KEY }}
  # Native lane has no AWF gh-proxy sidecar: the agent's gh CLI reads GitHub
  # as this workflow token, scoped by each workflow's own permissions block
  # (issues/pull-requests/contents read in this pipeline's workflows).
  GH_TOKEN: ${{ github.token }}
network:
  allowed:
    - defaults
    - opencode.ai
---

# Engine lane: pi + OpenCode Go (ADR-0016 — single model, single provider)

Model: **`openai/deepseek-v4.1-flash`** for every workflow in this pipeline —
the `openai/` prefix routes through gh-aw's credential-isolated gateway
as the OpenAI-compatible backend while `OPENAI_BASE_URL` re-targets it to
`https://opencode.ai/zen/go/v1` (Go). The pre-agent-step `models.json`
declares the **Chat Completions** wire protocol (`api: openai-completions`) — Go's
deepseek-v4.1-flash surface — plus input/context/output limits and
**`"reasoning": false`**. That flag is a deliberate **off** on a model
that does support reasoning: Go's acceptance of `reasoning_effort` for
this id is unverified, and the GLM upstream 400'd on it (the adopting
repo's own #479/#493 history). Raise it as its own canary experiment,
never bundled with a model swap. **Measured** (canary 39994868917): no
effort field went over the wire, and the stream still carried
`type:"thinking"` blocks — the flag governs request-side effort control,
not the model's internal reasoning, so it is **not** a thinking-cost lever.
Spend stays on `max-ai-credits`.

Authentication: one secret, **`OPENAI_API_KEY`** (same OpenCode key as
the repo's `OPENCODE_API_KEY` — only the binding name follows gh-aw's
provider contract). `network.allowed` includes `opencode.ai`. Pi is a
**validated Go client** (session headers, current builds; hence the
version pin) — no custom header work needed here. Metering:
deepseek-v4.1-flash is $0.15/$0.60 per M (in/out — glm-5.3-flash was
$0.15/$0.50), and OpenCode Go caps **per model** ($60/month Go), so a
model swap opens a fresh meter. Per-workflow `max-ai-credits` stays the
gh-aw-side ceiling.

Pi is pinned at **`1.4.0`**. v0.89.21's own docs advertise 1.3.0 as the
tested ceiling and version-gate some `engine.config` features, so the
first canary run is the real compatibility verdict: on an argv or
streaming-log failure the fallback rung is `1.3.0` — not `1.1.0`.

History: the original draft of this lane used pi's own `models.json` +
pre-agent-step writing, then `model: opencode-go/...` with
`model-provider: openai` — both proved unsupported on v0.89.21 (the
latter silently routed pi to the Copilot gateway and made the shipped
locks demand a nonexistent `COPILOT_GITHUB_TOKEN`). The recognized-prefix
form above is what the compiler's own resolution code accepts; treat
"model segments not in the allowlist" as a compile-day tripwire.

### Terminal action guarantee (delta 12 — applies to EVERY run on this lane)

The `safeoutputs` CLI is on PATH; named-tool *intent* is not enough. Before
finishing, run the write through the CLI from bash — concretely:

```bash
# state-transition workflows (queue/review/fix stages):
safeoutputs add_labels '{"labels":[{"name":"<correct next state>"}]}'
# verdict / narration workflows:
safeoutputs add_comment '{"body":"..."}'
# nothing to write? call noop instead of exiting silently:
safeoutputs noop '{"message":"no action needed"}'
```

Do NOT call `gh` with write verbs even if GH_TOKEN is present — `gh label add`
403s under this token's read-only scope and the silent failure *also* leaves
outputs.jsonl empty; only the safeoutputs CLI records real writes.

**Payload exactly once** (delta 15, after f-o-f #613): pass the payload
through ONE channel — a single quoted inline argument *or* stdin, never
both. When stdin AND `--body` are both given, the inline placeholder wins
and the real payload (heredoc) is discarded — the run burns its entire
`add_comment` budget on a one-word placeholder and the composed content
never lands. Before invoking, check the command contains no stray
placeholder (`sentinel`, `TODO`, `"..."`), then write the real body.
After any malformed call, immediately re-run with the corrected args —
a malformed call does not consume tool-invocation budget, only a
*processed* output does.
### Terminal-action guarantee (delta 13): wrap up before the wall.

gh-aw's steering extension can crash under pi 1.x (`reading 'steer'`;
observed on 1.1.0) —
when that happens the "5 minutes left / 2 minutes left" warnings never
surface, and long audit sweeps die with **zero safe-outputs**. Contract:
keep partial results in `audits/` / `FINDINGS-` writes current enough that
a wall-terminate still leaves a usable tree (per-issue files only — never
`audits/findings.json`, the conveyor-owned registry, ADR-0013);
opportunistic
`safeoutputs add_comment` at each phase boundary (calibration; sweep;
write-up) is the preferred partial-result carrier.
