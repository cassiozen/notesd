import type { AgentTrigger } from '../api/agent'
import { MAX_REFERENCES, TRIGGERS, type AgentDraft } from './agent-rules'

/** A rule to borrow. title: what it does, in a few words. reads: notes it needs the agent to always read. */
export type Idea = { title: string; prompt: string; menuLabel?: string; reads?: string[] }

/** A whole agent to learn from, or to take part or all of. */
export type AgentExample = { name: string; blurb: string; prompt: string; rules: Partial<Record<AgentTrigger, Idea>> }

const DIGEST_TRIM: Idea = {
  title: 'Keep the Digest current',
  prompt: 'Take out of the Digest anything that came only from this entry.',
  reads: ['Digest'],
}

/** From the agents guide. */
export const EXAMPLES: AgentExample[] = [
  {
    name: 'Journal',
    blurb: 'Starts each entry with a few follow-ups, and keeps a Digest of what’s on your mind.',
    prompt:
      'My journal. These words are mine: don’t rewrite or add to what I write unless a rule says so. Keep a note titled Digest that sums up my recent entries.',
    rules: {
      noteCreated: {
        title: 'Follow-ups from the Digest',
        prompt:
          'Show me a card at the top with a short list of 2 to 4 follow-ups and questions, no heading. Take them mostly from For now in the Digest (“Cold gone yet?”, “Did the talk happen?”), with one from Lasting now and then. Friendly and direct, like a friend catching up: ask what happened, not how I felt about it. Only if there’s no Digest, ask about my day. Don’t write in the entry.',
        reads: ['Digest'],
      },
      newNoteDone: {
        title: 'Update the Digest',
        prompt:
          'Update the Digest with this entry, in two sections, a few short lines each. Lasting: realizations about myself, patterns, values, big decisions; things worth knowing about me months from now. Keep these unless I say otherwise. For now: things to follow up on soon (a cold, a deadline, plans for the weekend), each with the date I first mentioned it. Take one out once I say it’s over, or after 2 weeks with no mention. Merge with what’s there and rewrite the whole Digest. If I wrote nothing, change nothing. Create the Digest if there isn’t one.',
        reads: ['Digest'],
      },
      noteDeleted: DIGEST_TRIM,
      movedOut: DIGEST_TRIM,
    },
  },
  {
    name: 'Recipes',
    blurb: 'Turns ingredients into a checklist in metric, tags each recipe, and scales it when you ask.',
    prompt: 'Recipes I cook. I measure in grams and millilitres.',
    rules: {
      newNoteDone: {
        title: 'Metric checklist, tagged',
        prompt:
          'Turn the ingredients into a checklist, converting cups and ounces to metric. Tag the recipe with its cuisine and main ingredient. Leave the method as written.',
      },
      menu: {
        title: 'Scale to 2 servings',
        menuLabel: 'Scale to 2 servings',
        prompt: 'Rewrite the quantities for 2 servings, with the originals in brackets.',
      },
    },
  },
  {
    name: 'Tidy tags',
    blurb: 'Tags each new note from a small, consistent set, and suggests typo fixes when you ask.',
    prompt: 'Everyday notes. Keep them findable with a small, consistent set of tags.',
    rules: {
      newNoteDone: {
        title: 'Tag from a small set',
        prompt: 'Add 1 to 3 tags on a line at the end, reusing the space’s tags whenever one fits. Change nothing else.',
      },
      menu: {
        title: 'Suggest typo fixes',
        menuLabel: 'Fix typos',
        prompt: 'Suggest a fix for each spelling mistake and obvious typo, for me to accept or reject. Keep the wording, punctuation and formatting.',
      },
    },
  },
]

/** Ideas for a rule: the examples', then a general one for a moment no example covers well. */
export function ideasFor(trigger: AgentTrigger): { idea: Idea; from?: string }[] {
  const out: { idea: Idea; from?: string }[] = []
  for (const ex of EXAMPLES) {
    const idea = ex.rules[trigger]
    if (idea && !out.some((o) => o.idea.prompt === idea.prompt)) out.push({ idea, from: ex.name })
  }
  const general = TRIGGERS.find((t) => t.key === trigger)?.idea
  if (general) out.push({ idea: general })
  return out
}

/** The notes an example's rules need it to always read. */
export function readsOf(ex: AgentExample): string[] {
  return [...new Set(Object.values(ex.rules).flatMap((r) => r.reads ?? []))]
}


const menuLabels = new Set(EXAMPLES.flatMap((ex) => ex.rules.menu?.menuLabel ?? []))

/** The notes added to those it reads, as many as fit: References offers the rest while a rule needs them. */
function reading(refs: string[], more: string[]): string[] {
  const out = [...refs]
  for (const r of more) if (!out.includes(r) && out.length < MAX_REFERENCES) out.push(r)
  return out
}

/**
 * The draft with the idea as its rule for the moment, and the notes it needs read. A command name
 * typed already stays, unless it's another idea's.
 */
export function withIdea(d: AgentDraft, trigger: AgentTrigger, idea: Idea): AgentDraft {
  const name = d.menuLabel.trim()
  const menuLabel = trigger === 'menu' && idea.menuLabel && (!name || menuLabels.has(name)) ? idea.menuLabel : d.menuLabel
  return { ...d, triggers: { ...d.triggers, [trigger]: idea.prompt }, menuLabel, referenceNotes: reading(d.referenceNotes, idea.reads ?? []) }
}

/** The draft with all of the example: its purpose, and its rules over any for the same moments. Others stay. */
export function withExample(d: AgentDraft, ex: AgentExample): AgentDraft {
  let out: AgentDraft = { ...d, prompt: ex.prompt }
  for (const [trigger, idea] of Object.entries(ex.rules) as [AgentTrigger, Idea][]) {
    out = withIdea({ ...out, menuLabel: trigger === 'menu' ? '' : out.menuLabel }, trigger, idea)
  }
  return out
}

/** Whether taking the idea would change the draft's rule: not if it has it, as withIdea would leave it. */
export function hasIdea(d: AgentDraft, trigger: AgentTrigger, idea: Idea): boolean {
  const w = withIdea(d, trigger, idea)
  return w.triggers[trigger] === d.triggers[trigger] && w.menuLabel === d.menuLabel
}

/** Notes the draft's rules taken from examples need read, as each was taken (at any moment): an edited one may not. */
export function readsNeeded(d: AgentDraft): string[] {
  const prompts = new Set(Object.values(d.triggers))
  const out = new Set<string>()
  for (const ex of EXAMPLES)
    for (const idea of Object.values(ex.rules)) if (prompts.has(idea.prompt)) for (const r of idea.reads ?? []) out.add(r)
  return [...out]
}

/** Whether a draft has every part of the example already. */
export function hasExample(d: AgentDraft, ex: AgentExample): boolean {
  return (
    d.prompt === ex.prompt &&
    (Object.entries(ex.rules) as [AgentTrigger, Idea][]).every(([t, idea]) => hasIdea(d, t, idea)) &&
    readsOf(ex).every((r) => d.referenceNotes.includes(r))
  )
}
