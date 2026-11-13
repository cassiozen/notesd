import { useEffect, useId, useRef, useState, type FormEvent } from 'react'
import { ApiError } from '../api/client'
import { settings } from '../api/settings'
import type { AiSettings, AiSettingsInput } from '../api/types'
import { genericMessage, sentence } from '../auth/errors'
import {
  DEFAULT_DAILY_TOKEN_CAP,
  providerHost,
  retryAiSettings,
  setAiSettings,
  useAiSettings,
  useAiSettingsFailed,
} from '../notes/ai-settings'
import { Button } from '../ui/button'
import { Dialog } from '../ui/dialog'
import { Field } from '../ui/field'
import { AgentIcon } from '../ui/icons'
import { aiProviders, providerAt, type AiProvider } from './ai-providers'
import { DisconnectDialog } from './disconnect-dialog'
import type { Integration, IntegrationStatus } from './integrations'
import styles from './settings.module.css'

function AiMark() {
  return (
    <span className={styles.mark} aria-hidden="true">
      <AgentIcon size={18} />
    </span>
  )
}

export const ai: Integration = {
  id: 'ai',
  name: 'AI',
  pitch: 'Give spaces an agent that tags, titles and tidies notes, through any OpenAI-compatible API.',
  mark: <AiMark />,
  Connect: AiConnect,
  Manage: AiManage,
}

export function useAiStatus(): IntegrationStatus {
  const saved = useAiSettings()
  const failed = useAiSettingsFailed()
  if (!saved) return failed ? { state: 'failed', retry: retryAiSettings } : { state: 'loading' }
  return saved.configured
    ? { state: 'on', editable: saved.editable, detail: `${saved.model} at ${providerHost(saved.baseUrl)}` }
    : { state: 'off', editable: saved.editable }
}

type AiField = 'baseUrl' | 'apiKey' | 'model' | 'dailyTokenCap'
type Errors = Partial<Record<AiField | 'form', string>>
const aiFields: readonly string[] = ['baseUrl', 'apiKey', 'model', 'dailyTokenCap'] satisfies AiField[]

/** The server's refusal, on the field it's about. */
function serverErrors(err: unknown, prefix = ''): Errors {
  if (err instanceof ApiError && err.status === 400 && err.field && aiFields.includes(err.field)) {
    return { [err.field]: sentence(err.message) }
  }
  if (err instanceof ApiError && err.code === 'ai_test_failed') return { form: prefix + sentence(err.message) }
  return { form: genericMessage(err) }
}

function AiConnect({ onClose }: { onClose: () => void }) {
  return <AiDialog saved={null} onClose={onClose} />
}

/** The settings as saved; a new key replaces the old one, an empty field keeps it. */
function AiManage({ onClose }: { onClose: () => void }) {
  const saved = useAiSettings()
  const [disconnecting, setDisconnecting] = useState(false)
  if (disconnecting && saved) return <AiDisconnect saved={saved} onBack={() => setDisconnecting(false)} onClose={onClose} />
  return <AiDialog saved={saved} onClose={onClose} onDisconnect={() => setDisconnecting(true)} />
}

const providerChoices: readonly (AiProvider | { id: 'other'; name: string })[] = [...aiProviders, { id: 'other', name: 'Other' }]

type ModelList = { for: string } & ({ state: 'loading' } | { state: 'loaded'; ids: string[] } | { state: 'failed'; message: string })

const listKey = (baseUrl: string, apiKey: string) => `${baseUrl.trim().replace(/\/+$/, '')}\n${apiKey.trim()}`

function modelHint(list: ModelList | undefined): string | undefined {
  switch (list?.state) {
    case 'loading':
      return 'Listing the provider’s models…'
    case 'loaded':
      return list.ids.length > 0 ? 'Pick one of the provider’s models, or type a name.' : undefined
    case 'failed':
      return `Couldn’t list the provider’s models: ${list.message}`
    default:
      return undefined
  }
}

function keyHint(provider: AiProvider | undefined, configured: boolean) {
  if (configured) return 'Paste a new key to replace the saved one.'
  if (provider?.keyUrl) {
    return (
      <>
        Make one at{' '}
        <a href={provider.keyUrl} target="_blank" rel="noreferrer">
          {new URL(provider.keyUrl).host}
        </a>
        .
      </>
    )
  }
  if (provider) return `${provider.name} doesn’t check keys: any word will do.`
  return undefined
}

