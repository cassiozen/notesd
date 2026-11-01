/** Settings' categories, in the order docs/contributing/settings-guidelines.md gives. The native apps' About has nothing on the web. */
export const SECTIONS = [
  { key: 'server', title: 'Server', group: 'account' },
  { key: 'security', title: 'Security', group: 'account' },
  { key: 'spaces', title: 'Spaces', group: 'account' },
  { key: 'integrations', title: 'Integrations', group: 'account' },
  { key: 'appearance', title: 'Appearance', group: 'device' },
  { key: 'device', title: 'Sync & data', group: 'device' },
] as const

export type SettingsSection = (typeof SECTIONS)[number]['key']

export function isSettingsSection(key: string): key is SettingsSection {
  return SECTIONS.some((s) => s.key === key)
}
