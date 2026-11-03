import { useEffect, useId, useState } from 'react'
import { agent, type AccountSettings } from '../api/agent'
import { genericMessage } from '../auth/errors'
import field from '../ui/field.module.css'
import { Kicker } from '../ui/kicker'
import { Surface } from '../ui/surface'
import styles from './settings.module.css'

function zones(current: string): string[] {
  let all: string[] = []
  try {
    all = Intl.supportedValuesOf('timeZone')
  } catch {
    // Older browsers: only the current one can be shown.
  }
  return all.includes(current) ? all : [current, ...all]
}

/** The account's time zone, which agents use for "today". Synced to the account. */
export function TimeZoneCard() {
  const id = useId()
  const [settings, setSettings] = useState<AccountSettings | null>(null)
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    let live = true
    agent.account().then(
      (s) => live && setSettings(s),
      (err: unknown) => live && setError(genericMessage(err)),
    )
    return () => {
      live = false
    }
  }, [])

  async function choose(timeZone: string) {
    setBusy(true)
    setError(undefined)
    try {
      setSettings(await agent.setTimeZone(timeZone))
    } catch (err) {
      setError(genericMessage(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Surface as="section" className={styles.card} aria-labelledby={`${id}-kicker`}>
      <Kicker id={`${id}-kicker`}>Time zone</Kicker>
      <span className={styles.text}>
        Agents use it to know what day it is.
        {settings?.timeZoneSource === 'auto' && ' Set from the first device that connected.'}
      </span>
      {settings && (
        <div className={field.field}>
          <label htmlFor={id} className="visually-hidden">
            Time zone
          </label>
          <select
            id={id}
            className={field.input}
            value={settings.timeZone}
            disabled={busy}
            onChange={(e) => void choose(e.target.value)}
          >
            {zones(settings.timeZone).map((z) => (
              <option key={z} value={z}>
                {z.replaceAll('_', ' ')}
              </option>
            ))}
          </select>
        </div>
      )}
      {error && (
        <p role="alert" className={styles.error}>
          {error}
        </p>
      )}
    </Surface>
  )
}
