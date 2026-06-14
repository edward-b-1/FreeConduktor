# Release Process

`gh` CLI is NOT installed. Do not attempt to use it. All GitHub operations use the web UI.

## Remotes

- `origin` — GitHub (public). Push `main` and version tags.
- `gitlab` — GitLab (private backup). Push `main` only, never tags.

## Steps

1. **Bump version** in `build.gradle.kts`: `version = "0.1.X"`
2. **Commit** all pending changes including the version bump
3. **Tag**: `git tag v0.1.X`
4. **Push to GitHub**: `git push origin main && git push origin v0.1.X`
5. **Push to GitLab**: `git push gitlab main`
6. **Clean stale build output**: `Remove-Item -Recurse -Force "build\jpackage\FreeConduktor"`
7. **Build**: `.\gradlew.bat jpackageImage`
8. **Zip**: `Compress-Archive -Path "build\jpackage\FreeConduktor" -DestinationPath "build\jpackage\FreeConduktor-0.1.X-windows.zip"`
9. **Create GitHub release** at https://github.com/edward-b-1/FreeConduktor/releases/new
   - Tag: select existing `v0.1.X`
   - Title: `v0.1.X`
   - Write release notes (see format below)
   - Upload `FreeConduktor-0.1.X-windows.zip` as the release asset

## Release notes format

```
## v0.1.X

### Section heading

- **Feature name** — description of what changed and why it matters
```
