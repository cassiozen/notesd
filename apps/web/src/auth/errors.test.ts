import { describe, expect, it } from 'vitest'
import { sentence } from './errors'

describe('sentence', () => {
  it('capitalises, and ends with a full stop only when it needs one', () => {
    expect(sentence('model not found')).toBe('Model not found.')
    expect(sentence('already done.')).toBe('Already done.')
    expect(sentence('unauthorized!')).toBe('Unauthorized!')
    expect(sentence('is the key right?')).toBe('Is the key right?')
  })
})
