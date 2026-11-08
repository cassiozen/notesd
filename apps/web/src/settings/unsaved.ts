import { createContext, useContext, useEffect, useState } from 'react'

type Sections = Map<symbol, string>

/** What a page's sections hold unsaved: the page asks before it closes over any of it. */
const Unsaved = createContext<Sections | null>(null)

export const UnsavedProvider = Unsaved.Provider

/** For the page: its sections, and what they hold unsaved now ("the agent"). */
export function useUnsavedSections(): { sections: Sections; unsaved: () => string[] } {
  const [sections] = useState<Sections>(() => new Map())
  return { sections, unsaved: () => [...sections.values()] }
}

/** For a section: while unsaved is true, its page asks before closing, naming what: "the agent". */
export function useUnsaved(what: string, unsaved: boolean) {
  const sections = useContext(Unsaved)
  const [key] = useState(() => Symbol(what))
  useEffect(() => {
    if (!sections || !unsaved) return
    sections.set(key, what)
    return () => {
      sections.delete(key)
    }
  }, [sections, key, what, unsaved])
}
