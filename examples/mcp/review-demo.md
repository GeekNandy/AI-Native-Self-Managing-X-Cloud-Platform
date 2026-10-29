# MCP agent demonstration prompt

Replace `<repository-id>` with the managed repository selected for this demonstration. Create **Discount calculation** in the workbench first. Use the proposal-only agent credential for this session.

> Use the x-cloud-workbench MCP server. List its managed repositories and inspect repository `<repository-id>`. Record its starting Git head. Start a repair with provider `mutation-search`, at most eight attempts and a 90-second budget. Ask it to fix the discount calculation while preserving negative-input rejection and leaving tests unchanged. Poll `inspect_repair` until it reaches a terminal state. Explain the failing baseline, passing verification, source diff and tool trace using the returned evidence. Verify that the repository head is still unchanged and the patch is awaiting review. Stop there; I will inspect and approve it in the workbench UI.

This prompt deliberately selects deterministic reference search. It demonstrates MCP interoperability, real isolated test execution and the operator review boundary. To evaluate a live model, select an actually configured provider with at most three attempts and label its evidence separately.

After the agent stops, open **Repository agent**, select the returned run ID, inspect **FAIL → PASS**, the source diff, unchanged tests and trace. Click **Review & commit patch**; the final commit control remains disabled until the review checkbox is checked. Approve explicitly, then ask the MCP client to inspect the same run and report its approved commit. The client receives the result but has no approval tool.
