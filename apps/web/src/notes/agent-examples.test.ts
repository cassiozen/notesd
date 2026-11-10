import { describe, expect, it } from 'vitest'
import { EXAMPLES, hasIdea, readsNeeded, withIdea } from './agent-examples'
import { emptyAgent } from './agent-rules'
import { titleOf } from './note-titles'

const tidy = EXAMPLES.find((e) => e.name === 'Tidy tags')
const journal = EXAMPLES.find((e) => e.name === 'Journal')

describe('taking an example’s rule', () => {
  it('keeps a command name of mine, and counts as taken then', () => {
    const menu = tidy?.rules.menu
    if (!menu) throw new Error('no menu rule')
    const mine = { ...emptyAgent, menuLabel: 'Typos!', triggers: { menu: 'Other.' } }
    const taken = withIdea(mine, 'menu', menu)
    expect(taken.menuLabel).toBe('Typos!')
    expect(hasIdea(taken, 'menu', menu)).toBe(true)
    // Another example's name gives way to this one's.
    expect(withIdea({ ...mine, menuLabel: 'Scale to 2 servings' }, 'menu', menu).menuLabel).toBe('Fix typos')
  })

  it('needs the notes its rule reads, until the rule is edited', () => {
    const opened = journal?.rules.noteCreated
    if (!opened) throw new Error('no rule')
    const d = { ...emptyAgent, triggers: { noteCreated: opened.prompt } }
    expect(readsNeeded(d)).toEqual(['Digest'])
    expect(readsNeeded({ ...d, triggers: { noteCreated: opened.prompt + ' Briefly.' } })).toEqual([])
    // Moved to another moment, it still needs them.
    expect(readsNeeded({ ...d, triggers: { editStarted: opened.prompt } })).toEqual(['Digest'])
  })
})

describe('titleOf', () => {
  it('is what the server keeps: trimmed, one space at a time', () => {
    expect(titleOf('  Weekly \t  digest ')).toBe('Weekly digest')
  })
})
