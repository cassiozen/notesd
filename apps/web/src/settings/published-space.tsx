import { useEffect, useId, useState, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { ApiError } from '../api/client'
import { publishing, type PublishSettings } from '../api/spaces'
import type { Space } from '../api/types'
import { genericMessage } from '../auth/errors'
import { catchUpSpaces, expectPublished, unexpectPublished } from '../notes/spaces'
import { PublishedGlyph } from '../notes/trait-glyphs'
import type { TraitPage } from '../notes/traits'
import { Button } from '../ui/button'
import { useCopy } from '../ui/copy'
import { InlineConfirm } from '../ui/inline-confirm'
import { PageHead, SectionRow } from './section-row'
import settingsStyles from './settings.module.css'
import info from './space-info.module.css'
import styles from './published-space.module.css'

// Publishing a space (architecture §7.10), as Space info's Website section: a row saying where it is, and its page
// with turning it on and off, its address, its custom CSS and its own domain.

type Failure = { field?: 'handle' | 'css' | 'domain'; message: string }

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
        if (err.field === 'domain') return { field: 'domain', message: 'Use a domain name, such as blog.example.com.' }
    }
  }
  return { message: genericMessage(err) }
}

/** The address without its scheme or handle: "notes.example.com/@". */
const prefix = (s: PublishSettings) => `${s.origin.replace(/^https?:\/\//, '')}/@`

const DOMAIN_DOCS = 'https://notesd.app/docs/guides/using/publishing.html#use-your-own-domain'

/**
 * Whether the space is a website anyone can read. Off, its owner publishes it from the page; on, the page has the
 * address (its handle edited in place), its custom CSS and own domain, and Stop publishing. A member sees where it
 * is, in the row: there's nothing for them to change.
 */
