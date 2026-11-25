// Engine-level binding of guardrails.HUMAN_ONLY for pi (ADR-0009 addendum).
// Mirrors devloop v0.3.13's runtime DENY_COMMANDS — which dispatches on
// argv[0] (claude/opencode) and never binds a pi engine, so until this file
// the wall was advisory for the agent's own shell.
// Deny-only: blocks, never prompts, no network, no shell-outs.
// ponytail: literal commands only — `$()`, backticks, `bash -c`, and `gh api`
// merge paths are not covered; same ceiling as upstream. Extend if abuse shows up.
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

const DENY_COMMANDS = [
	"gh pr merge", "git merge", // merge
	"gh pr close", // close_pr
	"gh pr review --approve", // approve
	"gh issue close", // close_issue
	"gh issue edit", "gh pr edit", // apply_trigger (labels via --add-label)
];

// Flags that consume the next token, so `gh -R o/r pr merge` and `git -C . merge`
// normalise to the plain form before matching.
const FLAG_ARG = new Set(["-R", "--repo", "-C", "-c", "--hostname", "--config", "--git-dir", "--work-tree"]);
const SHELL_PREFIX = new Set(["sudo", "command", "time", "do", "then", "else", "{", "env", "nohup", "timeout"]);

function canonical(segment: string): string {
	const toks = segment.trim().split(/\s+/).filter(Boolean);
	for (let t = toks[0]; t !== undefined; t = toks[0]) {
		if (/^[A-Za-z_][A-Za-z0-9_]*=/.test(t)) toks.shift(); // FOO=bar cmd
		else if (SHELL_PREFIX.has(t)) {
			toks.shift();
			// sudo -E, timeout 30, nice -n 5: the wrapper's own argument
			if (toks.length && (/^\d+$/.test(toks[0]) || /^-[A-Za-z]$/.test(toks[0]))) toks.shift();
		} else break;
	}
	if (toks[0] === "gh" || toks[0] === "git") {
		const prog = toks.shift() as string;
		while (toks.length && toks[0].startsWith("-")) {
			const flag = toks.shift() as string;
			if (!flag.includes("=") && FLAG_ARG.has(flag)) toks.shift();
		}
		toks.unshift(prog);
	}
	return toks.join(" ");
}

// `gh pr review 12 --approve` / `=12 -a`: flags are order-independent, and -a/-r
// are the documented aliases. Approve is what arms the owner-approval receipt.
function reviewVerdict(cmd: string): string | null {
	if (!/^gh pr review\b/.test(cmd)) return null;
	return /(^|\s)(--approve|-a|--request-changes|-r)(\s|$)/.test(cmd) ? "gh pr review --approve" : null;
}

export function humanOnlyHit(command: string): string | null {
	// A quoted body is not a command: `cat > note <<'EOF' … gh pr merge 1 … EOF`
	// must not be blocked just because it mentions one.
	const bare = command.replace(/<<-?\s*(['"]?)([A-Za-z_][A-Za-z0-9_]*)\1[^\n]*\n[\s\S]*?\n\s*\2\s*(?:\n|$)/g, " ");
	for (const segment of bare.split(/&&|\|\||\||;|\n/)) {
		const cmd = canonical(segment);
		if (!cmd) continue;
		const verdict = reviewVerdict(cmd);
		if (verdict) return verdict;
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
