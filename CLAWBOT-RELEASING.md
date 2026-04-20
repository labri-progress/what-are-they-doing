# Some notes on the release process of ClawdBot

## Quality gates
- CI skips documentation-only changes to speed up feedback
- CI infers a "scope" from the diff: android vs node vs macos, etc; and skips unaffected platforms
- CI include type, format, and lint checks
- CI runs smoke tests, regular tests, and tests the installer
- PRs are checked by: Codex, Greptile, etc. (LLMs reviewing LLMs)
- PRs are classified by i) size (S, XS, L, etc.) ii) contributor "trustworthiness"

## Releases
- Calendar-versioned and channel-based
- There is a 6 hour delay on auto-updates!
- Releases are organized into three update channels:
  - stable — official released versions with tags like vYYYY.M.D and npm dist-tag latest.
  - beta — prereleases with tags such as vYYYY.M.D-beta.N and npm dist-tag beta.
  - dev — the moving head of the main branch with npm dist-tag dev.
  - Users can switch channels via CLI (openclaw update --channel stable|beta|dev).
- Releases are pushed to Docker GHCR directly, main/latest/tagged channels
- Basically one release per day, version name is the date (`2026.1.24-1`)
  - Often with a `-beta` version _on the same day_ :o
  - CHANGELOG is _huge_ everytime
- 