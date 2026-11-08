import type { Editor } from '@tiptap/core'
import { useCallback, useEffect, useId, useLayoutEffect, useRef, useState, useSyncExternalStore, type ReactNode } from 'react'
import { ApiError } from '../api/client'
import { useAuth } from '../auth/auth-context'
import { ChecklistTool, ImageTool, IndentTools, LinkForm, LinkTool, MarkTools, NoteContent, TextStyles, Tool, replaceNote, useNoteEditor } from '../editor/editor'
import { FindBar, findShortcut, seedFrom, type FindRequest } from '../editor/find-bar'
import { step } from '../editor/find'
import { noteFields, noteSummary, titleOf } from '../editor/note-doc'
import { dragRegion } from '../desktop'
import { ImageError } from '../images/prepare'
import { imageMarkdown } from '../images/ref'
import { insertImageAt, resealImages, storeImage } from '../images/store'
import { Banner } from '../ui/banner'
import { Button } from '../ui/button'
import { ComposeIcon, LockIcon, TrashIcon } from '../ui/icons'
import { LockedError, UnsupportedLockError } from '../locks/crypto'
import { lockSession, useLockStatus } from '../locks/session'
import { UnlockForm } from '../locks/unlock'
import { openOver } from '../router'
import { StatusPill } from '../ui/status-pill'
import { failureText } from './activity'
import { agentSocket, useAgentOverlays, useAgentWriting } from '../agent/socket'
import { requestRun, triggerTiming } from '../agent/triggers'
import { applyForAgent, composed, madeTo, opMadeTo, pointAt, readForAgent } from '../editor/agent-edit'
import { setAgentOverlays, stillQuoted, type Overlay } from '../editor/agent-overlays'
import { setAgentWaiting } from '../editor/agent-presence'
import { useFailedRun } from './failed-run'
import { offlineNotice, useStatusShown } from './save-status'
import { openNote, type Snapshot } from './saver'
import { notesSource, type Saver } from './source'
import { NoteMenu } from './share'
import { ago, editorName, useMinutes } from './sharing'
import { spaceName, useSpaces } from './spaces'
import { useNotes } from './store'
import { tagKey } from './tags'
import styles from './note-pane.module.css'

type Props = {
  /** An existing note, loaded by id… */
  id?: string | undefined
  /** …or a new one the server doesn't have yet. */
  draft?: Saver | undefined
  mobile: boolean
  onBack: () => void
  onNew: () => void
  /** The draft reached the server; it can go in the URL now. */
  onCreated: (id: string) => void
  /** Open another not-yet-saved note (a recovered copy). */
  onOpenDraft: (saver: Saver) => void
  onDelete: (saver: Saver) => void
  /** Move the note to another space (§7.7). */
  onMove: (saver: Saver, spaceId: string) => void
  /** The note is gone for good (deleted elsewhere, discarded). */
  onGone: (id: string) => void
  /** A short message for the toast. */
  onNotice: (text: string) => void
}

type Load =
  | { kind: 'loading' }
  | { kind: 'missing' }
  | { kind: 'failed' }
  /**
   * A locked note, and locked notes are locked: only its title is readable.
   * [draft]: a new note, not on the server yet, kept until unlocked.
   * [keyFailed]: the key in memory didn't open it (the keyring was reset since).
   */
  | { kind: 'locked'; title: string; draft?: Saver; keyFailed: boolean }
  /** Locked by a newer notesd: shown, never opened or saved. */
  | { kind: 'unsupported'; title: string }
  | { kind: 'ready'; saver: Saver }

