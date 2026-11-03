import { useEffect, useState } from 'react'
import { agent, type AgentRun } from '../api/agent'
import { useAgentConfigs } from './agent-config'

const DISMISSED_KEY = 'notes.agent.dismissedRuns'

function dismissed(): string[] {
  try {
    const raw = localStorage.getItem(DISMISSED_KEY)
    return raw ? (JSON.parse(raw) as string[]) : []
  } catch {
    return []
  }
}

/**
 * The note's latest agent run, if it failed and wasn't dismissed. Fetched when the note opens; from
 * the editor milestone it arrives live.
 */
export function useFailedRun(noteId: string | null): { run: AgentRun | null; dismiss: () => void } {
  const configs = useAgentConfigs()
  const any = (configs?.size ?? 0) > 0
  const [found, setFound] = useState<{ noteId: string; run: AgentRun } | null>(null)

  useEffect(() => {
    if (!noteId || !any) return
    const abort = new AbortController()
    agent.runs({ note: noteId, limit: 1 }, abort.signal).then(
      ({ runs }) => {
        const run = runs[0]
        if (run?.status === 'failed' && !dismissed().includes(run.id)) setFound({ noteId, run })
      },
      () => {
        // Offline or no server: nothing to say.
      },
    )
    return () => abort.abort()
  }, [noteId, any])

  const run = found && found.noteId === noteId ? found.run : null
  return {
    run,
    dismiss: () => {
      if (!run) return
      try {
        // The last few are enough: a newer run replaces an old failure anyway.
        localStorage.setItem(DISMISSED_KEY, JSON.stringify([run.id, ...dismissed()].slice(0, 50)))
      } catch {
        // Not remembered; it shows again next time the note opens.
      }
      setFound(null)
    },
  }
}
