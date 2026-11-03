import type { AgentDraft } from './agent-rules'

export type AgentExample = { name: string; blurb: string; draft: AgentDraft }

const DIGEST_TRIM = 'Take out of the Digest anything that came only from this entry.'

/** Starting points, from the agents guide. */
export const EXAMPLES: AgentExample[] = [
  {
    name: 'Journal',
    blurb: 'Asks you a question to start each entry, and keeps a Digest of what’s on your mind.',
    draft: {
      enabled: true,
      prompt:
        'My journal. These words are mine: don’t rewrite or add to what I write unless a rule says so. Keep a note titled Digest that sums up my recent entries.',
      menuLabel: '',
      referenceNotes: ['Digest'],
      triggers: {
        noteCreated:
          'Ask me one short, open question to get me writing, drawing on the Digest. Put it at the top as a heading. With no Digest yet, ask about my day.',
        newNoteDone:
          'Update the Digest with what this entry adds, under Themes, Open questions and Intentions. Keep each to a few short lines, merging with what’s there rather than adding to the end. Create the Digest if there isn’t one.',
        noteDeleted: DIGEST_TRIM,
        movedOut: DIGEST_TRIM,
      },
    },
  },
  {
    name: 'Meeting notes',
    blurb: 'Titles each meeting with its date, tags its projects, and lists action items when you ask.',
    draft: {
      enabled: true,
      prompt: 'Notes from meetings, one per meeting. Keep my wording; only add structure.',
      menuLabel: 'Action items',
      referenceNotes: [],
      triggers: {
        newNoteDone:
          'With no title yet, title it with the meeting’s subject and today’s date, like "Roadmap review, 3 Nov". Tag the projects it mentions, reusing the space’s tags.',
        menu: 'Add an "Action items" checklist at the end: what people agreed to do, each with who’s doing it. Leave the rest as it is.',
      },
    },
  },
  {
    name: 'Recipes',
    blurb: 'Turns ingredients into a checklist in metric, tags each recipe, and scales it when you ask.',
    draft: {
      enabled: true,
      prompt: 'Recipes I cook. I measure in grams and millilitres.',
      menuLabel: 'Scale to 2 servings',
      referenceNotes: [],
      triggers: {
        newNoteDone:
          'Turn the ingredients into a checklist, converting cups and ounces to metric. Tag the recipe with its cuisine and main ingredient. Leave the method as written.',
        menu: 'Rewrite the quantities for 2 servings, with the originals in brackets.',
      },
    },
  },
  {
    name: 'Tidy tags',
    blurb: 'Tags each new note from a small, consistent set, and fixes typos when you ask.',
    draft: {
      enabled: true,
      prompt: 'Everyday notes. Keep them findable with a small, consistent set of tags.',
      menuLabel: 'Fix typos',
      referenceNotes: [],
      triggers: {
        newNoteDone:
          'Add 1 to 3 tags on a line at the end, reusing the space’s tags whenever one fits. Change nothing else.',
        menu: 'Fix spelling mistakes and obvious typos only. Keep the wording, punctuation and formatting.',
      },
    },
  },
]
