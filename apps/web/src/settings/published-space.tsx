import { useEffect, useId, useState, type ReactNode } from 'react'
import { ApiError } from '../api/client'
import { publishing, type PublishSettings } from '../api/spaces'
import type { Space } from '../api/types'
import { genericMessage } from '../auth/errors'
import { reloadSpaces } from '../notes/spaces'
import { Button } from '../ui/button'
import { useCopy } from '../ui/copy'
import { InlineConfirm } from '../ui/inline-confirm'
import info from './space-info.module.css'
import styles from './published-space.module.css'

// Publishing a space (architecture §7.10), as Space info's Published section: its address, its own stylesheet,
// and turning it on and off, all in place.

type Failure = { field?: 'handle' | 'css'; message: string }

/** What went wrong, in words, and which field it was about. */
export function publishError(err: unknown): Failure {
  if (err instanceof ApiError) {
    switch (err.code) {
      case 'space_has_locked':
        if (err.count === 1) return { message: 'Unlock its locked note to publish it.' }
        return { message: err.count === undefined ? 'Unlock its locked notes to publish it.' : `Unlock its ${err.count} locked notes to publish it.` }
      case 'space_default':
        return { message: 'This space can’t be published. Publish another one.' }
      case 'handle_taken':
        return { field: 'handle', message: 'Another space has that address. Try another.' }
      case 'not_space_owner':
        return { message: 'Only the space’s owner can do that.' }
      case 'invalid_request':
        if (err.field === 'handle') {
          return { field: 'handle', message: 'Use 1–64 lowercase letters, digits and hyphens, not starting or ending with a hyphen.' }
        }
        if (err.field === 'css') return { field: 'css', message: `Custom CSS ${err.message}.` }
    }
  }
  return { message: genericMessage(err) }
}

/** The address without its scheme or handle: "notes.example.com/@". */
const prefix = (s: PublishSettings) => `${s.origin.replace(/^https?:\/\//, '')}/@`

/**
 * Whether the space is a website anyone can read. Off, its owner publishes it; on, the address (its handle edited
 * in place), its custom CSS, and Stop publishing. A member sees where it is.
 */
export function PublishedSection({ space }: { space: Space }) {
  const headingId = useId()
  const trait = space.traits.find((t) => t.key === 'published')
  const owner = space.shared?.role !== 'member'
  const [settings, setSettings] = useState<PublishSettings | null>(null)
  const [busy, setBusy] = useState<'publish' | 'handle' | 'css' | 'stop' | null>(null)
  const [failure, setFailure] = useState<Failure>()
  const [stopping, setStopping] = useState(false)

  // Read again when the trait changes: published, moved or taken down here or on another device.
  const summary = trait?.summary
  useEffect(() => {
    if (!summary || !owner) return
    let live = true
    publishing.get(space.id).then(
      (s) => live && setSettings(s),
      // not_published: taken down meanwhile, and the list hasn't said so yet.
      (err: unknown) => live && !(err instanceof ApiError && err.code === 'not_published') && setFailure(publishError(err)),
    )
    return () => {
      live = false
    }
  }, [space.id, summary, owner])

  async function run(key: NonNullable<typeof busy>, work: () => Promise<PublishSettings | undefined>): Promise<boolean> {
    setBusy(key)
    setFailure(undefined)
    try {
      const next = await work()
      setSettings(next ?? null)
      // The trait, its glyph and the lock rules (§7.8) come with the list.
      await reloadSpaces()
      return true
    } catch (err) {
      setFailure(publishError(err))
      return false
    } finally {
      setBusy(null)
    }
  }

  const error = (field?: Failure['field']) =>
    failure &&
    failure.field === field && (
      <p role="alert" className={info.error}>
        {failure.message}
      </p>
    )

  return (
    <section className={info.section} aria-labelledby={headingId}>
      <h3 id={headingId} className={info.caption}>
        Published
      </h3>
      <div className={info.group}>
        {trait?.problem && <p className={styles.problem}>{trait.problem}</p>}
        {!trait ? (
          <div className={styles.off}>
            <p className={styles.lead}>
              Publish {space.name} as a website anyone can read: each note with a title and some text is a post, with pages for its tags and a search.
              Its address comes from the space’s name, and you can change it after.
            </p>
            <Button size="sm" variant="accent" busy={busy === 'publish'} disabled={busy !== null} onClick={() => void run('publish', () => publishing.put(space.id, { origin: location.origin }))}>
              Publish
            </Button>
          </div>
        ) : !owner ? (
          <p className={styles.lead}>{trait.summary}</p>
        ) : !settings ? (
          <p className={styles.lead} aria-busy="true">
            {trait.summary}
          </p>
        ) : (
          <>
            <Address
              settings={settings}
              busy={busy}
              onChange={(handle) => run('handle', () => publishing.put(space.id, { handle }))}
              onEdit={() => failure?.field === 'handle' && setFailure(undefined)}
            />
            {error('handle')}
            <Stylesheet
              key={settings.css}
              css={settings.css}
              busy={busy}
              onSave={(css) => run('css', () => publishing.put(space.id, { css }))}
              error={error('css')}
            />
          </>
        )}
      </div>
      {error()}
      {trait && owner && (
        <div className={info.footer}>
          <Button size="sm" variant="danger-text" disabled={busy !== null} aria-expanded={stopping} onClick={() => setStopping(true)}>
            Stop publishing…
          </Button>
          {stopping && (
            <InlineConfirm
              title={`Stop publishing ${space.name}?`}
              action="Stop publishing"
              busy={busy === 'stop'}
              onConfirm={() =>
                void run('stop', async () => {
                  await publishing.remove(space.id)
                  return undefined
                }).then(() => setStopping(false))
              }
              onCancel={() => setStopping(false)}
            >
              The site goes offline. Its address stays this space’s, for when you publish it again.
            </InlineConfirm>
          )}
        </div>
      )}
    </section>
  )
}