export function NotePane({ id, draft, mobile, onBack, onNew, onCreated, onOpenDraft, onDelete, onMove, onGone, onNotice }: Props) {
  const { existingSaver, saverFor, release, removed, space, setSpace } = useNotes()
  const lockStatus = useLockStatus()
  const [load, setLoad] = useState<Load>(() => {
    const ready = draft ?? (id ? existingSaver(id) : undefined)
    // A locked note's saver holds plain text: only show it while unlocked.
    if (!ready || (ready.isLocked() && !lockSession.key())) return draft ? lockedDraft(draft) : { kind: 'loading' }
    return { kind: 'ready', saver: ready }
  })

  // Locked notes locked again: the open note closes (during render, so its
  // text is never shown again) and reloads as a locked placeholder. A new
  // note can't be reloaded; it waits, in memory, for the unlock.
  if (load.kind === 'ready' && load.saver.isLocked() && lockStatus !== 'unlocked') {
    setLoad(draft ? lockedDraft(draft) : { kind: 'loading' })
  }

  // The locked new note reached the server and is opened by id now. Still locked: staying on the
  // placeholder, not passing through loading, keeps a password half typed into it.
  if (load.kind === 'locked' && load.draft && !draft) setLoad({ kind: 'locked', title: load.title, keyFailed: false })

  // Unlocked elsewhere (another note, Settings, a password change): try again.
  if (load.kind === 'locked' && !load.keyFailed && lockStatus === 'unlocked') {
    setLoad(load.draft ? { kind: 'ready', saver: load.draft } : { kind: 'loading' })
  }

  // A locked new note behind the placeholder: an untouched one just goes; one
  // that reached the server (saved before the key went) opens by id, locked.
  const lockedNew = load.kind === 'locked' ? load.draft : undefined
  useEffect(() => {
    if (!lockedNew) return
    const check = () => {
      if (lockedNew.getSnapshot().created) onCreated(lockedNew.id)
      else if (lockedNew.isBlankDraft()) onGone(lockedNew.id)
    }
    check()
    return lockedNew.subscribe(check)
  }, [lockedNew, onCreated, onGone])

  useEffect(() => {
    if (load.kind !== 'loading' || !id) return
    let cancelled = false
    notesSource().get(id).then(
      async (stored) => {
        let opened
        try {
          opened = await openNote(stored)
        } catch (err) {
          if (cancelled) return
          setLoad(
            err instanceof UnsupportedLockError
              ? { kind: 'unsupported', title: stored.title }
              : err instanceof LockedError
                ? { kind: 'locked', title: stored.title, keyFailed: lockSession.key() !== null }
                : { kind: 'failed' },
          )
          return
        }
        if (cancelled) return
        const { note, locked, envelope } = opened
        const saver = saverFor({
          id: note.id,
          fields: { title: note.title, content: note.content },
          revision: note.revision,
          updatedAt: note.updatedAt,
          locked,
          envelope,
          spaceId: note.spaceId,
          ...(note.updatedBy !== undefined && { updatedBy: note.updatedBy }),
        })
        // Opened from a link into another space: the list follows it there.
        if (note.spaceId !== space) setSpace(note.spaceId)
        setLoad({ kind: 'ready', saver })
      },
      (err: unknown) => {
        if (cancelled) return
        if (err instanceof ApiError && (err.status === 404 || err.status === 410)) {
          // Gone from the server: drop any stale row too.
          removed(id)
          setLoad({ kind: 'missing' })
        } else setLoad({ kind: 'failed' })
      },
    )
    return () => {
      cancelled = true
    }
  }, [id, load.kind, saverFor, removed, space, setSpace])

  const saver = load.kind === 'ready' ? load.saver : null
  // Leaving: save what's left, then the store forgets it; agents hear of both.
  useEffect(() => {
    if (!saver) return
    agentSocket.noteOpened(saver.id, saver.spaceId())
    return () =>
      void saver
        .savedRevision()
        .catch(() => null)
        .then((revision) => agentSocket.noteClosed(saver.id, revision))
        .finally(() => release(saver.id))
  }, [saver, release])

  return (
    <section className={styles.pane} aria-label="Note" data-note-pane="">
      {saver ? (
        <NoteEditor
          saver={saver}
          isDraft={draft !== undefined}
          mobile={mobile}
          onBack={onBack}
          onNew={onNew}
          onCreated={onCreated}
          onDelete={() => onDelete(saver)}
          onMove={(spaceId) => onMove(saver, spaceId)}
          onGone={() => onGone(saver.id)}
          onOpenDraft={onOpenDraft}
          onNotice={onNotice}
        />
      ) : (
        <>
          <div className={styles.bar} {...dragRegion('deep')}>
            {mobile && <BackButton onClick={onBack} />}
          </div>
          <div className={styles.placeholder}>
            {load.kind === 'loading' && <span className={[styles.muted, styles.loading].join(' ')}>Loading…</span>}
            {load.kind === 'missing' && (
              <>
                <h2 className={styles.placeholderTitle}>This note doesn’t exist.</h2>
                <span className={styles.muted}>It may have been deleted on another device.</span>
              </>
            )}
            {load.kind === 'locked' && (
              <>
                <span className={styles.lockMark}>
                  <LockIcon size={24} />
                </span>
                <h2 className={styles.placeholderTitle}>{load.title.trim() || 'Locked note'}</h2>
                {lockStatus === 'none' || (load.keyFailed && lockStatus === 'unlocked') ? (
                  // The keyring was reset since this note was locked: its key is gone.
                  <span className={styles.muted}>This note was locked with a password that was reset. It can’t be opened.</span>
                ) : (
                  <>
                    <span className={styles.muted}>This note is locked.</span>
                    {/* Unlocking re-renders this pane as unlocked, which opens the note. */}
                    <UnlockForm onUnlocked={() => undefined} autoFocus={!mobile} />
                  </>
                )}
              </>
            )}
            {load.kind === 'unsupported' && (
              <>
                <span className={styles.lockMark}>
                  <LockIcon size={24} />
                </span>
                <h2 className={styles.placeholderTitle}>{load.title.trim() || 'Locked note'}</h2>
                <span className={styles.muted}>{new UnsupportedLockError().message}</span>
              </>
            )}
            {load.kind === 'failed' && (
              <>
                <h2 className={styles.placeholderTitle}>Couldn’t load this note.</h2>
                <Button size="sm" onClick={() => setLoad({ kind: 'loading' })}>
                  Try again
                </Button>
              </>
            )}
          </div>
        </>
      )}
    </section>
  )
}

