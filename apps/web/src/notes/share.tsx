import { useEffect, useId, useRef, useState, useSyncExternalStore, type FormEvent } from 'react'
import { ApiError } from '../api/client'
import { notes as api } from '../api/notes'
import type { Space, SpaceTrait } from '../api/types'
import { genericMessage, sentence } from '../auth/errors'
import { Button } from '../ui/button'
import { Dialog } from '../ui/dialog'
import { Field } from '../ui/field'
import { findKeys } from '../editor/find-bar'
import { copyText } from '../ui/copy'
import { MoreIcon, ShareIcon } from '../ui/icons'
import { lockSession, useLockStatus } from '../locks/session'
import { LockNoteDialog, SetupDialog } from '../locks/unlock'
import { useEmailSettings } from './email-settings'
import type { Saver } from './source'
import { SpaceIdentity } from './space-identity'
import { loadedSpaces, useSpaces } from './spaces'
import { exposingTrait, lockedReason, lockedRefusal, newExposure, traitGlyph } from './traits'
import styles from './share.module.css'

/** "Visible to Ana" as it reads mid-sentence. */

/** The note as one Markdown file: the title as a heading, then the body. */
function exportMarkdown(saver: Saver): string {
  const { title, content } = saver.fields()
  return title.trim() ? `# ${title.trim()}\n\n${content}` : content
}

/**
 * The note's menu: Email (when Settings has a Resend key), the browser's share
 * sheet where there is one, and Copy as Markdown. Browsers can't add our own
 * action to their share sheet, hence a menu. With [onDelete] it's the desktop's
 * one "More" menu and ends with Delete; phones keep Delete as its own button.
 * Lock note / Remove lock sit there too; a locked note can't be emailed, since
 * the server only has its ciphertext. With more than one space, Move to space,
 * which asks first when the destination shows the note to more people (§7.8).
 * [onFind] (desktop) leads with Find and Find and replace.
 */
