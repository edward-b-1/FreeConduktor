# Release Process

## Prerequisites

- JDK 21+ with `jpackage` on `PATH` (ships with JDK 21+, verify with `jpackage --version`)
- Git remotes configured:
  - `origin` → GitHub (`git@github-edward-b-1:edward-b-1/FreeConduktor.git`) — hosts releases
  - `gitlab` → GitLab (`git@gitlab.com:Birdsall/freeconduktor.git`) — backup mirror

---

## Step 1 — Bump the version

Edit `build.gradle.kts`:

```
version = "0.x.y"
```

Commit the version bump along with the other changes for the release, or as a standalone commit.

---

## Step 2 — Build the app image

```powershell
.\gradlew.bat jpackageImage
```

This produces a self-contained Windows app (bundled JVM, no Java install needed) at:

```
build\jpackage\FreeConduktor\
```

---

## Step 3 — Package into a zip

```powershell
Compress-Archive -Path "build\jpackage\FreeConduktor" `
                 -DestinationPath "build\FreeConduktor-0.x.y-windows.zip" `
                 -Force
```

Naming convention: `FreeConduktor-{version}-windows.zip`

---

## Step 4 — Tag the release

```bash
git tag v0.x.y
```

The tag should match the version in `build.gradle.kts`.

---

## Step 5 — Push to GitHub and GitLab

```bash
git push origin main
git push origin v0.x.y
git push gitlab main
```

---

## Step 6 — Create the GitHub release

1. Go to https://github.com/edward-b-1/FreeConduktor/releases/new
2. Select the tag `v0.x.y`
3. Set the release title to `FreeConduktor v0.x.y`
4. Write release notes summarising what changed
5. Upload `build\FreeConduktor-0.x.y-windows.zip` as a release asset
6. Publish the release
