import { afterEach, describe, expect, it } from 'vitest'
import { trackModality } from './modality'

const root = document.documentElement
const keyboard = () => root.hasAttribute('data-keyboard')
let stop: (() => void) | undefined

afterEach(() => {
  stop?.()
  stop = undefined
})

describe('trackModality', () => {
  it('starts as pointer', () => {
    stop = trackModality()
    expect(keyboard()).toBe(false)
  })

  it('a key marks keyboard, a pointer clears it', () => {
    stop = trackModality()
    document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', bubbles: true }))
    expect(keyboard()).toBe(true)
    document.body.dispatchEvent(new Event('pointerdown', { bubbles: true }))
    expect(keyboard()).toBe(false)
  })

  it('a bare modifier is not keyboard use', () => {
    stop = trackModality()
    document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'Meta', bubbles: true }))
    expect(keyboard()).toBe(false)
  })

  it('sees keys a handler stops from bubbling', () => {
    stop = trackModality()
    const el = document.createElement('button')
    document.body.append(el)
    el.addEventListener('keydown', (e) => e.stopPropagation())
    el.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }))
    expect(keyboard()).toBe(true)
    el.remove()
  })

  it('stopping clears the mark and the listeners', () => {
    stop = trackModality()
    document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'a', bubbles: true }))
    stop()
    stop = undefined
    expect(keyboard()).toBe(false)
    document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'a', bubbles: true }))
    expect(keyboard()).toBe(false)
  })
})