export function NoteMenu({
  saver,
  mobile,
  onDelete,
  onMove,
  onFind,
}: {
  saver: Saver
  mobile: boolean
  onDelete?: () => void
  onMove: (spaceId: string) => void
  onFind?: (replace: boolean) => void
}) {
  const name = onDelete ? 'More' : 'Share'
  const email = useEmailSettings()
  const spaces = useSpaces()
  const [open, setOpen] = useState(false)
  const [emailing, setEmailing] = useState(false)
  const [moving, setMoving] = useState(false)
  const [confirming, setConfirming] = useState<{ space: Space; exposure: SpaceTrait[]; lost: SpaceTrait[] } | null>(null)
  // Why locking (or moving a locked note) can't happen here (§7.9).
  const [explaining, setExplaining] = useState<{ title: string; text: string } | null>(null)
  const [copied, setCopied] = useState(false)
  const [locking, setLocking] = useState<'setup' | 'unlock' | null>(null)
  const { locked } = useSyncExternalStore(saver.subscribe, saver.getSnapshot)
  const lockStatus = useLockStatus()
  const menuId = useId()
  const anchor = useRef<HTMLSpanElement>(null)
  const canShare = typeof navigator.share === 'function'

  const lock = (list: Space[] | null) => {
    const here = list?.find((sp) => sp.id === saver.spaceId())
    const exposing = exposingTrait(here)
    if (here && exposing) {
      setExplaining({ title: lockedRefusal(exposing), text: lockedReason(exposing, here.name, false) })
    } else if (lockSession.key()) void saver.changeLock(true)
    else {
      // Until the keyring is fetched, 'unknown' can't tell a first lock from a later one.
      const choose = () => setLocking(lockSession.getStatus() === 'none' ? 'setup' : 'unlock')
      if (lockStatus === 'unknown') lockSession.load().then(choose, choose)
      else choose()
    }
  }

  useEffect(() => {
    if (!open) return
    const close = (e: Event) => {
      if (!anchor.current?.contains(e.target as Node)) setOpen(false)
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false)
    }
    document.addEventListener('mousedown', close)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', close)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  useEffect(() => {
    if (!copied) return
    const t = setTimeout(() => {
      setCopied(false)
      setOpen(false)
    }, 1200)
    return () => clearTimeout(t)
  }, [copied])

  return (
    <span ref={anchor} className={styles.anchor}>
      <button
        type="button"
        className={mobile ? styles.buttonMobile : styles.button}
        aria-label={name}
        title={name}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        onClick={() => setOpen((v) => !v)}
      >
        {onDelete ? <MoreIcon /> : <ShareIcon size={mobile ? 19 : 16} />}
      </button>
      {open && (
        <div id={menuId} role="menu" aria-label={name} className={styles.menu}>
          {onFind && (
            <>
              <button
                type="button"
                role="menuitem"
                className={[styles.item, styles.withKeys].join(' ')}
                aria-keyshortcuts={findKeys.find.aria}
                onClick={() => {
                  setOpen(false)
                  onFind(false)
                }}
              >
                Find…
                <span className={styles.keys} aria-hidden="true">
                  {findKeys.find.label}
                </span>
              </button>
              <button
                type="button"
                role="menuitem"
                className={[styles.item, styles.withKeys].join(' ')}
                aria-keyshortcuts={findKeys.replace.aria}
                onClick={() => {
                  setOpen(false)
                  onFind(true)
                }}
              >
                Find and replace…
                <span className={styles.keys} aria-hidden="true">
                  {findKeys.replace.label}
                </span>
              </button>
              <div role="separator" className={styles.separator} />
            </>
          )}
          {email?.configured && !locked && (
            <button
              type="button"
              role="menuitem"
              className={styles.item}
              onClick={() => {
                setOpen(false)
                setEmailing(true)
              }}
            >
              Email…
            </button>
          )}
          {canShare && (
            <button
              type="button"
              role="menuitem"
              className={styles.item}
              onClick={() => {
                setOpen(false)
                const { title } = saver.fields()
                // Dismissing the sheet rejects; nothing to report.
                navigator.share({ title: title.trim(), text: exportMarkdown(saver) }).catch(() => undefined)
              }}
            >
              Share…
            </button>
          )}
          <button
            type="button"
            role="menuitem"
            className={styles.item}
            onClick={() => {
              void copyText(exportMarkdown(saver)).then(setCopied)
            }}
          >
            {copied ? 'Copied' : 'Copy as Markdown'}
          </button>
          <div role="separator" className={styles.separator} />
          <button
            type="button"
            role="menuitem"
            className={styles.item}
            onClick={() => {
              setOpen(false)
              if (locked) {
                void saver.changeLock(false)
                return
              }
              // Until the list loads, a space others can read can't be told apart, and the server would refuse the lock.
              if (spaces) lock(spaces)
              else void loadedSpaces().then(lock)
            }}
          >
            {locked ? 'Remove lock' : 'Lock note'}
          </button>
          {spaces && spaces.length > 1 && saver.canMove() && (
            <button
              type="button"
              role="menuitem"
              className={styles.item}
              onClick={() => {
                setOpen(false)
                setMoving(true)
              }}
            >
              Move to space…
            </button>
          )}
          {onDelete && (
            <>
              <div role="separator" className={styles.separator} />
              <button
                type="button"
                role="menuitem"
                className={[styles.item, styles.danger].join(' ')}
                onClick={() => {
                  setOpen(false)
                  onDelete()
                }}
              >
                Delete note
              </button>
            </>
          )}
        </div>
      )}
      {locking === 'setup' && (
        <SetupDialog
          onClose={() => setLocking(null)}
          onDone={() => {
            setLocking(null)
            void saver.changeLock(true)
          }}
        />
      )}
      {locking === 'unlock' && (
        <LockNoteDialog
          onClose={() => setLocking(null)}
          onUnlocked={() => {
            setLocking(null)
            void saver.changeLock(true)
          }}
        />
      )}
      {emailing && email?.configured && (
        <EmailDialog saver={saver} from={email.from} onClose={() => setEmailing(false)} />
      )}
      {moving && spaces && (
        <MoveDialog
          spaces={spaces.filter((sp) => sp.id !== saver.spaceId())}
          onClose={() => setMoving(false)}
          onMove={(to) => {
            setMoving(false)
            const exposing = exposingTrait(to)
            if (locked && exposing) {
              setExplaining({ title: lockedRefusal(exposing), text: lockedReason(exposing, to.name, true) })
              return
            }
            const from = spaces.find((sp) => sp.id === saver.spaceId())
            // Into a space that shows it to more people, or out of one: either way, who sees it changes.
            const exposure = newExposure(from, to)
            const lost = from ? newExposure(to, from) : []
            if (exposure.length > 0 || lost.length > 0) setConfirming({ space: to, exposure, lost })
            else onMove(to.id)
          }}
        />
      )}
      {confirming && (
        <ConfirmMoveDialog
          title={saver.fields().title.trim() || 'New note'}
          space={confirming.space}
          exposure={confirming.exposure}
          lost={confirming.lost}
          onClose={() => setConfirming(null)}
          onMove={() => {
            setConfirming(null)
            onMove(confirming.space.id)
          }}
        />
      )}
      {explaining && <ExplainDialog {...explaining} onClose={() => setExplaining(null)} />}
    </span>
  )
}

function MoveDialog({ spaces, onClose, onMove }: { spaces: Space[]; onClose: () => void; onMove: (to: Space) => void }) {
  const titleId = useId()
  return (
    <Dialog labelledBy={titleId} onClose={onClose}>
      <div className={styles.stack}>
        <h2 id={titleId} className={styles.title}>
          Move to space
        </h2>
        <div className={styles.choices} role="group" aria-labelledby={titleId}>
          {spaces.map((sp) => (
            <Button key={sp.id} size="md" onClick={() => onMove(sp)}>
              <SpaceIdentity space={sp} nameClassName={styles.choiceName} />
            </Button>
          ))}
        </div>
        <div className={styles.actions}>
          <Button size="md" onClick={onClose}>
            Cancel
          </Button>
        </div>
      </div>
    </Dialog>
  )
}

