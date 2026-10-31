# Cloudflare Tunnel for a small public trial

Ardor stays a single Docker Compose stack. A named Cloudflare Tunnel publishes only
the frontend entry point; the API keeps going through the frontend's existing `/api`
rewrite, and the voice WebSocket is routed directly to the backend because Next.js
does not proxy WebSocket upgrades.

```
browser -> https://app.ischarlesyang.com -> Cloudflare edge -> cloudflared (Compose)
        -> frontend:3000  (/ and /api/* via Next rewrite -> backend:8080)
        -> backend:8080   (/ws/* only)
```

PostgreSQL, Redis, backend `8080` and frontend `3000` remain bound to `127.0.0.1`
(PostgreSQL/Redis) or the Compose network; the tunnel is the only public path.

## Existing resources

Check what already exists before creating anything:

```powershell
cloudflared tunnel list
```

A tunnel created for another application must not be reused. At the time of writing,
`interview-guide` serves `interview.ischarlesyang.com`; Ardor uses its own named tunnel
`ardor` and the hostname `app.ischarlesyang.com`.

## One-time setup

1. Create the named tunnel (skip if `ardor` already appears in `cloudflared tunnel list`):

   ```powershell
   cloudflared tunnel create ardor
   ```

   This writes the credentials JSON under `%USERPROFILE%\.cloudflared\`. Keep it there;
   never copy it into the repository.

2. Route the hostname to the tunnel. This creates a proxied CNAME in the existing
   `ischarlesyang.com` zone:

   ```powershell
   cloudflared tunnel route dns ardor app.ischarlesyang.com
   ```

3. Copy `deploy/cloudflared/config.example.yml` to a directory outside the repository,
   for example `%LOCALAPPDATA%\ProjectArdor\cloudflared\config.yml`, and replace
   `<TUNNEL_UUID>` with the id printed by `cloudflared tunnel create`. The credentials
   path inside the container is fixed at `/etc/cloudflared/credentials.json`.

   Validate the ingress rules:

   ```powershell
   cloudflared --config "$env:LOCALAPPDATA\ProjectArdor\cloudflared\config.yml" tunnel ingress validate
   cloudflared --config "$env:LOCALAPPDATA\ProjectArdor\cloudflared\config.yml" tunnel ingress rule https://app.ischarlesyang.com/ws/interviews/voice/new
   ```

4. Add the non-secret tunnel paths and HTTPS settings to the root `.env` (never commit it):

   ```dotenv
   SESSION_COOKIE_SECURE=true
   ARDOR_REALTIME_ALLOWED_ORIGINS=https://app.ischarlesyang.com,http://localhost:3000,http://127.0.0.1:3000
   ARDOR_REGISTRATION_ALLOWLIST=tester1@example.com,tester2@example.com
   CLOUDFLARE_TUNNEL_CONFIG=C:/Users/<user>/AppData/Local/ProjectArdor/cloudflared/config.yml
   CLOUDFLARE_TUNNEL_CREDENTIALS=C:/Users/<user>/.cloudflared/<tunnel-uuid>.json
   ```

   `ARDOR_REGISTRATION_ALLOWLIST` is the admission control for the trial: only those
   exact emails can register; existing accounts still log in. Leave it empty only for
   local development. Quotas for chat, voice and other features are unchanged.

5. Rebuild and restart the backend so the cookie, origin and allowlist settings apply:

   ```powershell
   .\scripts\start-deploy.ps1
   ```

## Start, stop and logs

```powershell
# Start only the tunnel after the app is healthy
docker compose -f docker-compose.yml -f docker-compose.tunnel.yml up -d cloudflared

# Status
docker compose -f docker-compose.yml -f docker-compose.tunnel.yml ps cloudflared
cloudflared tunnel info ardor

# Logs (JSON file driver, 10 MB x 3 per container)
docker logs -f project-ardor-cloudflared-1

# Stop the public entry point; the local stack keeps running
docker compose -f docker-compose.yml -f docker-compose.tunnel.yml stop cloudflared
```

The container uses `restart: unless-stopped`, so it comes back when Docker Desktop
starts again. It does not make the host highly available: **if this machine is offline,
sleeping or shut down, the public site is unavailable.** The tunnel is outbound-only and
opens no inbound port.

## Security boundaries

- The tunnel and DNS changes are the only public resources. Database (`127.0.0.1:5433`),
  Redis (`127.0.0.1:6379`), backend (`127.0.0.1:8080`) and frontend (`127.0.0.1:3000`)
  are not published beyond the host.
- Session and CSRF cookies are `Secure`, `HttpOnly` where applicable, `SameSite=Lax`
  with `SESSION_COOKIE_SECURE=true`. Forwarded-proto headers are honored so HTTPS
  requests are treated as secure.
- `ARDOR_REALTIME_ALLOWED_ORIGINS` must contain the public HTTPS origin; otherwise the
  voice WebSocket handshake is rejected even though the HTTP page loads.
- New accounts are limited by `ARDOR_REGISTRATION_ALLOWLIST`. Per-user monthly, weekly
  and per-minute quotas plus the model-call admission layer still apply. There is no
  application-level login brute-force limiter; use Cloudflare Access or a WAF rate-limit
  rule if the trial needs that layer.
- Tunnel credentials and any Cloudflare API token stay outside the repository and must
  never be printed, committed or pasted into an issue/PR.

## Verification checklist

Run these after the tunnel is running; do not treat a healthy tunnel or a `200` login
page as sufficient:

1. `curl -I https://app.ischarlesyang.com/login` shows Cloudflare headers and `200`.
2. Register through the public URL with an allowlisted email; the `Set-Cookie` headers
   for `SESSION` and `XSRF-TOKEN` contain `Secure`.
3. Authenticated `/api/auth/me`, conversation creation and a tiny image upload succeed.
4. An SSE request to `/api/agent/messages/stream` returns `text/event-stream` and event
   frames through the public host. A failed model configuration can exercise the stream
   without spending model quota.
5. A `wss://app.ischarlesyang.com/ws/interviews/voice/new` handshake with the session
   cookie and public `Origin` reaches the backend and receives `ready`; do not send
   audio unless a real voice test is intended.
6. A second account receives `404` for the first account's run/trace IDs.

## Recovery

- **Tunnel down:** `docker logs project-ardor-cloudflared-1`, then restart the container.
  Local access at `http://localhost:3000` is unaffected.
- **Bad client config:** stop the tunnel, fix the rendered config, validate it with
  `cloudflared tunnel ingress validate`, then start it again.
- **App rollback:** use the pre-deploy database dump and image tags created before the
  deployment (`backups/db-pre-deploy-*.sql.gz`, `ardor-*-rollback:pre-deploy-*`). Restore
  only with explicit intent; keep the database dump until the trial is over.
- **Remove public access:** stop the tunnel container; delete the DNS record only if the
  hostname should stop resolving. Deleting the tunnel itself is irreversible and is not
  part of a normal rollback.
