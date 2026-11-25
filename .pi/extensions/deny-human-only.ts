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
	"gh pr close", // close_pr
	"gh pr review --approve", // approve
	"gh issue close", // close_issue
	"gh issue edit", "gh pr edit", // apply_trigger (labels via --add-label)
];

// Match each shell segment, not the whole command: `cd repo && gh pr merge` was
// slipping past the prefix test, which is how agents always invoke it.
// ponytail: no $()/backtick inspection — extend if abuse shows up.
// `gh pr review 12 --approve` puts the number before the flag, so the literal
// prefix test misses it; flags are order-independent in gh.
const REVIEW_APPROVE = /^gh pr review\b.*\s--(?:approve|request-changes)\b/;

export function humanOnlyHit(command: string): string | null {
	for (const segment of command.split(/&&|\|\||;|\n/)) {
		const cmd = segment
			.trim()
			.replace(/^sudo\s+/, "")
			.replace(/^(?:do|then|else|\{)\s+/, "");
		if (REVIEW_APPROVE.test(cmd)) return "gh pr review --approve";
		const hit = DENY_COMMANDS.find((c) => cmd === c || cmd.startsWith(c + " "));
		if (hit) return hit;
	}
	return null;
}

export default function (pi: ExtensionAPI) {
	pi.on("tool_call", async (event, ctx) => {
		if (event.toolName !== "bash") return undefined;
		const hit = humanOnlyHit(String(event.input.command ?? ""));
		if (hit) {
			return {
				block: true,
				reason: `Human-only command blocked (guardrails.HUMAN_ONLY): "${hit}". Agents never merge, approve, close, or edit issues/PRs — see AGENTS.md.`,
			};
		}
		return undefined;
	});
}
