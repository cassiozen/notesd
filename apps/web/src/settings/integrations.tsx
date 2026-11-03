import { useState, type ComponentType, type ReactNode } from 'react'
import { Button } from '../ui/button'
import { Kicker } from '../ui/kicker'
import { Surface } from '../ui/surface'
import { resend, useResendStatus } from './resend'
import styles from './settings.module.css'

export type IntegrationStatus =
  | { state: 'loading' }
  /** editable: whether this account may connect, change or disconnect it (server-wide ones are admins'). */
  | { state: 'off'; editable: boolean }
  | { state: 'on'; editable: boolean; detail: string }

export type Integration = {
  id: IntegrationId
  name: string
  /** What connecting it lets you do, in a line. */
  pitch: string
  mark: ReactNode
  Connect: ComponentType<{ onClose: () => void }>
  Manage: ComponentType<{ onClose: () => void }>
}

type IntegrationId = 'resend'

export const INTEGRATIONS: Integration[] = [resend]

/** Spelled out per integration rather than mapped over the list: each status is a hook. */
export function useIntegrationStatuses(): Record<IntegrationId, IntegrationStatus> {
  return { resend: useResendStatus() }
}

/** Off, the one connected by name, or how many are. */
export function integrationsValue(statuses: Record<IntegrationId, IntegrationStatus>): string {
  if (INTEGRATIONS.some((i) => statuses[i.id].state === 'loading')) return ''
  const on = INTEGRATIONS.filter((i) => statuses[i.id].state === 'on')
  if (on.length === 0) return 'Off'
  return on.length === 1 ? (on[0]?.name ?? '') : String(on.length)
}

/** What's connected, then what can be; each one connects in its own dialog. */
export function IntegrationsPage() {
  const statuses = useIntegrationStatuses()
  const [open, setOpen] = useState<{ integration: Integration; mode: 'connect' | 'manage' } | null>(null)
  const connected = INTEGRATIONS.filter((i) => statuses[i.id].state === 'on')
  const available = INTEGRATIONS.filter((i) => statuses[i.id].state === 'off')
  const close = () => setOpen(null)

  return (
    <>
      {connected.length > 0 && (
        <Surface as="section" className={styles.devices} aria-labelledby="connected-kicker">
          <div className={styles.devicesHead}>
            <Kicker id="connected-kicker">Connected</Kicker>
          </div>
          <ul className={styles.deviceList}>
            {connected.map((i) => {
              const status = statuses[i.id]
              if (status.state !== 'on') return null
              return (
                <li key={i.id} className={styles.device}>
                  {i.mark}
                  <span className={styles.deviceText}>
                    <span className={styles.deviceName}>{i.name}</span>
                    <span className={styles.muted}>{status.detail}</span>
                  </span>
                  {status.editable ? (
                    <Button size="sm" onClick={() => setOpen({ integration: i, mode: 'manage' })} aria-label={`Manage ${i.name}`}>
                      Manage
                    </Button>
                  ) : (
                    <span className={styles.here}>On</span>
                  )}
                </li>
              )
            })}
          </ul>
        </Surface>
      )}
      {available.length > 0 && (
        <Surface as="section" className={styles.devices} aria-labelledby="available-kicker">
          <div className={styles.devicesHead}>
            <Kicker id="available-kicker">{connected.length > 0 ? 'More to connect' : 'Available'}</Kicker>
          </div>
          <ul className={styles.deviceList}>
            {available.map((i) => {
              const status = statuses[i.id]
              const editable = status.state === 'off' && status.editable
              return (
                <li key={i.id} className={styles.device}>
                  {i.mark}
                  <span className={styles.deviceText}>
                    <span className={styles.deviceName}>{i.name}</span>
                    <span className={styles.muted}>{editable ? i.pitch : `${i.pitch} An admin can connect it.`}</span>
                  </span>
                  {editable && (
                    <Button
                      variant="solid"
                      size="sm"
                      onClick={() => setOpen({ integration: i, mode: 'connect' })}
                      aria-label={`Connect ${i.name}`}
                    >
                      Connect
                    </Button>
                  )}
                </li>
              )
            })}
          </ul>
        </Surface>
      )}
      {open?.mode === 'connect' && <open.integration.Connect onClose={close} />}
      {open?.mode === 'manage' && <open.integration.Manage onClose={close} />}
    </>
  )
}
