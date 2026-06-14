# FreeConduktor House Style Guide

The visual language of the app, derived from `src/main/resources/com/freeconductor/styles.css`
and the inline styles in the Kotlin UI code. Use this to keep new windows consistent and to
clean up existing divergences.

**Golden rule:** prefer a shared CSS style class over an inline `style = "..."`. Inline styles
have the highest CSS specificity, can't be themed, and are the main source of the
inconsistencies listed at the bottom of this document.

---

## 1. Foundations

| Thing | Choice | Notes |
|-------|--------|-------|
| Theme | AtlantaFX **PrimerLight** | Set in `App.kt` via `Application.setUserAgentStylesheet(PrimerLight().userAgentStylesheet)`. A NordDark option is wired in `MainWindow.kt`. All colors should come from this theme's tokens (see §3). |
| UI font | **Roboto** (Regular 400, Medium 500, Bold 700) | Bundled under `resources/.../fonts/`. `.root { -fx-font-family: "Roboto"; }` applies it everywhere. |
| Monospace font | **Roboto Mono** | For hosts, addresses, code, message payloads, and any value where alignment/copy-paste matters. |
| Icons | **Ikonli FontAwesome5** (`FontAwesomeSolid`) | `org.kordamp.ikonli:ikonli-fontawesome5-pack`. Size via `FontIcon(...).also { it.iconSize = N }`. |

> **Note:** Roboto Medium (500) is loaded via `@font-face` but currently unused by any rule —
> weight in practice is just Regular (400) or Bold (700).

---

## 2. Typography scale

Sizes actually in use, from largest to smallest. **Bold** unless noted. "muted" = `-color-fg-muted`.

| Size | Weight | Used for (style class) |
|------|--------|------------------------|
| 30px | bold | **View titles** — `.view-title` (BROKERS, TOPICS, …); big stat numbers `.topics-stat-value` |
| 26px | bold | Dashboard stat numbers — `.stat-value`, `.stat-value-alert` |
| 24px | bold | Welcome screen title `.title-label` |
| 22px | bold | Connected-cluster name in sidebar `.connected-cluster-name` |
| 16px | bold | Sidebar logo text `.sidebar-logo-text` |
| 15px | bold | Cluster list cell name `.cluster-cell-name` |
| 14px | regular/bold | Nav buttons `.nav-button`; cluster-card name (bold); welcome subtitle (regular, muted) |
| 13px | regular | Detail/link text — `.cluster-cell-detail` (mono), `.topic-name-link`, `.broker-config-name`, `.broker-config-link`, `.overview-section-title` (bold), `.nav-btn` (bold) |
| 12px | regular/bold | The workhorse small size — status bar, table column headers (bold), `.code-area` (mono), `.stat-label` (muted), `.toolbar-action-btn` (bold), `.settings-btn` (bold), `.broker-config-badge` (bold) |
| 11px | regular/bold | `.sidebar-header` (bold, muted), `.cluster-card-host` (mono, muted), `.security-badge` |
| 10px | bold | `.config-section-label` (muted), `.security-badge` |
| 9px  | bold | `.topic-deprecated-badge` |

**Convention for a new screen:** the page title uses `.view-title` (30px bold). Section labels
are 11–13px bold, often muted and/or accent-colored. Body text is 12–14px. Never hand-pick a
title size inline — add the `view-title` class.

---

## 3. Color tokens (AtlantaFX semantic palette)

Always reference these tokens, **never** raw hex. They adapt automatically if the theme changes.

### Backgrounds
| Token | Role |
|-------|------|
| `-color-bg-default` | Main content background |
| `-color-bg-subtle` | Sidebar, toolbars, status bar, hover-card backgrounds |
| `-color-bg-inset` | Pressed/hover state for icon buttons |

### Text
| Token | Role |
|-------|------|
| `-color-fg-default` | Primary text |
| `-color-fg-muted` | Secondary / hint / label text |

### Lines
| Token | Role |
|-------|------|
| `-color-border-default` | Every divider, table border, card outline |

### Accent (blue) — interactive / links / selection
`-color-accent-fg` (text & links), `-color-accent-subtle` (active nav background, header tint),
`-color-accent-muted` (badge background).

### Status colors
| Intent | fg | muted (badge bg) |
|--------|-----|------------------|
| Success | `-color-success-fg` | `-color-success-muted` |
| Warning / deprecated | `-color-warning-fg` | `-color-warning-muted` |
| Danger / alert | `-color-danger-fg` | — |
| Neutral | `-color-fg-muted` | `-color-neutral-muted` |

`-color-shadow-default` is used for elevation/drop-shadows.

