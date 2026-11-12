<script setup lang="ts">
// A list that stays current over push: `useLiveList` against go/examples/push-demo.
// The hub is the page's ONE stream; the list declares its topics and applies each tick by version.
import { providePushHub, useLiveList } from "@flowcatalyst/ui/push";
import { computed, ref } from "vue";
import { BASE_URL, fetchTickets, publish, tickChange, topicFor } from "./demo.ts";
import type { Tick } from "./demo.ts";

const hub = providePushHub({ baseUrl: BASE_URL, getTickets: fetchTickets });

const count = ref(3);
const topics = computed(() => Array.from({ length: count.value }, (_, i) => topicFor(i + 1)));

const list = useLiveList<Tick>({
  topics,
  // The demo keeps no state to read, so the snapshot is empty and carries no cursor: the stream opens
  // live. An endpoint of your own returns its rows and the push cursor, read BEFORE the rows.
  load: () => Promise.resolve({ rows: [] }),
  id: (row) => row.id,
  version: (row) => row.seq,
  toChange: tickChange,
});

// The demo takes whatever sequence number the caller sends; a clock keeps it rising across reloads.
let seq = Date.now();
const failure = ref<string | null>(null);

async function publishAll(): Promise<void> {
  failure.value = null;
  try {
    await publish(topics.value.map((topic) => ({ seq: ++seq, topic })));
  } catch (cause) {
    failure.value = cause instanceof Error ? cause.message : String(cause);
  }
}

// The caller's own change, applied at once by the same version rule: hide a row. The next tick for
// the topic has a higher version, so the row comes back.
function hide(row: Tick): void {
  list.apply({ kind: "remove", id: row.id, version: row.seq });
}

const lastApplied = computed(() => (list.lastAppliedAt.value === null ? "never" : new Date(list.lastAppliedAt.value).toLocaleTimeString()));
</script>

<template>
  <main>
    <h1>Push live list</h1>
    <p>
      Status: <strong data-testid="status">{{ list.status.value }}</strong> · last applied {{ lastApplied }}
    </p>
    <p v-if="hub.error.value !== null" role="alert">Push error {{ hub.error.value.code }}: {{ hub.error.value.message }}</p>
    <p v-if="hub.denied.value.length > 0" role="alert">Denied: {{ hub.denied.value.map((d) => d.topic).join(", ") }}</p>
    <p v-if="failure !== null" role="alert">{{ failure }}</p>
    <p>
      <label>Topics <input v-model.number="count" type="number" min="1" max="100" /></label>
      <button type="button" @click="publishAll">Publish a tick to each</button>
    </p>
    <table>
      <thead>
        <tr>
          <th>Topic</th>
          <th>Version (seq)</th>
          <th>Sent (unix ns)</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in list.rows.value" :key="row.id">
          <td>{{ row.id }}</td>
          <td>{{ row.seq }}</td>
          <td>{{ row.ts }}</td>
          <td><button type="button" @click="hide(row)">Hide</button></td>
        </tr>
        <tr v-if="list.rows.value.length === 0">
          <td colspan="4">Nothing yet: publish a tick.</td>
        </tr>
      </tbody>
    </table>
  </main>
</template>

<style>
body {
  font-family: system-ui, sans-serif;
  margin: 2rem;
}
table {
  border-collapse: collapse;
}
th,
td {
  border: 1px solid #8884;
  padding: 0.25rem 0.75rem;
  text-align: left;
}
</style>
