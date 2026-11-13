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
  /** The account that last changed it (§7.9); absent for changes from before it was recorded. */
  updatedBy?: number
  /** On a tombstone a move between files left: the space the note went to (§7.9). */
  movedTo?: string
}

export type ListItem = Pick<Note, 'id' | 'title' | 'revision' | 'createdAt' | 'updatedAt' | 'spaceId' | 'updatedBy'> & {
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
  /** On, in the server's order (§7.8); [] for none. */
  traits: SpaceTrait[]
  /** Who's in it, when it's shared (§7.9); absent for the account's own. */
  shared?: SharedSpace
  /**
   * This app's alone: traits being turned off here, gone from `traits` before the server confirms, but still
   * exposing the space to the lock rules (§7.8) until it has.
   */
  leaving?: SpaceTrait[]
}

/** An account as others see it: its username, and the name and photo (avatar: its version, '' for none) it chose. */
export type Account = { id: number; username: string; displayName: string; avatar: string }

/** A shared space's people as this account sees them (§7.9). */
export type SharedSpace = {
  role: 'owner' | 'member'
  /** The owner first. */
  members: Account[]
  /** Waiting invitations; the owner's only, absent for members. */
  invites?: ({ userId: number } & Omit<Account, 'id'> & { id: string })[]
}

/** An invitation into someone else's space, waiting for this account (§7.9). */
export type Invite = {
  id: string
  spaceId: string
  spaceName: string
  spaceIcon: string
  from: Account
  createdAt: number
}

/** What other members changed in a shared space since this device last showed it (§7.9). */
export type SpaceNews = {
  spaceId: string
  /** Notes changed. */
  count: number
  /** count is a floor: online, only the list's first pages are read, and more remained. */
  atLeast?: boolean
  /** Who changed them (their chosen name, or else username), latest first. */
  names: string[]
  latestAt: number
}

/** A feature on for a whole space (§7.8), worded by the server. */
export type SpaceTrait = {
  key: string
  label: string
  summary: string
  /** Who else can see the space's notes; absent when they stay with the account. */
  exposure?: string
  /** Who `exposure` reaches, as stable keys ("user:3", "public"): what a move compares. */
  audience?: string[]
  /** Something wrong with it (publishing failed): shown in the danger colour. */
  problem?: string
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
  /** The account's id, as notes' updatedBy and shared spaces' members name it; absent from servers before sharing. */
  userId?: number
  username: string
  /** The name chosen to show instead of the username; '' for none. */
  displayName: string
  /** The photo's version, for avatarURL; '' for none. */
  avatar: string
  /** May change server-wide settings and manage accounts. */
  isAdmin: boolean
  csrfToken: string
  /** The server's build version, such as v0.11.0. */
  serverVersion?: string
}

/** An account, as an admin sees it in Settings. */
export type Person = {
  id: number
  username: string
  /** As on SessionInfo. */
  displayName: string
  avatar: string
  isAdmin: boolean
  createdAt: number
  /** Invited: no password chosen yet. */
  pending: boolean
  /** When its setup link stops working; absent without one. */
  linkExpiresAt?: number
}

/** A one-time link's secret; the app builds the URL around it (people-link.ts). */
export type SetupLink = { token: string; expiresAt: number }

export type ErrorBody = {
  error: string
  message?: string
  field?: string
  current?: Note
  /** space_not_empty: the notes still in the space; space_has_locked: its locked notes. */
  count?: number
  /** upgrade_required: the oldest app version the server takes. */
  minVersion?: string
}

/** The server's Resend settings for emailing notes; the key itself is never sent back. Only admins may change them. */
export type EmailSettings = { configured: boolean; from: string; keyHint: string; editable: boolean }

/** GET /settings/ai. Server-wide; editable when this user is an admin. */
export type AiSettings = {
  configured: boolean
  baseUrl: string
  model: string
  keyHint: string
  dailyTokenCap: number
  editable: boolean
}

/** PUT /settings/ai and POST /settings/ai/test. Omit apiKey to use the saved one. */
export type AiSettingsInput = { baseUrl: string; model: string; apiKey?: string; dailyTokenCap?: number }

/** POST /settings/ai/models: the address and a key, or none for the saved one. */
export type AiModelsInput = { baseUrl: string; apiKey?: string }

/** The provider's models, sorted; on OpenRouter only those that call tools. */
export type AiModels = { models: { id: string }[] }

/** A passed POST /settings/ai/test. model is as the provider named it. */
export type AiTestResult = { ok: true; model: string; latencyMs: number }

/** GET /update. latest, newer and requested only when the server can update itself. */
export type UpdateStatus = {
  current: string
  enabled: boolean
  /** Only admins can start an update. */
  editable: boolean
  latest?: string
  newer?: boolean
  requested?: boolean
  /** A release of a newer major version: installed by hand, never by the server itself. */
  majorAvailable?: string
}

/**
 * How a keyring entry's kek is derived from the password or passkey secret. Open-ended:
 * a newer notesd may write a name this one doesn't know.
 */
export type Kdf = { name: string; iterations?: number; [param: string]: unknown }

/** One wrapped copy of the locked-notes key (§10.1). The server can't open it. */
export type LockKey = {
  id: string
  kind: 'password' | 'recovery' | 'passkey'
  credentialId?: string
  salt: string
  /** pbkdf2-sha256's, repeated for apps before kdf; 0 for a passkey. */
  iterations: number
  /** Null or absent: written before 1.0, implied by kind. */
  kdf?: Kdf | null
  wrapped: string
  createdAt: number
}
