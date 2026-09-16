# Probe instruments may exceed provider capability; verification may not

Site probing sometimes needs instruments the shipped provider could never use:
TLS-impersonated curl (film1k's Cloudflare challenge — FINDINGS-408), a headless
browser to read rendered DOM, watch the player's network log, or see which cookie an
age-gate click sets. That asymmetry is fine in FINDINGS because FINDINGS only needs
ground truth — and every browser finding is a **Lead** that must be re-proven with
plain curl before entering FINDINGS, because the provider only ever gets raw HTTP.
It is *forbidden* in verify-provider: verification must mirror the app's plain-HTTP
runtime (NiceHttp, no JS). A browser-passing verification would prove something no
provider can reproduce in-app.

Considered alternative: also verify through the browser so challenge-walled sites
could ship "verified" — rejected as a lie by construction; those sites stay Blocked
with a recorded reason instead.
