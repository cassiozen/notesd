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
  it('capitalizes and ends with one full stop', () => {
    expect(sentence('must be at least 8 characters')).toBe('Must be at least 8 characters.')
    expect(sentence('already done.')).toBe('Already done.')
  })
})
