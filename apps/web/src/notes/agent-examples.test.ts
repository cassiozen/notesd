import { describe, expect, it } from 'vitest'
import { EXAMPLES, isBlank, withExample } from './agent-examples'
import { emptyAgent } from './agent-rules'
import { titleOf } from './note-titles'

describe('starting from an example', () => {
  it('takes its purpose, rules and command name, keeping the switch and the notes it reads', () => {
    const tidy = EXAMPLES.find((e) => e.name === 'Tidy tags')
    if (!tidy) throw new Error('no Tidy tags')
    const d = { ...emptyAgent, enabled: true, referenceNotes: ['Glossary'] }
    expect(isBlank(d)).toBe(true)
    const taken = withExample(d, tidy)
    expect(taken).toMatchObject({ enabled: true, prompt: tidy.prompt, menuLabel: 'Fix typos', referenceNotes: ['Glossary'] })
    expect(Object.keys(taken.triggers).toSorted()).toEqual(['menu', 'newNoteDone'])
    expect(isBlank(taken)).toBe(false)
  })

  it('isn’t offered over a purpose or a rule', () => {
    expect(isBlank({ ...emptyAgent, prompt: ' ' })).toBe(true)
    expect(isBlank({ ...emptyAgent, prompt: 'Mine.' })).toBe(false)
    expect(isBlank({ ...emptyAgent, triggers: { menu: '' } })).toBe(false)
  })
})

describe('titleOf', () => {
  it('is what the server keeps: trimmed, one space at a time', () => {
    expect(titleOf('  Weekly \t  digest ')).toBe('Weekly digest')
  })
})
