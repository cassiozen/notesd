import { settings } from '../api/settings'
import { accountStore } from './account-store'

// For the share menu and Settings; admins can change it, others can't.
const store = accountStore(() => settings.email())

export const useEmailSettings = store.use
export const useEmailSettingsFailed = store.useFailed
export const setEmailSettings = store.set
export const retryEmailSettings = store.retry
export const forgetEmailSettings = store.forget
