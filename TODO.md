# FreeConduktor TODO

## In Progress / Upcoming

- [x] **Create Topic dialog: replication factor bugs**
  - [x] Changing the Replication Factor spinner does not update the partitions hint label
  - [x] Replication Factor spinner max should be capped at the number of brokers in the connected cluster; the dialog currently does not receive broker count so the cap is not enforced
- [ ] **Create Topic dialog: Advanced Configuration panel**
  - [ ] Expanding the panel does not resize the dialog window vertically
  - [ ] Advanced config content may need to be scrollable
  - [ ] Config is currently a raw text area; the original app has a dedicated UI element per config property
  - [ ] Topic Override column should support in-place editing (see `Screenshot 2026-06-14 113108.png`) rather than opening a separate dialog box
- [ ] **Network call failure handling (investigation required)** — many UI elements call the admin client or other services to retrieve cluster properties; behaviour on failure is unclear
  - [ ] What happens if `getBrokerCount()` fails? Currently defaults to 1 but the failure is silent — should it show an error or warning?
  - [ ] Audit all network calls made at dialog/view open time and define consistent error behaviour
- [x] **Fix the Create Topic dialog** — full rebuild to match original Conduktor look. Specific issues:
  - [x] Labels truncated to "..." — GridPane label column too narrow
  - [x] Prompt text broken — `\n` in `promptText` not rendering, config hint runs together on one line
  - [x] Partitions / Replication Factor are plain `TextField`; should be `Spinner<Int>` with up/down arrows
  - [x] Missing info hint next to Replication Factor: "You will create N new partitions on your cluster"
  - [x] Missing Cleanup Policy row with toggle switches: "Retention (time or size)" and "Compaction (key-based)"
  - [x] Missing collapsible "Advanced Configuration" `TitledPane` (replaces raw config text area)
  - [x] Button should be accented "CREATE TOPIC", not plain "Create"
  - [x] Window title should be "Create New Topic", not "Create Topic"
- [x] **Fix window icon** — all dialog windows should display the application icon in the title bar
- [ ] **Status bar: connected cluster address** — show bootstrap address as a dedicated element when connected
- [ ] **Status bar: gear icon** — add gear icon to the left of the cluster address (opens cluster settings/options)
- [ ] **Status bar: Timezone display** — clickable "Timezone: UTC" on the right side; opens a config window to change the timezone used for message timestamps; persist selection
- [ ] **Persist app preferences** — save and restore app-level state across sessions; Conduktor stores these in `app.properties`. Suggested: `~/.freeconductor/app.json`. Items to persist:
  - [ ] Window size and maximized state
  - [ ] Theme (light/dark)
  - [ ] Sidebar collapsed state
  - [ ] Cluster sort order
- [ ] **Persist per-cluster preferences** — extend `ClusterConfig` model; Conduktor stores these inside each cluster entry. Items to persist:
  - [ ] Column visibility (broker ID, rack, size, RF/partition, spread, last write, consumer groups, etc.)
  - [ ] `showDatesAsUTC` / timezone selection (ties into status bar Timezone TODO)
  - [ ] Per-topic serde preferences (key serde, value serde, default from-offset)
  - [ ] Consumer/producer/admin client config overrides (timeouts, fetch sizes, etc.)
  - [ ] Favourite topics and consumer groups
- [ ] **Make styles consistent** — audit the app against `STYLE-GUIDE.md` and converge on the documented house styles. Known divergences: detail-view titles use inline font sizes (20/22/24px) instead of the `.view-title` class (30px); muted small-text uses duplicated inline `style` strings instead of a shared class; some refresh buttons still use the "↻" glyph instead of a `FontAwesomeSolid.SYNC_ALT` icon; hardcoded hex colors outside the broker-badge legend should move to `-color-*` tokens.
- [ ] **Advanced Configuration: fetch property descriptions from the live Kafka cluster** — Conduktor fetches official Kafka documentation strings at runtime by appending `_DOC` to each config key (e.g. querying `retention.ms_DOC` returns the full Kafka doc string for that property). This gives version-appropriate descriptions rather than hardcoded ones. See `reference/topic-config-tooltips.md` for the extracted strings and a comparison with the current FreeConduktor descriptions.

## Completed

<!-- move items here as they are done -->