### ⚠ Exception: hardcoded badge colors
The broker config source badges in `styles.css` use literal hex instead of tokens:

| Badge | Hex | Meaning |
|-------|-----|---------|
| `.broker-config-badge-static`  | `#4078C0` (blue)   | Static (server.properties) |
| `.broker-config-badge-broker`  | `#2e8b57` (green)  | Broker-level dynamic override |
| `.broker-config-badge-cluster` | `#7b52ab` (purple) | Cluster-wide dynamic default |
| `.broker-config-badge-topic`   | `#b07d2a` (amber)  | Topic-level override |

These are a deliberate 4-way categorical legend (no semantic token maps cleanly to "purple"),
but they are the one place we leave the token system. Reuse these exact hex values if you add
to that legend; don't invent new ad-hoc colors elsewhere.

---

## 4. Component catalog

**Buttons**
- Primary action — AtlantaFX `accent` style class (e.g. "CREATE TOPIC").
- Toolbar action — `.toolbar-action-btn` (12px bold, padding `8 20`).
- Icon/ghost buttons — transparent background, `-color-border-default` outline, radius 4–6,
  `-color-bg-inset` on hover: `.settings-btn`, `.home-btn`, `.nav-btn`, `.sidebar-collapse-btn`, `.row-icon-btn`.
- Primary-toolbar buttons are pinned to 34px height for a uniform strip.

**Badges** — small, bold, rounded pills:
- `.security-badge*` (radius 10, 10–11px) for connection security (PLAINTEXT/SSL/SASL/SASL_SSL).
- `.broker-config-badge*` (radius 3, 12px) single-letter source markers.
- `.topic-deprecated-badge` (radius 3, 9px, warning colors).

**Tables** — `.table-view` 1px border; `.column-header` bold 12px with bottom rule only.
Row links use `.topic-name-link` / `.broker-config-link` (accent text, underline on hover, no
button chrome).

**Cards / sections** — `.cluster-card` (radius 8, padding `16 20 20`, accent border on hover),
`.overview-section` (radius 6, padding `12 16`), `.stat-card` / `.topics-stat-box` (vertical
divider between items, none on `:last-child`).

**Sidebar** — `-color-bg-subtle` background; `.sidebar-header` 11px bold muted with letter
spacing; nav items `.nav-button` with `.nav-button-active` (accent subtle bg + accent text).

**Status bar** — `.status-bar`, `-color-bg-subtle`, top border, 12px.

**TitledPane (collapsible panels)** — to remove AtlantaFX's border, add the
`borderless-titled-pane` class and override the **sub-nodes** `> .title` and `> .content`
(NOT the root node — see `reference_atlantafx_styling` memory / commit b73683f for why).

---

## 5. Spacing & layout conventions

- Content/page padding: ~`16 20` (top/bottom, left/right). Headers often `10 16`.
- HBox/VBox gaps: **8** (tight, toolbar), **14–16** (form rows / sections).
- Form label column width: `minWidth = 150.0` so fields line up.
- Corner radii: **3** badges, **4** buttons/inputs, **6** small cards/sections, **8** large cards.
- Icon sizes: **11–14** inline with text, **20** for emphasis, **36** for hero/empty states.
- New windows are centered on the active window (see existing dialog setup) and dialogs that
  contain a resizable table should size-to-content and remember user resize (see CreateTopicDialog).

---

## 6. Known inconsistencies (cleanup targets)

These violate the rules above and are good candidates to converge:

1. **Detail-view titles use inline sizes instead of `.view-title` (30px):**
   - `TopicDetailView` title — inline `24px`
   - `BrokerDetailView` title — inline `22px`
   - `ConsumerGroupDetailView` title — inline `20px`
   - `DynamicConfigDialog` title — inline `20px`

   These should likely either all adopt `.view-title`, or we should define an intentional
   **secondary title** class (e.g. `.detail-title`) at one agreed size and use it everywhere,
   rather than three different inline values. (The BROKERS list title had the same problem — a
   stray inline `24px` — and was fixed by deleting the inline override so it uses `.view-title`.)

2. **Inline `style` strings for muted small text** (`-fx-text-fill: -color-fg-muted; -fx-font-size: 11/12/13px;`)
   recur across `MessageBrowserView`, `MessageDetailWindow`, `SimpleMessageCell`,
   `DynamicConfigDialog`. These duplicate what `.stat-label` / `.config-section-label` already
   express — candidates for a shared `.muted-caption` class.

3. **Hardcoded hex** outside the broker-badge legend should be migrated to `-color-*` tokens.

When adding a new screen, copy an existing well-styled view (TopicsView is a good reference) and
reuse its classes rather than introducing inline styles.
