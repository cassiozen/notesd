import { describe, expect, it } from 'vitest'
import { newPasswordErrors, sentence } from './errors'

describe('newPasswordErrors', () => {
  it('counts characters as the server does, not UTF-16 units', () => {
    expect(newPasswordErrors('😀😀😀😀', '😀😀😀😀')).toEqual({ password: 'Use at least 8 characters.' })
    expect(newPasswordErrors('😀😀😀😀😀😀😀😀', '😀😀😀😀😀😀😀😀')).toEqual({})
  })

  it('asks for a matching confirmation once the password is long enough', () => {
    expect(newPasswordErrors('short', 'other')).toEqual({ password: 'Use at least 8 characters.' })
    expect(newPasswordErrors('long enough', 'different')).toEqual({ confirm: "The passwords don't match." })
    expect(newPasswordErrors('long enough', 'long enough')).toEqual({})
  })
})

describe('sentence', () => {
  it('capitalises, and ends with a full stop only when it needs one', () => {
    expect(sentence('must be at least 8 characters')).toBe('Must be at least 8 characters.')
    expect(sentence('model not found')).toBe('Model not found.')
    expect(sentence('already done.')).toBe('Already done.')
    expect(sentence('unauthorized!')).toBe('Unauthorized!')
    expect(sentence('is the key right?')).toBe('Is the key right?')
  })
})
