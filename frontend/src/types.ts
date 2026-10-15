export type SnowflakeId = string

export interface User {
  id: SnowflakeId
  email: string
  displayName: string
  role: 'USER' | 'ADMIN'
  emailVerified?: boolean
}

export interface DomainTerm {
  id: SnowflakeId
  sourceTerm: string
  targetTerm: string
  note?: string
}

export interface Domain {
  id: SnowflakeId
  code?: string
  name: string
  description: string
  builtIn: boolean
  medicalDisclaimer: boolean
  terms: DomainTerm[]
  configuration?: SceneConfig | null
  revision: number
  configurationVersion?: string
}

export interface SceneConfig {
  domain: string
  documentType: string
  audience: string
  style: string
  preserveRules: string[]
  explanationPreference: string
}
export interface SceneDraft {
  id: SnowflakeId
  name: string
  description: string
  configuration: SceneConfig
  generationVersion: string
}

export interface TranslationTerm {
  id: SnowflakeId
  sourceTerm: string
  targetTerm: string
  partOfSpeech?: string
  ipa?: string
  explanation?: string
  sourceContext?: string
}

export interface Translation {
  id: SnowflakeId
  domainId: SnowflakeId | null
  sourceLanguage: string
  targetLanguage: string
  detectedLanguage: string
  sourceText: string
  translatedText: string
  medicalDisclaimer: boolean
  createdAt: string
  terms: TranslationTerm[]
  alternatives: { meaning: string; note?: string }[]
  sceneRevision?: number | null
  sceneSnapshot?: { name: string; description: string; configuration?: SceneConfig | null; revision: number; medicalDisclaimer: boolean } | null
}

export interface Lookup {
  lookupId: SnowflakeId
  selectedText: string
  meaning: string
  explanation: string
  counterpartText?: string
  learningWord?: string
  chineseMeaning?: string
  collectable: boolean
}

export interface LookupDetails {
  ipa?: string
  partOfSpeech?: string
  exampleSentence: string
  exampleTranslation?: string
}

export interface Vocabulary {
  id: SnowflakeId
  word: string
  partOfSpeech?: string
  ipa?: string
  chineseMeaning: string
  englishDefinition?: string
  exampleSentence?: string
  sourceContext?: string
  sourceType: string
  createdAt: string
}
