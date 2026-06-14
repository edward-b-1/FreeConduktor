# FreeConduktor TODO

## In Progress / Upcoming

- [x] **Create Topic dialog: replication factor bugs**
  - [x] Changing the Replication Factor spinner does not update the partitions hint label
  - [x] Replication Factor spinner max should be capped at the number of brokers in the connected cluster; the dialog currently does not receive broker count so the cap is not enforced
- [ ] **Create Topic dialog: Advanced Configuration panel**
  - [ ] Expanding the panel does not resize the dialog window vertically
  - [ ] Advanced config content may need to be scrollable
  - [ ] Config is currently a raw text area; the original app has a dedicated UI element per config property
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

## Completed

<!-- move items here as they are done -->
