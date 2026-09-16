// Engine-level binding of guardrails.HUMAN_ONLY for pi (ADR-0009 addendum).
// Mirrors devloop v0.3.13's runtime DENY_COMMANDS — which dispatches on
// argv[0] (claude/opencode) and never binds a pi engine, so until this file
// the wall was advisory for the agent's own shell.
// Deny-only: blocks, never prompts, no network, no shell-outs.
// ponytail: prefix match only — `gh api`-based merge/approve/close paths are
// not covered; same ceiling as upstream. Extend if abuse shows up.
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

const DENY_COMMANDS = [
	"gh pr merge", "git merge", // merge
	"gh pr review --approve", // approve
	"gh issue close", // close_issue
	"gh issue edit", "gh pr edit", // apply_trigger (labels via --add-label)
];

export default function (pi: ExtensionAPI) {
	pi.on("tool_call", async (event, ctx) => {
		if (event.toolName !== "bash") return undefined;
		const command = String(event.input.command ?? "").trim();
		const hit = DENY_COMMANDS.find((c) => command === c || command.startsWith(c + " "));
		if (hit) {
			return {
				block: true,
				reason: `Human-only command blocked (guardrails.HUMAN_ONLY): "${hit}". Agents never merge, approve, close, or edit issues/PRs — see AGENTS.md.`,
			};
		}
		return undefined;
	});
}
