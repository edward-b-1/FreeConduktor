# Release Process (instructions for Claude)

`gh` CLI is NOT installed. Do not attempt to use it. All GitHub operations use the web UI.

PowerShell 5.1 does not support `&&` between commands — use separate statements.

## Remotes

- `origin` — GitHub (public). Push `main` and version tags.
- `gitlab` — GitLab (private backup). Push `main` only, **never tags**.

---

## Steps

### 1. Bump version

In `build.gradle.kts`:
```
version = "0.1.X"
```

### 2. Commit

Stage only the files that were changed (never `git add -A`):
```
git add build.gradle.kts <other changed files>
git commit -m "..."
```

Use a PowerShell here-string for multi-line commit messages:
```powershell
git commit -m @'
Short summary line

- bullet
- bullet

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
'@
```

### 3. Tag

```
git tag v0.1.X
```

### 4. Push to GitHub (main + tag)

```
git push origin main
git push origin v0.1.X
```

### 5. Push to GitLab (main only)

```
git push gitlab main
```

### 6. Build

```
.\gradlew.bat clean installDist jpackageImage
```

### 7. Zip

```powershell
Compress-Archive -Path build\jpackage\FreeConduktor -DestinationPath build\jpackage\FreeConduktor-0.1.X-windows.zip -Force
```

### 8. Create GitHub release

Go to: https://github.com/edward-b-1/FreeConduktor/releases/new

- **Tag**: select existing `v0.1.X`
- **Title**: `v0.1.X` (no "FreeConduktor" prefix)
- **Body**: write release notes (see format below)
- **Asset**: upload `build\jpackage\FreeConduktor-0.1.X-windows.zip`

---

## Release notes format

Before writing release notes, **fetch the existing releases page** to check the current format:

```
WebFetch https://github.com/edward-b-1/FreeConduktor/releases
```

The format used on GitHub is plain bullet points under a bold heading with a colon — no `###` subheadings:

```
**Section name — optional subtitle:**
- First change — what it does and why it matters
- Second change — what it does and why it matters

**Another section:**
- Change
```

Example from v0.1.17:
```
**Create Topic dialog rebuild:**
- Spinners replace plain text fields for Partitions and Replication Factor
- Replication Factor capped at number of brokers in cluster, with live hint showing partition and replication details
- ...

**Bug fixes:**
- App icon now appears correctly on all dialog windows and standalone windows
```

For a single-section release:
```
**Feature area — subtitle:**
- Change one
- Change two
- Change three
```
