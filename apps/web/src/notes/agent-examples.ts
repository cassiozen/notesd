import type { AgentDraft } from './agent-rules'

export type AgentExample = { name: string; blurb: string; draft: AgentDraft }

const DIGEST = 'Find the note titled "Digest"'

/** Starting points, from the agents guide. */
export const EXAMPLES: AgentExample[] = [
  {
    name: 'Journal',
    blurb: 'Asks a question to start each entry, and keeps a Digest of what you write about.',
    draft: {
      enabled: true,
      prompt:
        'A journal. Each note is part of an ongoing line of thought. Help me start writing. Never write content for me. You maintain a note called "Digest".',
      menuLabel: '',
      referenceNotes: [],
      triggers: {
        noteCreated: `${DIGEST} and read it if it exists. Then insert one short, open question to get me writing, as a ## heading, followed by an empty line.`,
        newNoteDone: `${DIGEST}, or create it with three sections: Themes, Open questions, Intentions. Update it with what this note adds. Keep each section short; merge, don’t append.`,
        noteDeleted: 'Remove from "Digest" anything that came only from this note.',
        movedOut: 'Remove from "Digest" anything that came only from this note.',
      },
    },
  },
  {
    name: 'Meeting notes',
    blurb: 'Titles and tags each meeting, and lists its action items when you ask.',
    draft: {
      enabled: true,
      prompt: 'Meeting notes. One note per meeting. Keep my words; only add structure.',
      menuLabel: 'List action items',
      referenceNotes: [],
      triggers: {
        newNoteDone:
          'If the note has no title, title it with the meeting’s subject and today’s date. Add a tag for each project it mentions, reusing existing tags. Don’t change anything else.',
        menu: 'Add an "Action items" section at the end, as a task list of what people agreed to do, with who does it. Don’t change anything else.',
      },
    },
  },
  {
    name: 'Recipes',
    blurb: 'Turns ingredients into a checklist in metric, and tags each recipe.',
    draft: {
      enabled: true,
      prompt: 'Recipes. Metric quantities.',
      menuLabel: '',
      referenceNotes: [],
      triggers: {
        newNoteDone:
          'Rewrite the ingredients as a task list with metric quantities. Add a tag for the cuisine and one for the main ingredient. Leave the method as written.',
      },
    },
  },
  {
    name: 'Auto-tags',
    blurb: 'Tags new notes from a small, stable set, and fixes typos when you ask.',
    draft: {
      enabled: true,
      prompt: 'Keep notes findable with a small, stable set of tags. Prefer existing tags.',
      menuLabel: 'Fix typos',
      referenceNotes: [],
      triggers: {
        newNoteDone:
          'Add one line of 1–3 tags at the end of the note. Check for existing tags in the space first and reuse them when they fit. Don’t change anything else.',
        menu: 'Fix spelling and obvious typos only. Don’t change wording, punctuation style or formatting.',
      },
    },
  },
]