function lockedDraft(saver: Saver): Load {
  return { kind: 'locked', title: saver.fields().title, draft: saver, keyFailed: false }
}

/** The list row's title and preview; a locked note's body stays out of the list. */
function summary(saver: Saver, doc: Parameters<typeof noteSummary>[0]) {
  const s = noteSummary(doc)
  return saver.isLocked() ? { ...s, preview: '' } : s
}

/** Back to the list, named after its space once there are several. */
function BackButton({ onClick }: { onClick: () => void }) {
  const { space } = useNotes()
  const spaces = useSpaces()
  return (
    <button type="button" className={styles.back} onClick={onClick}>
      <span className={styles.chevron} aria-hidden="true">
        ‹
      </span>
      {spaces && spaces.length > 1 ? spaceName(spaces, space) : 'Notes'}
    </button>
  )
}

type EditorProps = {
  saver: Saver
  isDraft: boolean
  mobile: boolean
  onBack: () => void
  onNew: () => void
  onCreated: (id: string) => void
  onDelete: () => void
  onMove: (spaceId: string) => void
  onGone: () => void
  onOpenDraft: (saver: Saver) => void
  onNotice: (text: string) => void
}

const APP_TITLE = 'notesd'

// Savers outlive the pane: a split reopened later was already announced.
const splitsShown = new WeakSet<NonNullable<Snapshot['split']>>()

/** How often the list row follows the typing. */
const TOUCH_MS = 150
/** How long after typing stops suggestions are checked against the note. */
const STALE_CHECK_MS = 600
const noOverlays: Overlay[] = []

