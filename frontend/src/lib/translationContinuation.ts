import type { Translation } from '../types'

export const continuationKey = 'lexiforge:last-translation'

export function rememberTranslation(ownerId: string, translation: Translation) {
  sessionStorage.setItem(continuationKey, JSON.stringify({ ownerId, translation }))
}

export function takeTranslation(ownerId: string): Translation | null {
  const saved = sessionStorage.getItem(continuationKey)
  sessionStorage.removeItem(continuationKey)
  if (!saved) return null
  try {
    const value = JSON.parse(saved)
    const translation = value?.translation
    // Old unowned records cannot establish which account created them.
    if (!ownerId || value.ownerId !== ownerId || !translation
      || typeof translation.id !== 'string'
      || typeof translation.sourceText !== 'string'
      || typeof translation.translatedText !== 'string'
      || !['AUTO', 'EN', 'ZH'].includes(translation.sourceLanguage)
      || !['EN', 'ZH'].includes(translation.targetLanguage)) return null
    return translation as Translation
  } catch { return null }
}