export function PublishedSection({ space, page }: { space: Space; page: TraitPage }) {
  const trait = space.traits.find((t) => t.key === 'published')
  const owner = space.shared?.role !== 'member'
  const [settings, setSettings] = useState<PublishSettings | null>(null)
  const [busy, setBusy] = useState<'publish' | 'handle' | 'css' | 'domain' | 'stop' | null>(null)
  const [failure, setFailure] = useState<Failure>()
  const [stopping, setStopping] = useState(false)
  // Turned on or off here, the list shows it at once (expectPublished): the trait is the section's state.
  const on = trait !== undefined

  // Handed on with its page open: nothing left on it for a member to change.
  const { open, back } = page
  useEffect(() => {
    // After the commit: going back swaps pages synchronously, which React can't do from inside an effect.
    if (open && !owner) queueMicrotask(back)
  }, [open, owner, back])

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

  /** One call; `turning` on or off shows the space so at once, everywhere, put back if the call fails. */
  async function run(key: NonNullable<typeof busy>, work: () => Promise<PublishSettings | undefined>, turning?: boolean): Promise<boolean> {
    setBusy(key)
    setFailure(undefined)
    const call = turning === undefined ? undefined : expectPublished(space.id, turning)
    try {
      const next = await work()
      setSettings(next ?? null)
    } catch (err) {
      if (call !== undefined) unexpectPublished(space.id, call)
      setFailure(publishError(err))
      setBusy(null)
      return false
    }
    // The real trait, with its address, replaces the expected one once the list has it.
    await catchUpSpaces()
    setBusy(null)
    return true
  }

  const error = (field?: Failure['field']) =>
    failure &&
    failure.field === field && (
      <p role="alert" className={info.error}>
        {failure.message}
      </p>
    )

  const action = on ? (
    <Button size="md" variant="danger-text" className={`${settingsStyles.leading} ${info.delete}`} disabled={busy !== null} aria-expanded={stopping} onClick={() => setStopping(true)}>
      Stop publishing…
    </Button>
  ) : (
    <Button
      size="md"
      variant="accent"
      className={info.primary}
      busy={busy === 'publish'}
      disabled={busy !== null}
      onClick={() => void run('publish', () => publishing.put(space.id, { origin: location.origin }), true)}
    >
      Publish
    </Button>
  )

  return (
    <>
      <SectionRow
        Glyph={PublishedGlyph}
        label="Website"
        status={trait?.problem ?? trait?.summary ?? 'Not published'}
        on={on}
        problem={trait?.problem !== undefined}
        live={on}
        onOpen={owner ? page.show : undefined}
      />
      {owner &&
        page.slot &&
        createPortal(
          <div className={info.traitPage} hidden={!page.open}>
            <PageHead id={page.headingId} title="Website">
              {on
                ? `Anyone with its address can read ${space.name}.`
                : `Anyone with the link can read ${space.name}: each note with a title and some text is a post, with pages for its tags and a search. Its address comes from the space’s name, and you can change it after.`}
            </PageHead>
            {trait?.problem && <p className={info.error}>{trait.problem}</p>}
            {on &&
              (!settings ? (
                <p className={info.pageLead} aria-busy="true">
                  {trait?.summary}
                </p>
              ) : (
                <>
                  <div className={info.group}>
                    <Address
                      settings={settings}
                      busy={busy}
                      onChange={(handle) => run('handle', () => publishing.put(space.id, { handle }))}
                      onEdit={() => failure?.field === 'handle' && setFailure(undefined)}
                    />
                  </div>
                  {error('handle')}
                  <Stylesheet
                    key={`css:${settings.css}`}
                    css={settings.css}
                    busy={busy}
                    onSave={(css) => run('css', () => publishing.put(space.id, { css }))}
                    error={error('css')}
                  />
                  <OwnDomain
                    key={`domain:${settings.domain}`}
                    settings={settings}
                    busy={busy}
                    onSave={(domain) => run('domain', () => publishing.put(space.id, { domain }))}
                    error={error('domain')}
                  />
                </>
              ))}
            {error()}
            {on && stopping && (
              <InlineConfirm
                title={`Stop publishing ${space.name}?`}
                action="Stop publishing"
                busy={busy === 'stop'}
                onConfirm={() =>
                  void run(
                    'stop',
                    async () => {
                      await publishing.remove(space.id)
                      return undefined
                    },
                    false,
                  ).then((ok) => ok && setStopping(false))
                }
                onCancel={() => setStopping(false)}
              >
                The site goes offline. Its address stays this space’s, for when you publish it again.
              </InlineConfirm>
            )}
          </div>,
          page.slot,
        )}
      {owner && page.open && page.actions && createPortal(action, page.actions)}
    </>
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

/**
 * The site on a domain of its own. The owner's proxy serves it, mapping the domain onto the site's path; notesd only
 * builds the site's absolute links from it. How to set up each proxy is the docs': here, only the one thing to map.
 * Emptied and saved, it asks first: the site's links go back to the app's address.
 */
function OwnDomain({
  settings,
  busy,
  onSave,
  error,
}: {
  settings: PublishSettings
  busy: string | null
  onSave: (domain: string) => Promise<boolean>
  error: ReactNode
}) {
  const headingId = useId()
  const hintId = useId()
  const [draft, setDraft] = useState(settings.domain)
  const [asking, setAsking] = useState(false)
  const [copied, copy] = useCopy()
  const path = `/@${settings.handle}`
  const changed = draft.trim() !== settings.domain

  function submit() {
    if (busy !== null || !changed) return
    if (draft.trim() === '') setAsking(true)
    else void onSave(draft.trim())
  }

  return (
    <section className={info.section} aria-labelledby={headingId}>
      <h3 id={headingId} className={info.caption}>
        Your own domain
      </h3>
      <div className={info.group}>
        <div className={`${styles.row} ${styles.field}`}>
          <input
            className={styles.domain}
            aria-label="Domain"
            aria-describedby={hintId}
            placeholder="blog.example.com"
            autoComplete="off"
            autoCapitalize="none"
            spellCheck={false}
            value={draft}
            onChange={(e) => setDraft(e.target.value.toLowerCase())}
            onKeyDown={(e) => {
              if (e.key === 'Enter') submit()
            }}
          />
          {settings.domain ? (
            <>
              <p id={hintId} className={styles.hint}>
                Point {settings.domain} at this server, then have your proxy send its requests to notesd with <code>{path}</code> before
                the path.
              </p>
              <div className={styles.line}>
                <code className={styles.path}>{path}</code>
                <Button size="sm" variant="ghost" onClick={() => void copy(path)}>
                  {copied ? 'Copied' : 'Copy'}
                </Button>
                <a className={styles.docs} href={DOMAIN_DOCS} target="_blank" rel="noopener noreferrer">
                  How to set it up
                </a>
              </div>
            </>
          ) : (
            <p id={hintId} className={styles.hint}>
              Serve the site at a domain of its own, such as blog.example.com. Your proxy serves it: notesd can’t by itself.
            </p>
          )}
          {error}
          <div className={styles.save}>
            <Button size="sm" busy={busy === 'domain'} disabled={busy !== null || !changed} onClick={submit}>
              Save
            </Button>
          </div>
          {asking && (
            <InlineConfirm
              title={`Stop using ${settings.domain}?`}
              action="Stop using it"
              busy={busy === 'domain'}
              onConfirm={() => void onSave('')}
              onCancel={() => setAsking(false)}
            >
              The site’s links go back to its address here. Remove its block from your proxy too.
            </InlineConfirm>
          )}
        </div>
      </div>
    </section>
  )
}

/** The space's own CSS, added after the site's: saved when Save is pressed, kept as typed while the page is away. */
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
  const headingId = useId()
  const hintId = useId()
  const [draft, setDraft] = useState(css)
  return (
    <section className={info.section} aria-labelledby={headingId}>
      <h3 id={headingId} className={info.caption}>
        Custom CSS
      </h3>
      <div className={info.group}>
        <div className={`${styles.row} ${styles.field}`}>
          <textarea
            className={styles.code}
            aria-label="Custom CSS"
            aria-describedby={hintId}
            placeholder=":root { --accent: teal }"
            spellCheck={false}
            autoCapitalize="none"
            rows={12}
            wrap="off"
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
          />
          <p id={hintId} className={styles.hint}>
            Added after the site’s own styles, so it wins without <code>!important</code>. Most changes are a variable, such as{' '}
            <code>--accent</code>, <code>--font-body</code> or <code>--measure</code>.
          </p>
          {error}
          <div className={styles.save}>
            {draft !== css && (
              <Button size="sm" variant="ghost" disabled={busy !== null} onClick={() => setDraft(css)}>
                Discard
              </Button>
            )}
            <Button size="sm" busy={busy === 'css'} disabled={busy !== null || draft === css} onClick={() => void onSave(draft)}>
              Save
            </Button>
          </div>
        </div>
      </div>
    </section>
  )
}