/**
 * Who sees the note changes: the destination shows it to people its current space doesn't ([exposure]), or no
 * longer to some who see it now ([lost]). Said before it moves.
 */
function ConfirmMoveDialog({
  title,
  space,
  exposure,
  lost,
  onClose,
  onMove,
}: {
  title: string
  space: Space
  exposure: SpaceTrait[]
  lost: SpaceTrait[]
  onClose: () => void
  onMove: () => void
}) {
  const titleId = useId()
  return (
    <Dialog labelledBy={titleId} onClose={onClose}>
      <div className={styles.stack}>
        <h2 id={titleId} className={styles.title}>
          Move “{title}” to {space.name}?
        </h2>
        {exposure.length > 0 && (
          <>
            <p className={styles.text}>In {space.name}, this note will be:</p>
            <ExposureList traits={exposure} />
          </>
        )}
        {lost.length > 0 && (
          <>
            <p className={styles.text}>It will no longer be:</p>
            <ExposureList traits={lost} />
          </>
        )}
        <div className={styles.actions}>
          <Button size="md" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="solid" size="md" onClick={onMove}>
            Move
          </Button>
        </div>
      </div>
    </Dialog>
  )
}

function ExposureList({ traits }: { traits: SpaceTrait[] }) {
  return (
    <ul className={styles.exposure}>
      {traits.map((t) => {
        const Glyph = traitGlyph(t.key)
        return (
          <li key={t.key}>
            <span className={styles.exposureGlyph}>
              <Glyph size={15} />
            </span>
            {t.exposure}
          </li>
        )
      })}
    </ul>
  )
}

function ExplainDialog({ title, text, onClose }: { title: string; text: string; onClose: () => void }) {
  const titleId = useId()
  return (
    <Dialog labelledBy={titleId} onClose={onClose}>
      <div className={styles.stack}>
        <h2 id={titleId} className={styles.title}>
          {title}
        </h2>
        <p className={styles.text}>{text}</p>
        <div className={styles.actions}>
          <Button variant="solid" size="md" onClick={onClose}>
            OK
          </Button>
        </div>
      </div>
    </Dialog>
  )
}

function EmailDialog({ saver, from, onClose }: { saver: Saver; from: string; onClose: () => void }) {
  const [to, setTo] = useState('')
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)
  const [sentTo, setSentTo] = useState<string[] | null>(null)
  const titleId = useId()

  async function send(e: FormEvent) {
    e.preventDefault()
    const recipients = to
      .split(/[,;\s]+/)
      .map((a) => a.trim())
      .filter(Boolean)
    if (recipients.length === 0) {
      setError('Add at least one address.')
      return
    }
    setError(undefined)
    setBusy(true)
    try {
      const revision = await saver.savedRevision()
      if (revision === null) {
        setError('This note isn’t saved on the server yet. Send it once it shows Saved.')
        return
      }
      await api.email(saver.id, recipients, revision)
      setSentTo(recipients)
    } catch (err) {
      setError(sendError(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog labelledBy={titleId} onClose={onClose}>
      {sentTo ? (
        <div className={styles.stack}>
          <h2 id={titleId} className={styles.title}>
            Sent
          </h2>
          <p role="status" className={styles.text}>
            Sent to {sentTo.join(', ')}.
          </p>
          <div className={styles.actions}>
            <Button variant="solid" size="md" onClick={onClose}>
              Done
            </Button>
          </div>
        </div>
      ) : (
        <form className={styles.stack} onSubmit={(e) => void send(e)} noValidate>
          <h2 id={titleId} className={styles.title}>
            Email this note
          </h2>
          <Field
            label="To"
            type="email"
            multiple
            autoComplete="email"
            placeholder="name@example.com"
            value={to}
            onChange={(e) => {
              setTo(e.target.value)
              setError(undefined)
            }}
            error={error}
            hint={`From ${from}. Separate addresses with commas.`}
            autoFocus
          />
          <div className={styles.actions}>
            <Button size="md" onClick={onClose} disabled={busy}>
              Cancel
            </Button>
            <Button variant="solid" size="md" type="submit" busy={busy}>
              {busy ? 'Sending…' : 'Send'}
            </Button>
          </div>
        </form>
      )}
    </Dialog>
  )
}

function sendError(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.code === 'stale_revision') return 'This note just changed on another device. Wait for it to update, then send again.'
    if (err.code === 'email_not_configured') return 'Email isn’t set up anymore. Add a Resend API key in Settings.'
    if (err.code === 'email_failed') return `Resend didn’t send it: ${err.message}`
    if (err.status === 400 && err.field === 'to') return sentence(err.message)
    if (err.status === 404) return 'This note was deleted.'
  }
  return genericMessage(err)
}