/**
 * The site's address, its handle edited where it's shown: Enter or leaving the field asks first (links to the old
 * address stop working), Escape puts it back.
 */
function Address({
  settings,
  busy,
  onChange,
  onEdit,
}: {
  settings: PublishSettings
  busy: string | null
  onChange: (handle: string) => Promise<boolean>
  onEdit: () => void
}) {
  const [draft, setDraft] = useState<string | null>(null)
  const [asking, setAsking] = useState<string | null>(null)
  const [copied, copy] = useCopy()
  const value = draft ?? settings.handle

  function commit() {
    const handle = draft?.trim()
    if (handle === undefined) return
    if (!handle || handle === settings.handle) setDraft(null)
    else setAsking(handle)
  }

  return (
    <div className={styles.row}>
      <div className={styles.line}>
        <label className={styles.address}>
          <span className={styles.prefix}>{prefix(settings)}</span>
          <input
            className={styles.handle}
            aria-label="Address"
            autoComplete="off"
            autoCapitalize="none"
            spellCheck={false}
            maxLength={64}
            size={Math.max(value.length, 1)}
            value={value}
            disabled={busy === 'handle'}
            onChange={(e) => {
              setDraft(e.target.value.toLowerCase())
              onEdit()
            }}
            onBlur={commit}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                e.preventDefault()
                e.currentTarget.blur()
              } else if (e.key === 'Escape' && draft !== null) {
                e.preventDefault()
                e.stopPropagation()
                setDraft(null)
              }
            }}
          />
        </label>
        <span className={styles.actions}>
          <a className={styles.open} href={settings.url} target="_blank" rel="noopener noreferrer">
            Open
          </a>
          <Button size="sm" variant="ghost" onClick={() => void copy(settings.url)}>
            {copied ? 'Copied' : 'Copy'}
          </Button>
        </span>
      </div>
      {asking !== null && (
        <InlineConfirm
          title={`Move the site to ${prefix(settings)}${asking}?`}
          action="Change address"
          busy={busy === 'handle'}
          onConfirm={() =>
            void onChange(asking).then((ok) => {
              setAsking(null)
              if (ok) setDraft(null)
            })
          }
          onCancel={() => {
            setAsking(null)
            setDraft(null)
          }}
        >
          Links to the current address will stop working.
        </InlineConfirm>
      )}
    </div>
  )
}

/** The space's own CSS, added after the site's: folded away until wanted, saved when Save is pressed. */
function Stylesheet({
  css,
  busy,
  onSave,
  error,
}: {
  css: string
  busy: string | null
  onSave: (css: string) => Promise<boolean>
  error: ReactNode
}) {
  const hintId = useId()
  const [draft, setDraft] = useState(css)
  return (
    <details className={styles.row} open={css !== '' || undefined}>
      <summary className={styles.summary}>Custom CSS</summary>
      <div className={styles.css}>
        <textarea
          className={styles.code}
          aria-label="Custom CSS"
          aria-describedby={hintId}
          spellCheck={false}
          autoCapitalize="none"
          rows={8}
          wrap="off"
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
        />
        <p id={hintId} className={styles.hint}>
          Added after the site’s own styles, so it wins without <code>!important</code>. Most changes are a variable, such as{' '}
          <code>:root {'{'} --accent: teal {'}'}</code>.
        </p>
        {error}
        <div className={styles.save}>
          <Button size="sm" busy={busy === 'css'} disabled={busy !== null || draft === css} onClick={() => void onSave(draft)}>
            Save
          </Button>
        </div>
      </div>
    </details>
  )
}
