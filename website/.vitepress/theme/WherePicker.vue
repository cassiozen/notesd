<script setup>
import { onMounted, ref } from "vue";
import { WHERE, WHERE_KEY } from "./where.js";

const where = ref(null);

// The inline script in config.mjs already marked <html> before the page painted.
onMounted(() => {
  where.value = Object.keys(WHERE).find((w) => document.documentElement.classList.contains(`where-${w}`)) ?? null;
});

function pick(w) {
  const c = document.documentElement.classList;
  for (const k of Object.keys(WHERE)) c.remove(`where-${k}`);
  if (w) c.add(`where-${w}`);
  where.value = w;
  try {
    if (w) localStorage.setItem(WHERE_KEY, w);
    else localStorage.removeItem(WHERE_KEY);
  } catch {}
}
</script>

<template>
  <div class="where-picker" role="group" aria-label="Your server">
    <span class="where-picker-label">Your server:</span>
    <button
      v-for="(name, id) in WHERE"
      :key="id"
      type="button"
      :aria-pressed="where === id"
      @click="pick(id)"
    >
      {{ name }}
    </button>
    <button v-if="where" type="button" class="where-picker-all" @click="pick(null)">Show all</button>
  </div>
</template>
