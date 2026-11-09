import { fireEvent, screen, within } from '@testing-library/react'
import type { UserEvent } from '@testing-library/user-event'
import type { LockKey } from '../src/api/types'
import { fromBase64Url, importKey, passwordKek, unwrapRaw } from '../src/locks/crypto'
import { body } from './helpers'
import type { TestServer } from './server'

// For tests on note pictures and locks. Each test file still mocks ../src/images/prepare itself: vi.mock is hoisted.

// jsdom makes no blob: URLs.
let blobs = 0
URL.createObjectURL ??= () => `blob:test/${++blobs}`
URL.revokeObjectURL ??= () => undefined

export const PNG = fromBase64Url('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH_iZk9HQAAAABJRU5ErkJggg')
export const LOCK_PASSWORD = 'lock pass 123'
export const pngFile = () => new File([PNG], 'dot.png', { type: 'image/png' })

export const imageRef = (content: string) => /\(notesd:image\/([0-9a-f]{64})\?w=640&h=480\)/.exec(content)?.[1]
export const shown = () => [...body().querySelectorAll<HTMLImageElement>('[data-image-view] img')]

export function pick(...files: File[]) {
  fireEvent.change(screen.getByTestId('image-input'), { target: { files } })
}

export async function keyOf(server: TestServer): Promise<CryptoKey> {
  const { keys } = (await server.api<{ keys: LockKey[] }>('GET', '/locks')).body
  const entry = keys.find((k) => k.kind === 'password')
  if (!entry) throw new Error('no password entry')
  const kek = await passwordKek(LOCK_PASSWORD, fromBase64Url(entry.salt), entry.iterations)
  return importKey(await unwrapRaw(kek, entry.wrapped, 'password'))
}

export async function chooseLock(user: UserEvent, item: 'Lock note' | 'Remove lock') {
  await user.click(await screen.findByRole('button', { name: 'More' }))
  await user.click(within(screen.getByRole('menu', { name: 'More' })).getByRole('menuitem', { name: item }))
}

/** Locks the open note, creating the lock password; doesn't wait for the save. */
export async function lockWithNewPassword(user: UserEvent) {
  await chooseLock(user, 'Lock note')
  const dialog = await screen.findByRole('dialog', { name: 'Create a lock password' })
  await user.type(within(dialog).getByLabelText('Lock password'), LOCK_PASSWORD)
  await user.type(within(dialog).getByLabelText('Confirm'), LOCK_PASSWORD)
  await user.click(within(dialog).getByRole('button', { name: 'Create and lock' }))
}

/** An 8×8 JPEG: what the server takes as a profile photo. */
export const JPEG = Uint8Array.from(
  atob(
    '/9j/2wCEABALDA4MChAODQ4SERATGCgaGBYWGDEjJR0oOjM9PDkzODdASFxOQERXRTc4UG1RV19iZ2hnPk1xeXBkeFxlZ2MBERISGBUYLxoaL2NCOEJjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY//AABEIAAgACAMBIgACEQEDEQH/xAGiAAABBQEBAQEBAQAAAAAAAAAAAQIDBAUGBwgJCgsQAAIBAwMCBAMFBQQEAAABfQECAwAEEQUSITFBBhNRYQcicRQygZGhCCNCscEVUtHwJDNicoIJChYXGBkaJSYnKCkqNDU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6g4SFhoeIiYqSk5SVlpeYmZqio6Slpqeoqaqys7S1tre4ubrCw8TFxsfIycrS09TV1tfY2drh4uPk5ebn6Onq8fLz9PX29/j5+gEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoLEQACAQIEBAMEBwUEBAABAncAAQIDEQQFITEGEkFRB2FxEyIygQgUQpGhscEJIzNS8BVictEKFiQ04SXxFxgZGiYnKCkqNTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqCg4SFhoeIiYqSk5SVlpeYmZqio6Slpqeoqaqys7S1tre4ubrCw8TFxsfIycrS09TV1tfY2dri4+Tl5ufo6ery8/T19vf4+fr/2gAMAwEAAhEDEQA/AJqKKK889Q//2Q==',
  ),
  (c) => c.charCodeAt(0),
)
