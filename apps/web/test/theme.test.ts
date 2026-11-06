import { describe, expect, it } from 'vitest'
import { readMode, readTheme } from '../src/theme'

describe('stored theme and mode', () => {
  it('reads the mode an older version kept under notes.theme', () => {
    localStorage.setItem('notes.theme', 'dark')
    expect(readMode()).toBe('dark')
    expect(readTheme()).toBe('amber')
  })

  it('falls back to the default for a theme this build doesn’t have', () => {
    localStorage.setItem('notes.theme', JSON.stringify({ 'data-theme': 'gone' }))
    expect(readTheme()).toBe('amber')
    localStorage.setItem('notes.theme', '{not json')
    expect(readTheme()).toBe('amber')
  })
})
