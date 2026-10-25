export type Note = {
  id: string
  title: string
  content: string
  revision: number
  changeSeq: number
  /** Unix milliseconds, like every timestamp from the API. */
  createdAt: number
  updatedAt: number
  deletedAt: number | null
  conflictOf?: string
  /** The space the note is in (architecture §7.7). */
  spaceId: string
}

export type ListItem = Pick<Note, 'id' | 'title' | 'revision' | 'createdAt' | 'updatedAt' | 'spaceId'> & {
  preview: string
  /** The body is encrypted (§10.1): no preview. */
  locked: boolean
}

export type NoteList = {
  notes: ListItem[]
  nextCursor: string
  hasMore: boolean
  /** Every live note matching the filter, not just this page. */
  total: number
}

/** A tag in live notes (architecture §7.5); parents of nested tags included. */
export type TagCount = {
  key: string
  name: string
  /** Notes with this tag or one nested under it. */
  count: number
}

/** Narrows listing and search to a tag key (with its nested tags), to untagged notes or to locked ones. */
export type NoteFilter = { tag: string } | { untagged: true } | { locked: true } | null

export type SearchResult = {
  id: string
  title: string
  /** HTML-escaped text with matches wrapped in <mark>. */
  excerpt: string
  updatedAt: number
  /** Matched on the title only; the body is encrypted. */
  locked: boolean
  spaceId: string
}

/** Searching one space, or every space but one (the "more in other spaces" results). */
export type SearchScope = { space: string } | { exceptSpace: string }

/** A space (§7.7): a separate context with its own notes. */
export type Space = {
  id: string
  name: string
  /** A key into the apps' icons (notes/space-icons.ts); '' for the default one. */
  icon: string
  createdAt: number
  noteCount: number
}

export type MutationResult = {
  id: string
  status: 'created' | 'updated' | 'unchanged' | 'deleted' | 'already_deleted'
  revision: number
  changeSeq: number
  updatedAt: number
  deletedAt?: number
  /** Creates and updates: the space the note is in now, the default if the one sent is gone. */
  spaceId?: string
}

export type Session = {
  id: string
  kind: 'web' | 'device'
  name: string
  createdAt: number
  lastUsedAt: number
}

export type SessionInfo = {
  session: Session
  username: string
  csrfToken: string
  /** The server's build version, such as v0.11.0. */
  serverVersion?: string
}

export type ErrorBody = {
  error: string
  message?: string
  field?: string
  current?: Note
  /** space_not_empty: the notes still in the space. */
  count?: number
}

/** The server's Resend settings for emailing notes; the key itself is never sent back. */
export type EmailSettings = { configured: boolean; from: string; keyHint: string }

/** GET /update. latest, newer and requested only when the server can update itself. */
export type UpdateStatus = {
  current: string
  enabled: boolean
  /** Only admins can start an update. */
  editable: boolean
  latest?: string
  newer?: boolean
  requested?: boolean
}

/** One wrapped copy of the locked-notes key (§10.1). The server can't open it. */
export type LockKey = {
  id: string
  kind: 'password' | 'recovery' | 'passkey'
  credentialId?: string
  salt: string
  iterations: number
  wrapped: string
  createdAt: number
}
