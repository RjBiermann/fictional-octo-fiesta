// Check for the human-only wall matcher. Run: node .pi/ci/deny-human-only.check.mjs
// Lives outside .pi/extensions so pi never tries to load it as an extension.
import assert from "node:assert/strict";
import { humanOnlyHit } from "../extensions/deny-human-only.ts";

const blocked = [
  ["gh pr merge 1610 --squash --delete-branch", "gh pr merge"],
  ["cd /workspaces/sturdy-potato/fictional-octo-fiesta && gh pr merge 1610 --squash", "gh pr merge"],
  ["cd repo && gh pr merge 1 2>&1 | tail -5", "gh pr merge"],
  ["for n in 1543 1542; do gh issue close $n --reason \"not planned\"; done", "gh issue close"],
  ["gh issue comment 1548 -F /tmp/x.md >/dev/null && gh issue close 1548", "gh issue close"],
  ["sudo git merge main", "git merge"],
  ["gh pr close 12", "gh pr close"],
  ["cd repo && gh issue edit 5 --add-label ai-fix", "gh issue edit"],
  ["gh pr merge 1\ngh pr view 1", "gh pr merge"],
  ["gh pr review 1619 --approve", "gh pr review --approve"],
  ["gh pr review --approve 1619", "gh pr review --approve"],
  ["gh pr review 5 --request-changes --body x", "gh pr review --approve"],
  ["bash -c \"cd x; gh pr merge 1\"", "gh pr merge"],
];

const allowed = [
  "gh api repos/x/y/pulls/1/merge",
  "gh pr checks 1; gh pr view 2 --json mergeStateStatus",
  "gh issue comment 3 --body \"merge approved\"",
  "echo \"gh pr merge\"",
  "gh pr view 4 | tail -3",
  "git merge-base main HEAD",
  "gh pr review 5 --comment --body looks-good",
];

for (const [cmd, want] of blocked) assert.equal(humanOnlyHit(cmd), want, `should block: ${cmd}`);
for (const cmd of allowed) assert.equal(humanOnlyHit(cmd), null, `should allow: ${cmd}`);

console.log(`ok — ${blocked.length} blocked, ${allowed.length} allowed`);
