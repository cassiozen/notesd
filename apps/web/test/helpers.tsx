import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, onTestFinished } from 'vitest'
import type { Note, NoteList } from '../src/api/types'
import { App } from '../src/app'
import { localSource } from '../src/notes/local-source'
import { onlineSource } from '../src/notes/online-source'
import { setNotesSource } from '../src/notes/source'
import { inProcessEngine, signIn } from './engine/helpers'
import { createOwner, startServer, type TestServer } from './server'

export type Session = { id: string }

/** One pane at a time, as on a phone. Reset after each test. */
export function phoneLayout() {
  globalThis.layout = 'phone'
}

/** List and note side by side, without room for the tags sidebar. Reset after each test. */
export function mediumLayout() {
  globalThis.layout = 'medium'
}

/**
 * A server with an owner account and the app signed in to it. seed runs
 * before the app loads, so the list starts with those notes (and, side by
 * side, the newest one is open). onDevice: the screens read this device's
 * engine (in memory), already synced with the seed.
 */
export async function signedIn({
  path = '/',
  seed,
  resendUrl,
  onDevice = false,
}: { path?: string; seed?: (server: TestServer) => Promise<void>; resendUrl?: string; onDevice?: boolean } = {}) {
  const server = await startServer({ ...(resendUrl && { resendUrl }) })
  const { username, password } = await createOwner(server)
  // The other client (curl, a phone) signs in too, for edits from "another device".
  await server.api('POST', '/auth/login', { username, password, client: 'web' })
  await seed?.(server)
  window.history.replaceState(null, '', path)
  const user = userEvent.setup()
  let engine: ReturnType<typeof inProcessEngine> | undefined
  if (onDevice) {
    engine = inProcessEngine(username, await signIn(username, password))
    const e = engine
    // The first sync starts on open.
    await waitFor(async () => expect((await e.call('status')).kind).toBe('Synced'), { timeout: 5000 })
    setNotesSource(localSource(engine))
    onTestFinished(() => setNotesSource(onlineSource))
  }
  render(<App />)
  if (!onDevice) {
    await user.type(await screen.findByLabelText('Username'), username)
    await user.type(screen.getByLabelText('Password'), `${password}{Enter}`)
  }
  // The footer count shows once the first page is in.
  await within(await screen.findByRole('complementary', { name: 'Notes' })).findByText(/^\d+ notes?$/)
  return { server, user, password, engine }
}

let opSeq = 0
export const op = () => `test-op-${++opSeq}`

export async function createNote(server: TestServer, title: string, content = '') {
  const id = crypto.randomUUID()
  const res = await server.api('POST', '/notes', { operationId: op(), id, title, content })
  expect(res.status).toBe(201)
  return id
}

export async function getNote(server: TestServer, id: string) {
  return (await server.api<Note>('GET', `/notes/${id}`)).body
}

export async function listNotes(server: TestServer) {
  return (await server.api<NoteList>('GET', '/notes?limit=100')).body.notes
}

/** Edits a note as another device would. */
export async function editElsewhere(server: TestServer, id: string, patch: Partial<Pick<Note, 'title' | 'content'>>) {
  const note = await getNote(server, id)
  const res = await server.api('PUT', `/notes/${id}`, {
    operationId: op(),
    baseRevision: note.revision,
    title: patch.title ?? note.title,
    content: patch.content ?? note.content,
  })
  expect(res.status).toBe(200)
}

export const list = () => screen.getByRole('complementary', { name: 'Notes' })
export const body = () => screen.getByRole('textbox', { name: 'Note' })
/** The note's first line. */
export const noteTitle = () => body().querySelector('[data-title]')?.textContent
/** Everything after the first line. */
export const bodyText = () =>
  [...body().children]
    .slice(1)
    .map((el) => el.textContent)
    .join('')

/** Puts the caret at the end of el (jsdom has no layout, so a click can't place it). */
function caretAtEnd(el: Node) {
  body().focus()
  const range = document.createRange()
  range.selectNodeContents(el)
  range.collapse(false)
  const sel = window.getSelection()
  sel?.removeAllRanges()
  sel?.addRange(range)
  document.dispatchEvent(new Event('selectionchange'))
}

/** Types at the end of the note. */
export async function typeAtEnd(user: ReturnType<typeof userEvent.setup>, text: string) {
  caretAtEnd(body())
  await user.type(body(), text, { skipClick: true })
}

/** Types at the end of the first line. */
export async function typeInTitle(user: ReturnType<typeof userEvent.setup>, text: string) {
  const title = body().querySelector('[data-title]')
  if (!title) throw new Error('no title line')
  caretAtEnd(title)
  await user.type(body(), text, { skipClick: true })
}

export const newNoteButton = () => screen.getByRole('button', { name: 'New note' })
export const saved = () => screen.findByText('Saved', {}, { timeout: 3000 })

/** The list row for a note, by title. */
export const row = (title: string) => within(list()).getByRole('link', { name: new RegExp(`^${title}`) })

export async function openNote(user: ReturnType<typeof userEvent.setup>, title: string) {
  await user.click(await within(list()).findByRole('link', { name: new RegExp(`^${title}`) }))
  await waitFor(() => expect(noteTitle()).toBe(title))
}

/** Selects the first occurrence of text in the note. */
export function selectInBody(text: string) {
  const el = body()
  el.focus()
  const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT)
  for (let node = walker.nextNode(); node; node = walker.nextNode()) {
    const at = node.textContent?.indexOf(text) ?? -1
    if (at < 0) continue
    const range = document.createRange()
    range.setStart(node, at)
    range.setEnd(node, at + text.length)
    const sel = window.getSelection()
    sel?.removeAllRanges()
    sel?.addRange(range)
    document.dispatchEvent(new Event('selectionchange'))
    return
  }
  throw new Error(`"${text}" not in the note`)
}

/** Desktop: Delete lives at the end of the note's More menu. */
export async function deleteNote(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: 'More' }))
  await user.click(within(screen.getByRole('menu', { name: 'More' })).getByRole('menuitem', { name: 'Delete note' }))
}

/** Opens Settings at one of its categories (Server when none). */
export async function openSettings(user: ReturnType<typeof userEvent.setup>, category?: string) {
  await user.click(screen.getByRole('button', { name: 'Settings' }))
  const nav = await screen.findByRole('navigation', { name: 'Settings categories' })
  if (category) await user.click(within(nav).getByRole('link', { name: new RegExp(`^${category}`) }))
}
