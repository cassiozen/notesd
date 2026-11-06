import { settings } from '../api/settings'
import { accountStore } from './account-store'

// As the server's llm.DefaultDailyTokenCap, which a removed setup reverts to.
export const DEFAULT_DAILY_TOKEN_CAP = 1_000_000

// Admins can change it, others can't.
const store = accountStore(() => settings.ai())

export const useAiSettings = store.use
export const useAiSettingsFailed = store.useFailed
export const setAiSettings = store.set
export const retryAiSettings = store.retry
export const forgetAiSettings = store.forget
