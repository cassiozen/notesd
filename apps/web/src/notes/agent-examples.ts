import type { AgentTrigger } from '../api/agent'
import { rulesOf, type AgentDraft } from './agent-rules'

/** A whole agent to start from: its purpose and rules. menuLabel: its menu rule's command name. */
export type AgentExample = { name: string; prompt: string; rules: Partial<Record<AgentTrigger, string>>; menuLabel?: string }

/** From the agents guide. */
export const EXAMPLES: AgentExample[] = [
  {
    name: 'Tidy tags',
    prompt: 'Everyday notes. Keep them findable with a small, consistent set of tags.',
    rules: {
      newNoteDone: 'Add 1 to 3 tags on a line at the end, reusing the space’s tags whenever one fits. Change nothing else.',
      menu: 'Suggest a fix for each spelling mistake and obvious typo, for me to accept or reject. Keep the wording, punctuation and formatting.',
    },
    menuLabel: 'Fix typos',
  },
  {
    name: 'Recipes',
    prompt: 'Recipes I cook. I measure in grams and millilitres.',
    rules: {
      newNoteDone:
        'Turn the ingredients into a checklist, converting cups and ounces to metric. Tag the recipe with its cuisine and main ingredient. Leave the method as written.',
      menu: 'Rewrite the quantities for 2 servings, with the originals in brackets.',
    },
    menuLabel: 'Scale to 2 servings',
  },
]

/** Nothing written yet to start from an example over: no purpose, no rules. */
export const isBlank = (d: AgentDraft) => !d.prompt.trim() && rulesOf(d).length === 0

/** The draft with the example's purpose and rules. Only offered while blank, so there's nothing of mine to keep. */
export function withExample(d: AgentDraft, ex: AgentExample): AgentDraft {
  return { ...d, prompt: ex.prompt, triggers: { ...ex.rules }, menuLabel: ex.menuLabel ?? '' }
}
