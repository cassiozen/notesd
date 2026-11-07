import { act, renderHook, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { settings } from '../src/api/settings'
import type { AiSettings, EmailSettings } from '../src/api/types'
import { forgetAiSettings, useAiSettings } from '../src/notes/ai-settings'
import { forgetEmailSettings, useEmailSettings } from '../src/notes/email-settings'

describe('account settings stores', () => {
  afterEach(() => vi.restoreAllMocks())

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

  it('fetch again when a new session forgets them mid-fetch, ignoring the old answer', async () => {
    const answers: ((s: AiSettings) => void)[] = []
    vi.spyOn(settings, 'ai').mockImplementation(() => new Promise((r) => answers.push(r)))
    const ai = renderHook(() => useAiSettings())
    await waitFor(() => expect(answers).toHaveLength(1))

    act(() => forgetAiSettings())
    await waitFor(() => expect(answers).toHaveLength(2))
    await act(async () => answers[1]?.({ model: 'new' } as AiSettings))
    expect(ai.result.current?.model).toBe('new')

    await act(async () => answers[0]?.({ model: 'old' } as AiSettings))
    expect(ai.result.current?.model).toBe('new')
  })
})
