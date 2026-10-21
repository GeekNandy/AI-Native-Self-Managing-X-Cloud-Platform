# Hosted demonstration

Deploy the repository's **feature** branch as one Docker web service. The root Dockerfile compiles Java 21, installs Git in a separate non-root runtime, binds to `0.0.0.0:$PORT`, and requires username/password access before serving the UI, configuration, API, metrics or MCP. `/actuator/health` is public liveness and contains no details.

Configure runtime environment variables privately in Render:

| Variable | Value |
| --- | --- |
| `DEMO_USERNAME` | Your chosen demonstration username |
| `DEMO_PASSWORD` | A unique random password, at least 16 characters |
| `PUBLIC_ORIGIN` | Exact HTTPS app origin; defaults to Render's documented `RENDER_EXTERNAL_URL` |
| `DEMO_AUTH_REQUIRED` | `true` (also set by the image) |
| `PLATFORM_AI_PROVIDER` | `nebius` for live inference, or `fixture` for labelled offline examples |
| `NEBIUS_API_KEY` | Your private Token Factory credential, required for `nebius` |
| `NEBIUS_MODEL` | `nvidia/Nemotron-3.5-Super-VL` initially; verify access before changing |
| `PLATFORM_RUNTIME` | `sandbox` for the hosted environment simulator |
| `PLATFORM_DATA_DIR` | `/app/.workbench`, or a writable persistent-disk mount when configured |

The browser uses its native HTTPS username/password prompt. The UI then opens without the local operator-token dialog. Scoped bearer credentials still support separate agent/MCP clients; an agent cannot approve, commit, promote or mutate operator-only resources. Foreign Host and Origin headers remain rejected. Missing/partial public authentication configuration stops startup.

The live model endpoint is fixed to `https://api.tokenfactory.nebius.com/v1/chat/completions`. Requests require strict JSON schemas, a 30-second full-response deadline, bounded output, at most two concurrent Nebius calls and no redirects. Nemotron's optional thinking is disabled to preserve the output budget. Model proposals still pass independent semantic validation, policy, exact-digest review and revision checks. The same adapter supports bounded Java source-patch proposals with unchanged tests and at most three model calls.

The model configuration matches the previous Supplier Risk Intelligence setup. This project still requires its own live smoke/evaluation results; configuration and stubbed HTTP tests do not establish model quality or current account access.

## Runtime and persistence

A Render web service does not supply the trusted local Docker runner used by the repository workflow. The hosted app exposes actual runner availability and rejects isolated execution when it is unavailable. It never executes uploaded Java directly in the web server. Run the complete repository repair and local workload demonstration on a Docker-capable host; the CI workflow verifies both against actual containers.

The Free service filesystem is ephemeral, so its demo state can reset during restart or redeployment. Export evidence before redeploying. Durable H2 state and managed Git repositories require a persistent disk on a suitable paid service; choose that deliberately before claiming hosted restart persistence. The default local application remains durable in `.workbench`.

## Verify after deployment

1. Confirm the deploy reaches `live` and `/actuator/health` returns 200.
2. Confirm a fresh request to `/`, `/api/config` and `/actuator/metrics` gets a 401 Basic challenge.
3. Verify correct credentials open the UI and API; incorrect credentials and foreign origins are rejected.
4. Compile a new request in live mode, inspect all policy results, approve a simulator plan and exercise reviewed drift recovery.
5. Run interpreter evaluation and retain its actual provider/model and outcomes. Do not substitute fixture replay scores for live results.
6. Check the repository screen's runner disclosure and inspect the actual Docker evidence in CI.

Never put model keys, login passwords, bearer tokens or deploy hooks in this repository, build arguments, images, public docs or logs. Configure secrets at runtime. Use a new password for this project rather than reusing another application's login.