function NoteEditor({ saver, isDraft, mobile, onBack, onNew, onCreated, onDelete, onMove, onGone, onOpenDraft, onNotice }: EditorProps) {
  const snap = useSyncExternalStore(saver.subscribe, saver.getSnapshot)
  const { touch, tags, setFilter, space } = useNotes()
  const failed = useFailedRun(isDraft ? null : saver.id)
  const agentWriting = useAgentWriting(saver.id)
  // Opened new: note created, not started editing, fires for it.
  const [openedNew] = useState(isDraft)
  const lastEdit = useRef<number | null>(null)
  const startedEditing = () => {
    const now = Date.now()
    if (!openedNew && (lastEdit.current === null || now - lastEdit.current > triggerTiming.idleMs)) {
      requestRun('editStarted', saver.id, saver.spaceId())
    }
    lastEdit.current = now
  }

  useEffect(() => {
    if (openedNew) requestRun('noteCreated', saver.id, saver.spaceId())
  }, [openedNew, saver])
  const [initial] = useState(() => saver.fields())
  const [linkOpen, setLinkOpen] = useState(false)
  const [stylesOpen, setStylesOpen] = useState(false)
  const [find, setFind] = useState<{ request: FindRequest; replace: boolean } | null>(null)
  // Phones have no find bar: widening the window again mustn't bring it back and take focus.
  if (mobile && find) setFind(null)
  const touchTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  const stylesId = useId()
  const popover = useRef<HTMLDivElement>(null)

  // Pictures go in where they were asked for, even if the text moves meanwhile.
  const addImages = (files: File[], at: number) => {
    let pos = at
    const follow = ({ transaction }: { transaction: { mapping: { map: (p: number) => number } } }) => {
      pos = transaction.mapping.map(pos)
    }
    editor.on('transaction', follow)
    void saver
      .withImages(async () => {
        for (const file of files) {
          // In order, each under the one before.
          // oxlint-disable-next-line no-await-in-loop
          const ref = await storeImage(file, () => saver.isLocked())
          if (editor.isDestroyed) {
            // Closed while uploading: the picture goes at the end of the note.
            if (!saver.appendClosed(imageMarkdown(ref))) {
              throw new ImageError('A picture finished uploading after you reopened the note. Add it again.')
            }
            continue
          }
          // The next picture goes under this one.
          pos = insertImageAt(editor, pos, ref)
        }
      })
      .finally(() => editor.off('transaction', follow))
  }

  const editor = useNoteEditor({
    initial,
    onImages: (files, at) => addImages(files, at),
    onChange: (ed, byAgent) => {
      saver.edit()
      if (!byAgent) startedEditing()
      touchTimer.current ??= setTimeout(() => {
        touchTimer.current = undefined
        if (!ed.isDestroyed) touch(saver.id, summary(saver, ed.state.doc))
      }, TOUCH_MS)
    },
    onLinkShortcut: () => {
      setStylesOpen(false)
      setLinkOpen(true)
    },
    tags,
    onOpenTag: (name) => {
      const key = tagKey(name)
      setFilter({ tag: key, name: tags.find((t) => t.key === key)?.name ?? name })
      // On a phone the list is a separate screen.
      if (mobile) onBack()
    },
  })

  // In the note, where the user is looking: who's writing and where.
  const spaces = useSpaces()
  const agentLabel = `${spaceName(spaces, saver.spaceId())} agent`
  useEffect(() => {
    setAgentWaiting(editor, agentWriting ? agentLabel : null)
  }, [editor, agentWriting, agentLabel])

  // Layout effect: its cleanup runs before useEditor destroys the editor,
  // so detaching can still read the final text.
  useLayoutEffect(() => {
    const detach = saver.attach(() => noteFields(editor.state.doc))
    const off = saver.onRemoteChange((next) => {
      replaceNote(editor, next)
      touch(saver.id, summary(saver, editor.state.doc))
    })
    const unreseal = saver.setResealer((locked) => resealImages(editor, locked))
    return () => {
      off()
      unreseal()
      detach()
    }
  }, [saver, editor, touch])

  // Agents' runs read and change the note here, where the user sees it and can undo it.
  useEffect(
    () =>
      agentSocket.serve(saver.id, {
        read: () => {
          if (saver.isLocked()) return { error: 'locked' }
          const { fields, cursor } = readForAgent(editor.state)
          return { spaceId: saver.spaceId(), ...fields, cursor, revision: saver.knownRevision() }
        },
        apply: async (change) => {
          if (saver.isLocked()) return { error: 'locked' }
          await composed(editor)
          // What it shows now: the user may have typed since the run read it.
          const { fields: before, cursor } = readForAgent(editor.state)
          const revision = saver.knownRevision()
          const after = madeTo(change, before, cursor)
          if (!after) return { error: 'changed' }
          applyForAgent(editor, after)
          return { before, revision, shows: noteFields(editor.state.doc) }
        },
      }),
    [saver, editor],
  )

  // What runs show beside the note: cards to read, and changes the user makes with ✓ as the run would have.
  // None once it's locked: the server has dropped them, as agents never see a locked note.
  const open = useAgentOverlays(saver.id)
  const overlays = snap.locked ? noOverlays : open
  useEffect(() => {
    setAgentOverlays(editor, overlays, agentLabel, (overlay, answer, at) => {
      if (answer !== 'accept' || !overlay.change) return agentSocket.answerOverlay(overlay, answer)
      if (saver.isLocked()) return
      const op = overlay.change
      const { fields: before } = readForAgent(editor.state)
      // An insert at the cursor goes where the cursor was when it was suggested, carried along since.
      const cursor = op.kind === 'insert' && op.at === 'cursor' ? pointAt(editor.state, at, before) : null
      const after = stillQuoted(editor.state, overlay.id) ? opMadeTo(op, before, cursor) : null
      if (!after) return agentSocket.answerOverlay(overlay, 'stale')
      const revision = saver.knownRevision()
      applyForAgent(editor, after)
      agentSocket.answerOverlay(overlay, 'accept', { before, revision, shows: noteFields(editor.state.doc) })
    })
  }, [editor, overlays, agentLabel, saver])

  // A suggested replacement whose text the user changed can't be made where it shows any more: it goes
  // rather than waiting for a ✓ that would do nothing, or change the same words elsewhere. Checked once
  // typing pauses, since it serializes the note, and when the list changes, since accepting one
  // suggestion can be what changed another's text.
  useEffect(() => {
    const replacing = overlays.filter((o) => o.change?.kind === 'replace')
    if (!replacing.length) return
    let timer: ReturnType<typeof setTimeout> | undefined
    const later = () => {
      clearTimeout(timer)
      timer = setTimeout(() => {
        if (editor.isDestroyed) return
        const now = noteFields(editor.state.doc)
        for (const o of replacing) {
          if (o.change && (!stillQuoted(editor.state, o.id) || !opMadeTo(o.change, now, null))) agentSocket.answerOverlay(o, 'stale')
        }
      }, STALE_CHECK_MS)
    }
    const check = ({ transaction }: { transaction: { docChanged: boolean } }) => {
      if (transaction.docChanged) later()
    }
    later()
    editor.on('transaction', check)
    return () => {
      editor.off('transaction', check)
      clearTimeout(timer)
    }
  }, [editor, overlays])

  useEffect(() => () => clearTimeout(touchTimer.current), [])

  // An installed web app shows this in its title bar and the Window menu. Transactions, not
  // updates: remote changes replace the text without an update event.
  useEffect(() => {
    const show = () => {
      document.title = `${titleOf(editor.state.doc).trim() || 'New note'} — ${APP_TITLE}`
    }
    show()
    editor.on('transaction', show)
    return () => {
      editor.off('transaction', show)
      document.title = APP_TITLE
    }
  }, [editor])

  useEffect(() => {
    if (isDraft) editor.commands.focus('start')
  }, [isDraft, editor])

  useEffect(() => {
    if (isDraft && snap.created) onCreated(saver.id)
  }, [isDraft, snap.created, saver.id, onCreated])

  // The callbacks are new each render: act on each disappearance once.
  const goneSeen = useRef<Snapshot['gone']>(null)
  useEffect(() => {
    const gone = snap.gone
    if (!gone || goneSeen.current === gone) return
    goneSeen.current = gone
    onGone()
    if (!gone.copy) return
    onOpenDraft(gone.copy)
    onNotice(`Deleted on another device. Your edits were kept as “${gone.copy.fields().title}”.`)
  }, [snap.gone, onGone, onOpenDraft, onNotice])

  useEffect(() => {
    const split = snap.split
    if (!split || splitsShown.has(split)) return
    splitsShown.add(split)
    onNotice(`Changed in two places. Your version was kept as “${split.title}”.`)
  }, [snap.split, onNotice])

  const undoMerge = useCallback(() => {
    saver.undoMerge().then(
      (title) => {
        if (title) onNotice(`Merge undone. The other version was kept as “${title}”.`)
      },
      () => onNotice('The merge couldn’t be undone. Try again.'),
    )
  }, [saver, onNotice])

  useEffect(() => {
    if (!stylesOpen) return
    const close = (e: Event) => {
      const target = e.target as Element
      // The Aa button toggles on its own.
      if (popover.current?.contains(target) || target.closest?.(`[aria-controls="${stylesId}"]`)) return
      setStylesOpen(false)
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return
      setStylesOpen(false)
      editor.commands.focus()
    }
    document.addEventListener('mousedown', close)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', close)
      document.removeEventListener('keydown', onKey)
    }
  }, [stylesOpen, stylesId, editor])

  // replace: undefined keeps the bar as it is (⌘F with replace showing doesn't hide it).
  const openFind = useCallback(
    (replace?: boolean) => {
      const seed = seedFrom(editor)
      // A new request object each time, even with the bar up: it refocuses the field.
      setFind((prev) => ({
        request: { seed, field: replace ? 'replace' : 'find' },
        replace: replace ?? prev?.replace ?? false,
      }))
    },
    [editor],
  )

  // While the note has focus, or nothing does: anywhere else (the list, a dialog) keeps the browser's own find.
  const findOpen = find !== null
  useEffect(() => {
    if (mobile) return
    const onKey = (e: KeyboardEvent) => {
      const command = findShortcut(e)
      if (!command) return
      const target = e.target as Element
      // The note's dialogs open inside the pane.
      if (target.closest?.('dialog')) return
      if (target !== document.body && !target.closest?.('[data-note-pane]')) return
      if (command === 'next' || command === 'prev') {
        if (!findOpen) return
        e.preventDefault()
        step(editor, command === 'next' ? 1 : -1)
        return
      }
      e.preventDefault()
      openFind(command === 'replace' || undefined)
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [mobile, editor, findOpen, openFind])

  const toggleLink = () => {
    setStylesOpen(false)
    setLinkOpen((v) => !v)
  }
  const toggleStyles = () => {
    setLinkOpen(false)
    setStylesOpen((v) => !v)
  }

  const problem = snap.problem
  const statusShown = useStatusShown(snap)
  const refusal =
    problem?.kind === 'rejected' && problem.onDevice ? 'This device couldn’t store this save' : 'The server didn’t accept this save'
  // A 401 or a refusal also reads "Couldn't save"; the tooltip says which.
  const statusDetail =
    snap.state !== 'failed'
      ? undefined
      : problem?.kind === 'rejected'
        ? refusal
        : problem?.kind === 'unlock'
          ? 'Unlock locked notes to save'
          : problem?.kind === 'upgrade'
            ? 'Update notesd to save'
            : problem?.kind === 'unexpected'
              ? 'notesd didn’t get this save'
              : problem === null
            ? 'Waiting for you to sign in again'
            : undefined

  return (
    <>
      <div className={styles.bar} {...dragRegion('deep')}>
        {mobile && <BackButton onClick={onBack} />}
        <EditedBy snap={snap} />
        <span className={styles.spacer} />
        {/* The note shows it; this tells screen readers. */}
        <span className="visually-hidden" role="status">
          {agentWriting ? 'Agent is writing…' : ''}
        </span>
        <StatusPill state={snap.imagesBusy ? 'saving' : snap.state} shown={statusShown || snap.imagesBusy} detail={statusDetail} />
        {snap.locked && (
          <button
            type="button"
            className={mobile ? styles.lockNowMobile : styles.lockNow}
            onClick={() => void lockSession.lock()}
            aria-label="Lock now"
            title="Locked note. Lock now"
          >
            <LockIcon size={mobile ? 19 : 16} />
          </button>
        )}
        {mobile ? (
          <NoteMenu saver={saver} mobile onMove={onMove} />
        ) : (
          <NoteMenu saver={saver} mobile={false} onDelete={onDelete} onMove={onMove} onFind={(replace) => openFind(replace)} />
        )}
        {mobile && <DeleteButton onClick={onDelete} />}
      </div>

      {/* Phones keep the link field up top: a bottom one would sit under the keyboard. */}
      {linkOpen && mobile && (
        <div className={styles.linkRow}>
          <LinkForm editor={editor} onDone={() => setLinkOpen(false)} />
        </div>
      )}

      {failed.run && (
        <Notice
          tone="warn"
          actions={
            <>
              <Button size="sm" onClick={failed.dismiss}>
                Dismiss
              </Button>
              <Button
                variant="solid"
                size="sm"
                onClick={() => openOver('/activity', { space, run: failed.run?.id })}
              >
                Activity
              </Button>
            </>
          }
        >
          The agent’s last run on this note failed: {failureText(failed.run)}.
        </Notice>
      )}
      {offlineNotice(snap) && (
        <Notice
          tone="error"
          actions={
            <Button variant="solid" size="sm" onClick={() => saver.retryNow()}>
              Retry
            </Button>
          }
        >
          Couldn’t reach the server. Changes are kept here and retried.
        </Notice>
      )}
      {snap.imageError && (
        <Notice
          tone="error"
          actions={
            <Button size="sm" onClick={() => saver.dismissImageError()}>
              Dismiss
            </Button>
          }
        >
          {snap.imageError}
        </Notice>
      )}
      {problem?.kind === 'rejected' && (
        <Notice
          tone="error"
          actions={
            <Button variant="solid" size="sm" onClick={() => saver.retryNow()}>
              Retry
            </Button>
          }
        >
          {refusal}: {problem.message}
        </Notice>
      )}
      {problem?.kind === 'unexpected' && (
        <Notice
          tone="error"
          actions={
            <Button variant="solid" size="sm" onClick={() => saver.retryNow()}>
              Retry
            </Button>
          }
        >
          {problem.message}
        </Notice>
      )}
      {snap.merged && !problem && (
        <Notice
          tone="warn"
          actions={
            <Button size="sm" onClick={undoMerge}>
              Undo
            </Button>
          }
        >
          Merged with changes from another device.
        </Notice>
      )}
      {problem?.kind === 'conflict' && (
        <Notice
          tone="warn"
          actions={
            <>
              <Button size="sm" onClick={() => saver.takeTheirs()}>
                Use theirs
              </Button>
              <Button variant="solid" size="sm" onClick={() => saver.keepMine()}>
                Keep mine
              </Button>
            </>
          }
        >
          Changed on another device while you were editing. “Keep mine” saves your version as a separate note.
        </Notice>
      )}
      {problem?.kind === 'deleted' && (
        <Notice
          tone="warn"
          actions={
            <>
              <Button size="sm" onClick={onGone}>
                Discard
              </Button>
              <Button
                variant="solid"
                size="sm"
                onClick={() => {
                  const copy = saver.recover()
                  if (!copy) return
                  onGone()
                  onOpenDraft(copy)
                }}
              >
                Keep as new note
              </Button>
            </>
          }
        >
          This note was deleted on another device.
        </Notice>
      )}

      {find && !mobile && (
        <FindBar
          editor={editor}
          request={find.request}
          replace={find.replace}
          onReplaceChange={(replace) => setFind((prev) => prev && { ...prev, replace })}
          onClose={() => setFind(null)}
        />
      )}

      <div className={styles.scroller} data-note-scroller="">
        <div className={styles.page}>
          <NoteContent editor={editor} />
        </div>
      </div>

      {linkOpen && !mobile ? (
        <div className={[styles.dock, styles.dockLink].join(' ')}>
          <LinkForm editor={editor} onDone={() => setLinkOpen(false)} />
        </div>
      ) : (
        <div className={styles.dock} role="toolbar" aria-label="Formatting">
          {mobile ? (
            <>
              <ChecklistTool editor={editor} className={styles.bottomTool} iconSize={20} />
              <StylesButton editor={editor} id={stylesId} open={stylesOpen} onToggle={toggleStyles} mobile />
              <IndentTools editor={editor} className={styles.bottomTool} iconSize={19} />
              <LinkTool editor={editor} open={linkOpen} onToggle={toggleLink} className={styles.bottomTool} iconSize={19} />
              <ImageTool editor={editor} onPick={addImages} className={styles.bottomTool} iconSize={19} />
              <button type="button" className={styles.bottomTool} onClick={onNew} aria-label="New note" title="New note">
                <ComposeIcon />
              </button>
            </>
          ) : (
            <>
              <StylesButton editor={editor} id={stylesId} open={stylesOpen} onToggle={toggleStyles} />
              <MarkTools editor={editor} />
              <span className={styles.sep} aria-hidden="true" />
              <ChecklistTool editor={editor} />
              <IndentTools editor={editor} />
              <LinkTool editor={editor} open={linkOpen} onToggle={toggleLink} />
              <ImageTool editor={editor} onPick={addImages} />
            </>
          )}
        </div>
      )}

      {stylesOpen && (
        <div ref={popover} className={styles.popover}>
          <TextStyles
            editor={editor}
            id={stylesId}
            onApplied={() => {
              if (mobile) setStylesOpen(false)
            }}
          />
        </div>
      )}
    </>
  )
}

function StylesButton({ editor, id, open, onToggle, mobile = false }: { editor: Editor; id: string; open: boolean; onToggle: () => void; mobile?: boolean }) {
  return (
    <Tool
        label="Text styles"
        on={open}
        expanded={open}
        controls={id}
        className={[styles.aa, mobile && styles.bottomTool].filter(Boolean).join(' ')}
        run={() => {
          if (!editor.isFocused) editor.commands.focus()
          onToggle()
        }}
      >
      <span className={styles.aaBig}>A</span>a
    </Tool>
  )
}

/** "Edited by Ana · 2h": the last change was another member's (§7.9). */
function EditedBy({ snap }: { snap: Snapshot }) {
  const spaces = useSpaces()
  const { state } = useAuth()
  const who = editorName(spaces?.find((s) => s.id === snap.spaceId), snap.updatedBy, 'userId' in state ? state.userId : undefined)
  useMinutes(who !== null)
  if (!who || snap.updatedAt === null) return null
  return <span className={styles.editedBy}>{`Edited by ${who} · ${ago(snap.updatedAt)}`}</span>
}

function DeleteButton({ onClick }: { onClick: () => void }) {
  return (
    <button type="button" className={styles.deleteMobile} onClick={onClick} aria-label="Delete note" title="Delete note">
      <TrashIcon size={18} />
    </button>
  )
}

function Notice({ tone, actions, children }: { tone: 'error' | 'warn'; actions: ReactNode; children: ReactNode }) {
  return (
    <div className={styles.notice}>
      <Banner tone={tone} actions={actions}>
        {children}
      </Banner>
    </div>
  )
}
