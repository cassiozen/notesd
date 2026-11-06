// Generated from shared/themes by shared/themes/generate.ts. Change the JSON there and run `mise run themes`.

/** bar: --bg as hex, for the browser's title bar (<meta name="theme-color">), which takes no light-dark(). */
export const THEMES = [
  {"id":"amber","name":"Amber","mood":"Afternoon light on paper","palette":"amber","style":"glass","fonts":{"title":"instrument-serif","body":"instrument-sans"},"sidebar":"theme","bar":{"light":"#F5F3F1","dark":"#121416"}},
  {"id":"nocturne","name":"Nocturne","mood":"The city, long after midnight","palette":"nocturne","style":"lumen","fonts":{"title":"shippori-mincho","body":"zen-kaku-gothic"},"sidebar":"theme","bar":{"light":"#E8E9EF","dark":"#13141B"}},
  {"id":"carta","name":"Carta","mood":"Black ink on good paper","palette":"carta","style":"paper","fonts":{"title":"playfair","body":"hanken-grotesk"},"sidebar":"theme","bar":{"light":"#ECEBE7","dark":"#0A0A0A"}},
  {"id":"den","name":"Den","mood":"Warm graphite, one honey mark","palette":"den","style":"flush","fonts":{"title":"nunito-sans-bold","body":"nunito-sans"},"sidebar":"dark","bar":{"light":"#F6F6F5","dark":"#222427"}},
] as const

export const DEFAULT_THEME = "amber"
