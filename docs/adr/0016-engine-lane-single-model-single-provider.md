# Single model, single provider engine lane

Every gh-aw agent workflow in this pipeline runs the same model —
`openai/glm-5.3-flash` — through one provider lane: pi (`engine: pi`) over
the OpenCode Go endpoint (`https://opencode.ai/zen/go/v1`), Chat
Completions wire protocol (`api: openai-completions`), one secret
(`OPENCODE_API_KEY`), one network allowlist entry (`opencode.ai`).
`"reasoning": false` is mandatory: the GLM upstream 400s on
`reasoning_effort` (repo history #479/#493). Spend is bounded per
workflow by `max-ai-credits`; the provider cap ($60/month Go) is the
outer wall.

Why: one spend lane to meter (13-cost-tracker reads it as a single
line), one credential to guard (91-secret-guard), and one behavioral
contract — the terminal-action guarantee, budget-stamp protocol and
safe-output discipline in `shared/` assume one engine's quirks
(sandbox disabled because gh-aw's pi gateway forces Responses; threat
detection unavailable off Copilot CLI).

Decided:

- Model/provider changes are a **lane edit** (`shared/engine-lane.md`)
  plus `gh aw compile` — never per-workflow overrides.
- Any new workflow imports the lane; no workflow declares its own
  engine or model.

Consequence: a bad model day degrades every workflow at once (accepted
— uniformity is the point); swapping providers means editing one file
and recompiling.
