import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { SpaceTrait } from '../api/types'
import { SpaceIcon } from './space-icon'
import { SpaceIdentity } from './space-identity'

const work = { icon: '', name: 'Work', traits: [] as SpaceTrait[] }

describe('SpaceIdentity', () => {
  it('without traits, is the icon and name as before traits', () => {
    const { container } = render(
      <div>
        <SpaceIdentity space={work} nameClassName="name" />
      </div>,
    )
    const { container: before } = render(
      <div>
        <SpaceIcon space={work} />
        <span className="name" title="Work">
          Work
        </span>
      </div>,
    )
    expect(container.innerHTML).toBe(before.innerHTML)
    expect(screen.queryAllByRole('img', { name: /./ })).toEqual([])
  })

  it('follows the name with a glyph per trait, labelled in order', () => {
    const traits: SpaceTrait[] = [
      { key: 'shared', label: 'Shared', summary: 'With Ana' },
      { key: 'published', label: 'Published', summary: 'At x', problem: 'Publishing failed' },
      { key: 'calendar', label: 'Calendar', summary: 'Synced' },
    ]
    const { container } = render(
      <div>
        <SpaceIdentity space={{ ...work, traits }} />
      </div>,
    )
    const glyphs = screen.getByRole('img', { name: 'Shared, Published (Publishing failed), Calendar' })
    expect(container.firstElementChild?.lastElementChild).toBe(glyphs)
    expect(glyphs.previousElementSibling?.textContent).toBe('Work')
    expect([...glyphs.children].map((g) => (g as HTMLElement).dataset.trait)).toEqual(['shared', 'published', 'calendar'])
    expect(glyphs.querySelectorAll('svg')).toHaveLength(3)
    // Only the trait with a problem takes a colour.
    expect([...glyphs.children].map((g) => g.className !== '')).toEqual([false, true, false])
  })

  it('can leave the icon to the caller', () => {
    const { container } = render(
      <div>
        <SpaceIdentity space={work} icon={false} />
      </div>,
    )
    expect(container.querySelector('img')).toBeNull()
    expect(container.textContent).toBe('Work')
  })
})
