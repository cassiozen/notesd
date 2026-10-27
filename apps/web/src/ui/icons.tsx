// Line icons from the design, drawn on a 16-unit grid in currentColor.

type Props = { size?: number }

export function PlusIcon({ size = 15 }: Props) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" aria-hidden="true">
      <path d="M8 3v10M3 8h10" />
    </svg>
  )
}

export function SearchIcon({ size = 14 }: Props) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.6" aria-hidden="true">
      <circle cx="7" cy="7" r="5" />
      <path d="M11 11l3.5 3.5" strokeLinecap="round" />
    </svg>
  )
}

export function SettingsIcon({ size = 16 }: Props) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" aria-hidden="true">
      <path d="M2.5 5h5.5M12 5h1.5M2.5 11h1.5M8 11h5.5" />
      <circle cx="10" cy="5" r="1.5" />
      <circle cx="6" cy="11" r="1.5" />
    </svg>
  )
}

export function TrashIcon({ size = 16 }: Props) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M2.5 4h11M6 4V2.5h4V4M4 4l.7 9.5h6.6L12 4" />
    </svg>
  )
}

export function ChecklistIcon({ size = 17 }: Props) {
  return (
    <svg width={size} height={(size * 15) / 17} viewBox="0 0 18 16" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" aria-hidden="true">
      <circle cx="3.5" cy="4" r="2.4" />
      <circle cx="3.5" cy="12" r="2.4" />
      <path d="M8.5 4h8M8.5 12h8" />
    </svg>
  )
}

export function LinkIcon({ size = 16 }: Props) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" aria-hidden="true">
      <path d="M6.8 9.2a3 3 0 004.2 0l2.2-2.2a3 3 0 00-4.2-4.2l-1 1" />
      <path d="M9.2 6.8a3 3 0 00-4.2 0L2.8 9a3 3 0 004.2 4.2l1-1" />
    </svg>
  )
}

export function ComposeIcon({ size = 20 }: Props) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M13 9v4.5H2.5V3H7" />
      <path d="M11.5 1.8l2.7 2.7L8.5 10.2 5.6 10.5l.3-2.9z" />
    </svg>
  )
}

export function ChevronIcon({ size = 12 }: Props) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M6 3.5L10.5 8 6 12.5" />
    </svg>
  )
}

export function SidebarIcon({ size = 16 }: Props) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round" aria-hidden="true">
      <rect x="2" y="3" width="12" height="10" rx="2" />
      <path d="M6.5 3v10" />
    </svg>
  )
}

export function HashIcon({ size = 14 }: Props) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" aria-hidden="true">
      <path d="M6.5 2.5L5 13.5M11 2.5l-1.5 11M3 6h10.5M2.5 10H13" />
    </svg>
  )
}

export function ShareIcon({ size = 16 }: Props) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M8 1.8v8.2M5 4.8l3-3 3 3" />
      <path d="M4.5 7.5H3.5v6h9v-6h-1" />
    </svg>
  )
}

export function MoreIcon({ size = 16 }: Props) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">
      <circle cx="3.5" cy="8" r="1.25" />
      <circle cx="8" cy="8" r="1.25" />
      <circle cx="12.5" cy="8" r="1.25" />
    </svg>
  )
}

export function IndentIcon({ size = 16 }: Props) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M2.5 3h11M7.5 6.5h6M7.5 9.5h6M2.5 13h11" />
      <path d="M2.5 6l2.2 2-2.2 2" />
    </svg>
  )
}

export function OutdentIcon({ size = 16 }: Props) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M2.5 3h11M7.5 6.5h6M7.5 9.5h6M2.5 13h11" />
      <path d="M4.7 6L2.5 8l2.2 2" />
    </svg>
  )
}

export function LockIcon({ size = 14 }: Props) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <rect x="3" y="7" width="10" height="7" rx="2" />
      <path d="M5.5 7V5a2.5 2.5 0 0 1 5 0v2" />
    </svg>
  )
}

export function ImageIcon({ size = 16 }: Props) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <rect x="2" y="3" width="12" height="10" rx="2" />
      <circle cx="6" cy="6.5" r="1.1" />
      <path d="M2.5 11.5l3.5-3 2.5 2 2-1.5 3 2.5" />
    </svg>
  )
}

export function SpacesIcon({ size = 16 }: Props) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round" aria-hidden="true">
      <rect x="2.5" y="2.5" width="4.5" height="4.5" rx="1.2" />
      <rect x="9" y="2.5" width="4.5" height="4.5" rx="1.2" />
      <rect x="2.5" y="9" width="4.5" height="4.5" rx="1.2" />
      <rect x="9" y="9" width="4.5" height="4.5" rx="1.2" />
    </svg>
  )
}

export function MailIcon({ size = 16 }: Props) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <rect x="2" y="3.5" width="12" height="9" rx="2" />
      <path d="M2.5 4.5L8 8.8l5.5-4.3" />
    </svg>
  )
}

export function AppearanceIcon({ size = 16 }: Props) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" aria-hidden="true">
      <circle cx="8" cy="8" r="5.5" />
      <path d="M8 2.5a5.5 5.5 0 0 1 0 11z" fill="currentColor" stroke="none" />
    </svg>
  )
}

export function CloudIcon({ size = 16 }: Props) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M4.5 12.5h7a3 3 0 0 0 .4-5.97A4 4 0 0 0 4.2 7.6 2.5 2.5 0 0 0 4.5 12.5z" />
    </svg>
  )
}
