# Demo screenshots

Regenerated rather than hand-taken, so they cannot drift from the UI:

```bash
docker compose up -d
./mvnw -pl backend spring-boot:run
cd frontend && npm run dev
node docs/media/demo/capture.mjs docs/media/demo
```

`capture.mjs` drives headless Chrome over the DevTools Protocol. It seeds an OIDC session in
`sessionStorage`, pins the light theme so the images are consistent, asks a real question and
**waits for the citation chips** before capturing — a fixed delay would photograph a spinner
whenever the model is slow.

Captured at 1440×900 with `deviceScaleFactor: 2`, so they stay sharp on a retina display and in
GitHub's own image viewer.
