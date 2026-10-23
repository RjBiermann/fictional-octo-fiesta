# Single model, single provider engine lane

Every gh-aw agent workflow in this pipeline runs the same model —
`openai/deepseek-v4.1-flash` — through one provider lane: pi (`engine: pi`)
over the OpenCode Go endpoint (`https://opencode.ai/zen/go/v1`), Chat
Completions wire protocol (`api: openai-completions`), one secret
(`OPENCODE_API_KEY`), one network allowlist entry (`opencode.ai`).
`"reasoning": false` is deliberate on a model that does support reasoning:
Go's acceptance of `reasoning_effort` for this id is unverified, and the
GLM upstream 400'd on it (repo history #479/#493). Spend is bounded per
workflow by `max-ai-credits`; the provider cap ($60/month Go) is the
outer wall and is **per model**, so a model swap opens a fresh meter.

Supersedes the initial `glm-5.3-flash` lane (2026-10-23): deepseek-v4.1-flash
is the same $0.15/M input at a 1M context window (vs GLM's 128K declared),
which cuts the re-sent-context cost that dominated the long audit sweeps;
output rises $0.50/M → $0.60/M. Pi moved 1.1.0 → 1.4.0 at the same time —
v0.89.21's docs advertise 1.3.0 as tested, so the first canary run is the
compatibility verdict and `1.3.0` is the fallback rung, not `1.1.0`.

Why: one spend lane to meter (13-cost-tracker reads it as a single
line), one credential to guard (91-secret-guard), and one behavioral
contract — the terminal-action guarantee, budget-stamp protocol and
safe-output discipline in `shared/` assume one engine's quirks
(sandbox disabled because gh-aw's pi gateway forces Responses; threat
detection unavailable off Copilot CLI).

Decided:

- Model/provider changes are a **lane edit** (`shared/engine-lane.md`)
  plus `gh aw compile` — never per-workflow overrides. Compile with the
  gh-aw version stamped in the locks (`v0.89.21`); a compile at another
  version rewrites every lock and buries the real diff.
- Model swap and engine-version bump are validated by **one canary run**
  (13-cost-tracker: cheap, side-effect-free) before the conveyor resumes;
  do not flip `reasoning` in the same change as either. The canary must
  show **no `reasoning_effort`** in `pi-streaming.jsonl`: gh-aw's `/reflect`
  capability discovery can enable effort *despite* `reasoning: false`, and
  if it does, that flag is cosmetic and the paragraph above is wrong.
  Verified on 39994868917: no effort field on the wire — but the model
  still emits internal thinking, so the flag is a protocol-compat switch,
  never a spend control. Thinking cost is bounded by `max-ai-credits`
  alone.
- Any new workflow imports the lane; no workflow declares its own
  engine or model. `engine.config` is **not** an alternative carrier on
  v0.89.21 (probed: accepted and silently dropped) — the pre-agent
  `models.json` step is the only place model metadata reaches pi.

Consequence: a bad model day degrades every workflow at once (accepted
— uniformity is the point); swapping providers means editing one file
and recompiling. **Family independence is dropped**, not lost: Reviewer
and Builder now run the same model, so review is no longer cross-family
(the rule CONTEXT.md records for the removed Model chain). Restoring it
means giving `06-review`/`06b` their own model, i.e. amending this ADR
first — not a per-workflow override.
