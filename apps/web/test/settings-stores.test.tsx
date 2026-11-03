import { act, renderHook, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { settings } from '../src/api/settings'
import type { AiSettings, EmailSettings } from '../src/api/types'
import { forgetAiSettings, useAiSettings } from '../src/notes/ai-settings'
import { forgetEmailSettings, useEmailSettings } from '../src/notes/email-settings'

describe('account settings stores', () => {
  it('fetch again for screens left open when a new session forgets them', async () => {
    let n = 0
    let m = 0
    vi.spyOn(settings, 'ai').mockImplementation(async () => ({ model: `m${++n}` }) as AiSettings)
    vi.spyOn(settings, 'email').mockImplementation(async () => ({ from: `f${++m}` }) as EmailSettings)
    const ai = renderHook(() => useAiSettings())
    const email = renderHook(() => useEmailSettings())
    await waitFor(() => expect(ai.result.current?.model).toBe('m1'))
    await waitFor(() => expect(email.result.current).not.toBeNull())

    act(() => {
      forgetAiSettings()
      forgetEmailSettings()
    })
    await waitFor(() => expect(ai.result.current?.model).toBe('m2'))
    await waitFor(() => expect(email.result.current?.from).toBe('f2'))
  })
})
