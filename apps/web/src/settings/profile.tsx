import { useEffect, useId, useRef, useState, type FormEvent } from 'react'
import { ApiError } from '../api/client'
import { profile as api } from '../api/profile'
import type { Person } from '../api/types'
import { useAccount, useAuth, type Profile } from '../auth/auth-context'
import { genericMessage } from '../auth/errors'
import { ImageError } from '../images/prepare'
import { Avatar } from '../ui/avatar'
import { Button } from '../ui/button'
import { Dialog } from '../ui/dialog'
import { Field } from '../ui/field'
import { preparePhoto } from './photo'
import styles from './settings.module.css'

/** Name and photo, a line of Server: optional, and never asked for anywhere else once signed in. */
export function ProfileItem() {
  const { username, profile } = useAccount()
  const [open, setOpen] = useState(false)
  // Opened offline, there's nothing to edit against yet.
  if (!profile) return null
  return (
    <li className={styles.item}>
      <span className={styles.itemText}>
        <span className={styles.itemTitle}>Name and photo</span>
        <span className={styles.muted}>
          {profile.displayName ? `Shown as ${profile.displayName}` : `Optional. Without a name, you’re shown as ${username}.`}
        </span>
      </span>
      <Button size="sm" onClick={() => setOpen(true)} aria-label="Edit name and photo">
        Edit…
      </Button>
      {open && <ProfileDialog profile={profile} username={username} onClose={() => setOpen(false)} />}
    </li>
  )
}

/** A photo chosen here, not yet saved; or the saved one to be removed. */
type Photo = { bytes: Uint8Array<ArrayBuffer>; url: string } | 'remove' | null

function ProfileDialog({ profile, username, onClose }: { profile: Profile; username: string; onClose: () => void }) {
  const { profileChanged } = useAuth()
  const titleId = useId()
  const input = useRef<HTMLInputElement>(null)
  const [name, setName] = useState(profile.displayName)
  const [photo, setPhoto] = useState<Photo>(null)
  const [nameError, setNameError] = useState<string>()
  const [photoError, setPhotoError] = useState<string>()
  const [formError, setFormError] = useState<string>()
  const [busy, setBusy] = useState(false)

  const previewURL = photo && photo !== 'remove' ? photo.url : undefined
  useEffect(() => (previewURL ? () => URL.revokeObjectURL(previewURL) : undefined), [previewURL])

  async function choose(file: File) {
    setPhotoError(undefined)
    try {
      const bytes = await preparePhoto(file)
      setPhoto({ bytes, url: URL.createObjectURL(new Blob([bytes], { type: 'image/jpeg' })) })
    } catch (err) {
      setPhotoError(err instanceof ImageError ? err.message : 'This browser can’t read that picture.')
    }
  }

  async function submit(e: FormEvent) {
    e.preventDefault()
    setNameError(undefined)
    setPhotoError(undefined)
    setFormError(undefined)
    setBusy(true)
    // What was saved before a later step failed still shows, everywhere.
    let saved: Person | undefined
    try {
      if (name.trim() !== profile.displayName) saved = await api.setName(name.trim())
      if (photo === 'remove') saved = await api.removePhoto()
      else if (photo) saved = await api.setPhoto(photo.bytes)
      if (saved) profileChanged(saved)
      onClose()
    } catch (err) {
      if (saved) profileChanged(saved)
      setBusy(false)
      if (err instanceof ApiError && err.field === 'displayName') setNameError('Use at most 64 characters, on one line.')
      else if (err instanceof ApiError && (err.field === 'avatar' || err.status === 413 || err.status === 415)) {
        setPhotoError('The server didn’t take that photo. Try another.')
      } else setFormError(genericMessage(err))
    }
  }

  const hasPhoto = photo !== 'remove' && (photo !== null || profile.avatar !== '')
  return (
    <Dialog labelledBy={titleId} onClose={busy ? undefined : onClose}>
      <form className={styles.dialog} onSubmit={(e) => void submit(e)} noValidate>
        <h2 id={titleId} className={styles.dialogTitle}>
          Name and photo
        </h2>
        <p className={styles.dialogText}>Both are optional. Admins on this server see them when they manage people.</p>
        <div className={styles.photoRow}>
          <Avatar
            userId={profile.userId}
            version={photo === 'remove' ? '' : profile.avatar}
            src={previewURL}
            name={name.trim() || username}
            className={[styles.avatar, styles.photoPreview].join(' ')}
          />
          <div className={styles.photoActions}>
            <Button size="sm" onClick={() => input.current?.click()} disabled={busy}>
              {hasPhoto ? 'Change photo…' : 'Choose photo…'}
            </Button>
            {hasPhoto && (
              <Button size="sm" onClick={() => setPhoto(profile.avatar ? 'remove' : null)} disabled={busy}>
                Remove photo
              </Button>
            )}
          </div>
          <input
            ref={input}
            type="file"
            accept="image/*"
            hidden
            data-testid="photo-input"
            onChange={(e) => {
              const file = e.currentTarget.files?.[0]
              e.currentTarget.value = ''
              if (file) void choose(file)
            }}
          />
        </div>
        {photoError && (
          <p role="alert" className={styles.error}>
            {photoError}
          </p>
        )}
        <Field
          label="Name"
          autoComplete="name"
          maxLength={64}
          value={name}
          onChange={(e) => setName(e.target.value)}
          error={nameError}
          hint={`Shown instead of your username, ${username}, which you still sign in with.`}
          readOnly={busy}
        />
        {formError && (
          <p role="alert" className={styles.error}>
            {formError}
          </p>
        )}
        <div className={styles.dialogActions}>
          <Button size="md" onClick={onClose} disabled={busy}>
            Cancel
          </Button>
          <Button variant="solid" size="md" type="submit" busy={busy}>
            {busy ? 'Saving…' : 'Save'}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}
