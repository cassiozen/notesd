import { request } from './client'
import { filePath } from './files'
import type { MutationResult, Note, NoteFilter, NoteList, SearchResult, SearchScope, TagCount } from './types'

export const SEARCH_PAGE = 30

/** Under [file], a filePath: the account's ('') or a shared space's. */
const path = (id: string, file = '') => `${file}/notes/${encodeURIComponent(id)}`

const filterParams = (filter: NoteFilter) =>
  !filter ? '' : 'tag' in filter ? `&tag=${encodeURIComponent(filter.tag)}` : 'locked' in filter ? '&locked=true' : '&untagged=true'

const scopeParams = (scope: SearchScope) =>
  'space' in scope ? `&space=${encodeURIComponent(scope.space)}` : `&exceptSpace=${encodeURIComponent(scope.exceptSpace)}`

/** imageIds lists a locked note's images (§7.6); the server reads a plain note's from its content. */
export type CreateNote = {
  operationId: string
  id: string
  title: string
  content: string
  conflictOf?: string
  spaceId?: string
  imageIds?: string[]
}
export type UpdateNote = {
  operationId: string
  baseRevision: number
  title: string
  content: string
  spaceId?: string
  imageIds?: string[]
}

/** A change of space between files (§7.9): the note keeps its revision. */
export type MoveNote = { operationId: string; baseRevision: number; spaceId: string }

/** A shared space's notes are in its own file: each call goes to the file of the space it names. */
export const notes = {
  list: (filter: NoteFilter, space: string, cursor?: string, signal?: AbortSignal) =>
    request<NoteList>(
      'GET',
      `${filePath(space)}/notes?limit=50${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}${filterParams(filter)}${scopeParams({ space })}`,
      undefined,
      { ...(signal && { signal }) },
    ),
  /** [file]: a filePath; the account's by default. */
  get: (id: string, file = '') => request<Note>('GET', path(id, file)),
  /** In the file of the space it goes in. */
  create: (body: CreateNote, keepalive = false) =>
    request<MutationResult>('POST', `${filePath(body.spaceId ?? 'default')}/notes`, body, { keepalive }),
  /** [at]: the space whose file has the note now. */
  update: (id: string, at: string, body: UpdateNote, keepalive = false) =>
    request<MutationResult>('PUT', path(id, filePath(at)), body, { keepalive }),
  move: (id: string, at: string, body: MoveNote) => request<MutationResult>('POST', `${path(id, filePath(at))}/move`, body),
  remove: (id: string, at: string, body: { operationId: string; baseRevision: number }, keepalive = false) =>
    request<MutationResult>('DELETE', path(id, filePath(at)), body, { keepalive }),
  /**
   * In [file] (a filePath, the account's by default). In one space, the first
   * page also counts the matches in every other space, in any file.
   */
  search: (q: string, filter: NoteFilter, scope: SearchScope, offset: number, signal: AbortSignal, file = '') =>
    request<{ results: SearchResult[]; otherSpaces?: number }>(
      'GET',
      `${file}/search?q=${encodeURIComponent(q)}&limit=${SEARCH_PAGE}&offset=${offset}${filterParams(filter)}${scopeParams(scope)}`,
      undefined,
      { signal },
    ),
  /** Emails the server's copy, which must be at revision (409 stale_revision otherwise). */
  email: (id: string, at: string, to: string[], revision: number) =>
    request<{ id: string }>('POST', `${path(id, filePath(at))}/email`, { to, revision }),
  filters: (space: string, signal?: AbortSignal) =>
    request<{ tags: TagCount[]; locked: number }>('GET', `${filePath(space)}/filters?space=${encodeURIComponent(space)}`, undefined, {
      ...(signal && { signal }),
    }),
}
