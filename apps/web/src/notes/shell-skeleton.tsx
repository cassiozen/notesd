import { Backdrop } from '../ui/backdrop'
import { PHONE, WIDE, useMediaQuery } from '../ui/media'
import empty from './empty.module.css'
import list from './note-list.module.css'
import { loadSidebarHidden } from './shell'
import shell from './shell.module.css'
import tags from './tags-panel.module.css'
import styles from './shell-skeleton.module.css'

/** The shell's frame while boot waits on the server, so a reopened app isn't blank. */
export function ShellSkeleton() {
  const mobile = useMediaQuery(PHONE)
  const wide = useMediaQuery(WIDE)
  return (
    <>
      <Backdrop />
      {/* Hidden from assistive tech: nothing here is real yet. */}
      <div className={shell.app} aria-hidden="true" data-testid="shell-skeleton">
        {wide && !loadSidebarHidden() && (
          <div className={[tags.panel, tags.docked].join(' ')}>
            {/* The panel lays out its one child at the sidebar's width; without it, the bars shrink to nothing. */}
            <div className={tags.inner}>
              <div className={styles.rows}>
                <Bar width="60%" />
                <Bar width="45%" />
                <Bar width="52%" />
              </div>
            </div>
          </div>
        )}
        <div className={list.list}>
          <div className={styles.rows}>
            <Bar width="40%" tall />
            <Bar width="100%" tall />
            {[0, 1, 2, 3, 4].map((i) => (
              <div key={i} className={styles.row}>
                <Bar width="70%" />
                <Bar width="90%" />
              </div>
            ))}
          </div>
        </div>
        {!mobile && <div className={empty.pane} />}
      </div>
    </>
  )
}

function Bar({ width, tall = false }: { width: string; tall?: boolean }) {
  return <span className={[styles.bar, tall && styles.tall].filter(Boolean).join(' ')} style={{ width }} />
}
