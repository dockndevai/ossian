# Demo assets

Regenerated rather than hand-made, so they cannot drift from the UI.

```bash
docker compose up -d
./mvnw -pl backend spring-boot:run
cd frontend && npm run dev

node docs/media/demo/record.mjs /tmp/ossian-frames      # drive the walkthrough, capture frames
./docs/media/demo/encode.sh /tmp/ossian-frames docs/media/demo   # frames -> gif + mp4
node docs/media/demo/capture.mjs docs/media/demo        # the stills
```

Both scripts drive headless Chrome over the DevTools Protocol. They seed an OIDC session in
`sessionStorage` and pin the light theme, so the output is consistent between runs — and they
sign in *before* recording starts, so no credential is ever on screen.

The recorder captures on a loop running alongside the walkthrough rather than between its steps,
which is what catches the answer streaming in. It waits for the citation chips to appear before
moving on; a fixed delay would film a spinner whenever the model is slow.

`encode.sh` produces both a GIF and an MP4. GitHub renders a GIF inline from a repository path
and will not render an MP4 from one, but the MP4 is a third the size at better quality, so it is
worth keeping for anywhere that can play it. Playback is 12.5 fps against a 5 fps capture — two
and a half times real speed, because the walkthrough takes about eighty seconds to perform and
nobody watches eighty seconds of a README.
