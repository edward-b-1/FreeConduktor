# Consumer Window — Design Questions & Suggestions

## 1. Offset committing

**Current behaviour:** `enable.auto.commit = false`, no manual commit. The consumer never
commits offsets.

**Question:** Should the consumer optionally commit offsets?

**Suggestion:** Keep "no commit" as the default — committing is a side effect with real
consequences (it advances the group's position on the broker, which could interfere with
production consumers sharing the same group ID). However, add an explicit **"Commit offsets"
checkbox** (off by default) for users who deliberately want to advance a group's offset
position, e.g. to manually move a consumer group past a poison message.

**Original app behaviour:** Almost certainly does not commit by default. It is a
diagnostic/inspection tool, not a processing application.

---

## 2. Consumer group dropdown population

**Current behaviour:** `getTopicConsumerGroups(topic)` is used to populate the dropdown.
This only returns groups that have **committed offsets for the selected topic**. Because we
don't commit (see §1), any group created via our own consumer window will never appear here.

**Problem:** The filter is too strict. It excludes:
- Groups that exist on the cluster but haven't consumed this specific topic yet.
- Any group created by our own consumer window (no commit → no record).

**Suggestion:** Switch to `listConsumerGroups()` (all non-internal groups on the cluster,
filtered to exclude internal `__` groups, sorted alphabetically). This matches what the
original app almost certainly does and is more useful in practice.

---

## 3. Dropdown refresh

**Current behaviour:** The consumer group list is loaded once when:
- The "Consumer group" radio button is selected, or
- The topic changes while in consumer group mode.

Opening the dropdown does not trigger a refresh.

**Suggestion:** Add a small **refresh button** (↻) next to the combo box, so the user can
manually reload the group list at any time. Optionally also refresh on dropdown open
(`setOnShown` on the ComboBox popup).

---

## 5. "Filter by topic" checkbox for consumer group selection

**Idea:** Add a **"Filter by topic" checkbox** alongside the consumer group combo box.

- **Unchecked (default):** Show all consumer groups on the cluster (see §2 suggestion).
- **Checked:** Filter the list to only groups that have committed offsets for the selected
  topic, using `getTopicConsumerGroups(topic)` — the current behaviour.

This gives the user the best of both worlds: a broad list by default, with the option to
narrow it down when working with a busy cluster that has many consumer groups.

---

## 6. "Filter by topic" checkbox — extended: also filter internal/private topics?

**Idea:** The topic combo box (and potentially other topic lists throughout the app) could
have a **"Show internal topics" checkbox** (off by default). Internal topics are those
prefixed with `__` (e.g. `__consumer_offsets`, `__transaction_state`) and are created and
managed by Kafka itself. They are rarely of interest to end users doing routine diagnostics.

This could be implemented as:
- A per-view checkbox toggle, or
- A global preference in the cluster settings that applies app-wide.

**Related:** The consumer group dropdown already filters out `__`-prefixed groups (§2 fix).
The same logic could be applied consistently to topic lists.

---

## 4. Related: what does "Consumer group" mode actually do?

When the user selects "Consumer group" and enters an existing group ID, the consumer seeks
to the **last committed offset** for that group on each partition, then reads forward from
there. This lets the user see what a real application's consumer group would read next.

Without committing (§1), this is a safe read-only inspection — it doesn't change the
group's state on the broker.
