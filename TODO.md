# FreeConduktor TODO

## In Progress / Upcoming

- [x] **Fix the Create Topic dialog** — full rebuild to match original Conduktor look. Specific issues:
  - [x] Labels truncated to "..." — GridPane label column too narrow
  - [x] Prompt text broken — `\n` in `promptText` not rendering, config hint runs together on one line
  - [x] Partitions / Replication Factor are plain `TextField`; should be `Spinner<Int>` with up/down arrows
  - [x] Missing info hint next to Replication Factor: "You will create N new partitions on your cluster"
  - [x] Missing Cleanup Policy row with toggle switches: "Retention (time or size)" and "Compaction (key-based)"
  - [x] Missing collapsible "Advanced Configuration" `TitledPane` (replaces raw config text area)
  - [x] Button should be accented "CREATE TOPIC", not plain "Create"
  - [x] Window title should be "Create New Topic", not "Create Topic"
- [ ] **Status bar: connected cluster address** — show bootstrap address as a dedicated element when connected
- [ ] **Status bar: gear icon** — add gear icon to the left of the cluster address (opens cluster settings/options)
- [ ] **Status bar: Timezone display** — clickable "Timezone: UTC" on the right side; opens a config window to change the timezone used for message timestamps; persist selection

## Completed

<!-- move items here as they are done -->
