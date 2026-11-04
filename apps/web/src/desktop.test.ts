import { describe, expect, it, onTestFinished } from 'vitest'
import { deviceName } from './api/user-agent'
import { dragRegion } from './desktop'
import { isInstalledApp } from './install'

describe('in a browser', () => {
  it('is itself', () => {
    expect(deviceName()).not.toMatch(/notesd/)
    expect(dragRegion('deep')).toEqual({})
    expect(isInstalledApp()).toBe(false)
  })
})

describe('in the macOS app', () => {
  it('names its sessions after the app, counts as installed, and marks where the window drags from', () => {
    document.documentElement.dataset.desktop = 'macos'
    onTestFinished(() => void delete document.documentElement.dataset.desktop)
    expect(deviceName()).toBe('notesd for macOS')
    expect(isInstalledApp()).toBe(true)
    expect(dragRegion('self')).toEqual({ 'data-tauri-drag-region': 'true' })
    expect(dragRegion('deep')).toEqual({ 'data-tauri-drag-region': 'deep' })
  })
})