/** Address, key, model and cap; Test calls the model with them before anything's saved. */
function AiDialog({
  saved,
  onClose,
  onDisconnect,
}: {
  saved: AiSettings | null
  onClose: () => void
  onDisconnect?: () => void
}) {
  const titleId = useId()
  const configured = !!saved?.configured
  const [baseUrl, setBaseUrl] = useState(saved?.baseUrl ?? '')
  const [apiKey, setApiKey] = useState('')
  const [model, setModel] = useState(saved?.model ?? '')
  const [cap, setCap] = useState(String(saved?.dailyTokenCap ?? DEFAULT_DAILY_TOKEN_CAP))
  const [errors, setErrors] = useState<Errors>({})
  const [tested, setTested] = useState<string>()
  const [busy, setBusy] = useState<'save' | 'test'>()
  const [otherPicked, setOtherPicked] = useState(false)
  const [list, setList] = useState<ModelList>()
  const listed = useRef(0)
  const addressRef = useRef<HTMLInputElement>(null)
  const providersId = useId()
  const modelsId = useId()

  const provider = providerAt(baseUrl)
  const choice = provider?.id ?? (otherPicked || baseUrl.trim() ? 'other' : undefined)
  // A list loaded for another address or key no longer applies.
  const shown = list?.for === listKey(baseUrl, apiKey) ? list : undefined
  const ids = shown?.state === 'loaded' ? shown.ids : []

  /** Lists the provider's models, once the address and a key are in. */
  async function listModels(address = baseUrl, key = apiKey) {
    const savedAddress = !!saved?.configured && listKey(address, '') === listKey(saved.baseUrl, '')
    // The saved key only goes to the address it was saved with, not to whatever's typed.
    if (!address.trim() || (!key.trim() && !savedAddress)) return
    const id = listKey(address, key)
    if (list?.for === id && list.state !== 'failed') return
    const n = ++listed.current
    setList({ for: id, state: 'loading' })
    try {
      const res = await settings.aiModels({ baseUrl: address.trim(), ...(key.trim() && { apiKey: key.trim() }) })
      if (n === listed.current) setList({ for: id, state: 'loaded', ids: res.models.map((m) => m.id) })
    } catch (err) {
      if (n !== listed.current) return
      // A refused address or key shows on its field at Test or Save; here, only what the provider said.
      if (err instanceof ApiError && err.code === 'ai_failed') setList({ for: id, state: 'failed', message: err.message })
      else setList(undefined)
    }
  }

  // Once, for the saved provider; afterwards leaving the address or key field lists again.
  // oxlint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => void (saved?.configured && listModels(saved.baseUrl, '')), [])

  function pick(p: AiProvider | 'other') {
    setErrors({})
    setTested(undefined)
    if (p === 'other') {
      setOtherPicked(true)
      if (provider) setBaseUrl('')
      addressRef.current?.focus()
      return
    }
    setOtherPicked(false)
    setBaseUrl(p.baseUrl)
    void listModels(p.baseUrl, apiKey)
  }

  function input(): AiSettingsInput | undefined {
    const found: Errors = {}
    if (!baseUrl.trim()) found.baseUrl = 'Enter the API’s address.'
    if (!configured && !apiKey.trim()) found.apiKey = 'Paste the provider’s API key.'
    if (!model.trim()) found.model = 'Enter the model’s name.'
    const tokens = Number(cap.trim() || '0')
    if (!Number.isInteger(tokens) || tokens < 0) found.dailyTokenCap = 'Enter a whole number of tokens.'
    setErrors(found)
    setTested(undefined)
    if (Object.keys(found).length > 0) return undefined
    return {
      baseUrl: baseUrl.trim(),
      model: model.trim(),
      dailyTokenCap: tokens,
      ...(apiKey.trim() && { apiKey: apiKey.trim() }),
    }
  }

  async function save(e: FormEvent) {
    e.preventDefault()
    const body = input()
    if (!body) return
    setBusy('save')
    try {
      setAiSettings(await settings.saveAi(body))
      onClose()
    } catch (err) {
      setErrors(serverErrors(err))
      setBusy(undefined)
    }
  }

  async function test() {
    const body = input()
    if (!body) return
    setBusy('test')
    try {
      const res = await settings.testAi(body)
      setTested(`Connected. ${res.model} called a tool, in ${(res.latencyMs / 1000).toFixed(1)} s.`)
    } catch (err) {
      setErrors(serverErrors(err, 'The test failed: '))
    } finally {
      setBusy(undefined)
    }
  }

  const edit = (set: (v: string) => void, v: string) => {
    set(v)
    setErrors({})
    setTested(undefined)
  }

  return (
    <Dialog labelledBy={titleId} onClose={busy ? undefined : onClose}>
      <form className={styles.dialog} onSubmit={(e) => void save(e)} noValidate>
        <h2 id={titleId} className={styles.dialogTitle}>
          {saved ? 'AI' : 'Connect AI'}
        </h2>
        {!saved && (
          <p className={styles.dialogText}>
            Any OpenAI-compatible API works, with a model that supports tool calls. The key stays on this server.
          </p>
        )}
        <div className={styles.fieldGroup}>
          <span id={providersId} className={styles.groupLabel}>
            Provider
          </span>
          <div className={`${styles.segmented} ${styles.providers}`} role="radiogroup" aria-labelledby={providersId}>
            {providerChoices.map((p) => (
              <button
                key={p.id}
                type="button"
                role="radio"
                aria-checked={choice === p.id}
                className={styles.segment}
                disabled={!!busy}
                onClick={() => pick(p.id === 'other' ? 'other' : p)}
              >
                {p.name}
              </button>
            ))}
          </div>
        </div>
        <Field
          ref={addressRef}
          label="API address"
          type="url"
          mono
          autoComplete="off"
          spellCheck={false}
          placeholder="https://api.openai.com/v1"
          value={baseUrl}
          onChange={(e) => edit(setBaseUrl, e.target.value)}
          onBlur={() => void listModels()}
          error={errors.baseUrl}
          hint={provider?.id === 'ollama' ? 'Change localhost if Ollama runs on another machine.' : undefined}
          readOnly={!!busy}
          autoFocus
        />
        <Field
          label="API key"
          type="password"
          mono
          autoComplete="off"
          spellCheck={false}
          placeholder={configured ? `Saved · …${saved?.keyHint ?? ''}` : (provider?.keyPlaceholder ?? 'sk-…')}
          value={apiKey}
          onChange={(e) => edit(setApiKey, e.target.value)}
          onBlur={() => void listModels()}
          error={errors.apiKey}
          hint={keyHint(provider, configured)}
          readOnly={!!busy}
        />
        <Field
          label="Model"
          type="text"
          mono
          autoComplete="off"
          spellCheck={false}
          placeholder="gpt-4.1-mini"
          value={model}
          onChange={(e) => edit(setModel, e.target.value)}
          error={errors.model}
          hint={modelHint(shown)}
          list={ids.length > 0 ? modelsId : undefined}
          readOnly={!!busy}
        >
          {ids.length > 0 && (
            <datalist id={modelsId}>
              {ids.map((id) => (
                <option key={id} value={id} />
              ))}
            </datalist>
          )}
        </Field>
        <Field
          label="Daily tokens per space"
          type="text"
          inputMode="numeric"
          autoComplete="off"
          value={cap}
          onChange={(e) => edit(setCap, e.target.value)}
          error={errors.dailyTokenCap}
          readOnly={!!busy}
        />
        {errors.form && (
          <p role="alert" className={styles.error}>
            {errors.form}
          </p>
        )}
        {tested && (
          <p role="status" className={styles.success}>
            {tested}
          </p>
        )}
        <div className={styles.dialogActions}>
          {onDisconnect && (
            <Button variant="danger-text" size="md" className={styles.leading} onClick={onDisconnect} disabled={!!busy}>
              Disconnect…
            </Button>
          )}
          <Button size="md" onClick={onClose} disabled={!!busy}>
            Cancel
          </Button>
          <Button size="md" onClick={() => void test()} busy={busy === 'test'} disabled={!!busy && busy !== 'test'}>
            Test
          </Button>
          <Button variant="solid" size="md" type="submit" busy={busy === 'save'} disabled={!!busy && busy !== 'save'}>
            {saved ? 'Save' : 'Connect'}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}

function AiDisconnect({ saved, onBack, onClose }: { saved: AiSettings; onBack: () => void; onClose: () => void }) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string>()

  async function disconnect() {
    setBusy(true)
    try {
      await settings.removeAi()
      setAiSettings({ ...saved, configured: false, baseUrl: '', model: '', keyHint: '', dailyTokenCap: DEFAULT_DAILY_TOKEN_CAP })
      onClose()
    } catch (err) {
      setError(genericMessage(err))
      setBusy(false)
    }
  }

  return (
    <DisconnectDialog title="Disconnect AI?" error={error} busy={busy} onBack={onBack} onDisconnect={() => void disconnect()}>
      Spaces’ agents stop until it’s connected again. The API key is removed from the server.
    </DisconnectDialog>
  )
}
