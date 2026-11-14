import * as React from 'react'
import * as ClientModules from '@deepseek-ai/dsh-client-modules/src/client/index.ts'
import { Context } from '@deepseek-ai/cordis'
import Loader from '@deepseek-ai/cordis-plugin-loader'
import { getStaticModules } from '@deepseek-ai/dsh-client-web'
import * as UiRenderer from '@deepseek-ai/dsh-client-ui-renderer/src/client/index.ts'
import type { ClientBundleRegistration, WebBootGraph, WebBootEntry } from '@deepseek-ai/dsh-client-modules/client'
import type { Context as CordisContext } from '@deepseek-ai/cordis'
import type { SlotMap } from '@deepseek-ai/dsh-client-ui-slots'
import { mountClient } from '@deepseek-ai/dsh-client-web/src/mount.ts'
import { bootClient } from '@deepseek-ai/dsh-client-web/src/boot-client.ts'
import './style.css'

const MODULES_ID = '@deepseek-ai/dsh-client-modules'
const RENDERER_ID = '@deepseek-ai/dsh-client-ui-renderer'
const LOCALE_ID = '@comicine/dsh-android-client-locale'
const FRAME_ID = '@comicine/dsh-android-plugin-frame'
const ROOT = document.getElementById('root')
const activePackages = new Map<string, UiPluginPackage>()
const statusListeners = new Set<() => void>()
let statusRows: readonly PluginUiStatus[] = []
let focusedPackageName: string | undefined
let surface: 'plugin-ui' | 'chat-input' = 'plugin-ui'
let inputState: AndroidInputState = { sessionId: undefined, text: '', selectionStart: 0, selectionEnd: 0, revision: 0, locked: false }
let context: Context | undefined
let moduleSystem: ReturnType<typeof createClientModuleSystem> | undefined
let mounting: Promise<void> | undefined

declare module '@deepseek-ai/dsh-client-ui-slots' {
  interface SlotMap {
    'shell.bottom': { kind: 'single'; scope: 'root' }
    'shell.overlay': { kind: 'list'; scope: 'root' }
    'plugins.bundle.config': { kind: 'keyed'; scope: 'root'; owner: { view: 'summary' | 'page'; form?: unknown } }
    'plugins.detail.actions': { kind: 'list'; scope: 'root'; owner: { subject: unknown } }
    'plugins.detail.badge': { kind: 'list'; scope: 'root'; owner: { subject: unknown } }
    'plugins.detail.section': { kind: 'list'; scope: 'root'; owner: { subject: unknown } }
    'conversation.input.activity': { kind: 'single'; scope: 'session'; owner: { locked: boolean; onActiveChange: (active: boolean) => void } }
  }
}

interface AndroidInputState {
  sessionId: string | undefined
  text: string
  selectionStart: number
  selectionEnd: number
  revision: number
  locked: boolean
}

interface UiPluginPackage {
  id: string
  packageName: string
  version: string
  sourceSha256: string
  enabled: boolean
  manifestJson: string
}

interface PluginUiStatus {
  id: string
  state: string
  detail?: string
}

interface AndroidPluginUiBridge {
  onRuntimeReady(): void
  onRuntimeError(message: string): void
  onInputUiAvailability(available: boolean): void
  insertInputText(text: string, start: number, end: number, revision: number, sessionId: string): boolean
  setInputDraft(text: string, sessionId: string): boolean
  submitInput(sessionId: string): void
}

declare global {
  interface Window {
    __ModuleLoader__?: ClientModuleTarget
    __dshAndroidPluginUi?: {
      syncPlugins(payload: string): Promise<void>
      setLocale(language: string): void
      dispose(): Promise<void>
    }
    AndroidPluginUiBridge?: AndroidPluginUiBridge
    __DSH_ANDROID_LANGUAGE__?: string
  }
}

interface ClientModuleTarget {
  mode: 'queue' | 'live'
  pendingQueue: ClientBundleRegistration[]
  load(registration: ClientBundleRegistration): void
}

function publishStatuses(rows: readonly PluginUiStatus[]): void {
  statusRows = rows
  for (const listener of statusListeners) listener()
}

function usePluginStatuses(): readonly PluginUiStatus[] {
  return React.useSyncExternalStore(
    (listener) => {
      statusListeners.add(listener)
      return () => statusListeners.delete(listener)
    },
    () => statusRows,
    () => statusRows,
  )
}

function runtimeStatus(ctx: Context, packages: readonly UiPluginPackage[]): PluginUiStatus[] {
  return packages.map((plugin) => {
    const entry = ctx.loader.entries().find((candidate) => candidate.options.name === plugin.packageName)
    if (entry === undefined) return { id: plugin.packageName, state: 'not loaded' }
    const state = entry.fiber?.state
    if (state === 2) return { id: plugin.packageName, state: 'active' }
    if (state === 3) return { id: plugin.packageName, state: 'failed', detail: 'Cordis reported a plugin activation failure. See Android execution logs.' }
    const missing = Object.keys(entry.fiber?.inject ?? {}).filter((service) => ctx.get(service) === undefined)
    return {
      id: plugin.packageName,
      state: 'waiting',
      detail: missing.length > 0 ? `Waiting for: ${missing.join(', ')}` : undefined,
    }
  })
}

function PluginFrame(props: {
  renderSlot: (name: string, owner?: object, options?: { entryKey?: string }) => React.ReactNode
}): React.ReactNode {
  const statuses = usePluginStatuses()
  const [inputActivity, setInputActivity] = React.useState(false)
  const inputSlotCount = context?.slots.entries('conversation.input.activity').length ?? 0
  React.useEffect(() => {
    if (surface === 'chat-input') window.AndroidPluginUiBridge?.onInputUiAvailability(inputSlotCount > 0)
  }, [inputSlotCount, statuses])

  if (surface === 'chat-input') {
    return React.createElement('div', { className: 'plugin-frame__input', 'data-chat-input': '' },
      props.renderSlot('conversation.input.activity', {
        locked: inputState.locked,
        onActiveChange: setInputActivity,
      }))
  }
  const activeCount = statuses.filter((row) => row.state === 'active').length
  const focusedPlugin = focusedPackageName ? activePackages.get(focusedPackageName) : undefined
  const subject = focusedPlugin ? {
    kind: 'bundle',
    pkg: {
      name: focusedPlugin.packageName,
      version: focusedPlugin.version,
      installed: true,
      availability: 'installed',
      official: false,
      enabled: focusedPlugin.enabled,
      rows: [],
    },
  } : undefined
  return React.createElement(
    'div',
    { className: 'plugin-frame' },
    React.createElement(
      'header',
      { className: 'plugin-frame__header' },
      React.createElement('strong', null, 'Upstream plugin interface'),
      React.createElement('span', { className: 'plugin-frame__count' }, `${activeCount} active`),
    ),
    statuses.length > 0 && statuses.some((row) => row.state !== 'active')
      ? React.createElement(
        'section',
        { className: 'plugin-frame__status', 'aria-label': 'Plugin status' },
        ...statuses.filter((row) => row.state !== 'active').map((row) => React.createElement(
          'div',
          { className: 'plugin-frame__status-row', key: row.id },
          React.createElement('strong', null, `${row.id}: ${row.state}`),
          row.detail ? React.createElement('p', null, row.detail) : null,
        )),
      )
      : null,
    React.createElement('div', { className: 'plugin-frame__content' }, props.renderSlot('shell.bottom', {})),
    props.renderSlot('shell.overlay', {}),
    focusedPlugin
      ? React.createElement(
        'section',
        { className: 'plugin-frame__package', 'data-package-ui': focusedPlugin.packageName },
        React.createElement('h2', null, focusedPlugin.packageName),
        subject ? props.renderSlot('plugins.detail.actions', { subject }) : null,
        subject ? props.renderSlot('plugins.detail.badge', { subject }) : null,
        subject ? props.renderSlot('plugins.detail.section', { subject }) : null,
        React.createElement('div', { className: 'plugin-frame__config', 'aria-label': `${focusedPlugin.packageName} configuration` },
          props.renderSlot('plugins.bundle.config', { view: 'page' }, { entryKey: focusedPlugin.packageName })),
      )
      : null,
    statuses.length === 0
      ? React.createElement('p', { className: 'plugin-frame__empty' }, 'Enable an installed plugin that contributes a browser interface to display it here.')
      : null,
  )
}

function AndroidPluginFrame(ctx: CordisContext): void {
  ctx.slots.installScope('session', AndroidSessionScopeAdapter as never)
  ctx.slots.register({
    name: 'root',
    id: FRAME_ID,
    children: {
      'shell.bottom': { kind: 'single', scope: 'root' },
      'shell.overlay': { kind: 'list', scope: 'root' },
      'plugins.bundle.config': { kind: 'keyed', scope: 'root' },
      'plugins.detail.actions': { kind: 'list', scope: 'root' },
      'plugins.detail.badge': { kind: 'list', scope: 'root' },
      'plugins.detail.section': { kind: 'list', scope: 'root' },
      'conversation.input.activity': { kind: 'single', scope: 'session' },
    },
  }, PluginFrame)
}

class AndroidLocaleRuntime {
  private snapshot = { active: 'en', revision: 0, locales: [{ id: 'en', label: 'English' }, { id: 'zh', label: '中文' }] }
  private readonly listeners = new Set<() => void>()
  private readonly dictionaries = new Map<string, Record<string, Record<string, string>>>()

  getSnapshot = () => this.snapshot
  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  register(namespace: string, dictionaries: Record<string, Record<string, string>>): () => void {
    if (!namespace || typeof dictionaries !== 'object' || dictionaries === null) {
      throw new TypeError('locale.register requires a namespace and language dictionaries')
    }
    this.dictionaries.set(namespace, dictionaries)
    this.changed()
    return () => {
      if (this.dictionaries.get(namespace) !== dictionaries) return
      this.dictionaries.delete(namespace)
      this.changed()
    }
  }

  addLanguage(language: { id: string; label: string; fallback?: string }): () => void {
    if (!language.id || !language.label) throw new TypeError('locale.addLanguage requires an id and label')
    return () => undefined
  }

  setLocale(language: string): void {
    this.snapshot = { ...this.snapshot, active: language.toLowerCase().startsWith('zh') ? 'zh' : 'en' }
    this.changed()
  }

  bind(namespace: string): (key: string, params?: Record<string, unknown>) => string {
    return (key, params = {}) => {
      const dictionaries = this.dictionaries.get(namespace)
      const language = this.snapshot.active
      const value = dictionaries?.[language]?.[key] ?? dictionaries?.en?.[key] ?? this.dictionaries.get('common')?.[language]?.[key] ?? key
      return value.replace(/\{([^}]+)\}/g, (_match, name: string) => String(params[name] ?? `{${name}}`))
    }
  }

  private changed(): void {
    this.snapshot = { ...this.snapshot, revision: this.snapshot.revision + 1 }
    for (const listener of this.listeners) listener()
  }
}

const emptySessionBinding = Object.freeze({
  key: undefined,
  hooks: Object.freeze({}),
  keyedHooks: Object.freeze({}),
  props: Object.freeze({}),
})
let currentSessionBinding: Record<string, unknown> = emptySessionBinding
const sessionListeners = new Set<() => void>()
let currentSessionContextId: string | undefined
let currentSessionContext: Context | undefined
const emptySessionSource = {
  getSnapshot: () => currentSessionBinding,
  subscribe: (listener: () => void) => {
    sessionListeners.add(listener)
    return () => sessionListeners.delete(listener)
  },
}
const AndroidSessionScopeAdapter = {
  current: emptySessionSource,
  bindingSource: (_target: unknown) => emptySessionSource,
  renderArea: (_binding: unknown, props: { empty?: (() => React.ReactNode) | undefined; children: React.ReactNode }) =>
    (currentSessionBinding.key ? props.children : props.empty?.()) ?? null,
}

function setAndroidInputState(next: AndroidInputState): void {
  inputState = next
  if (next.sessionId === undefined) {
    currentSessionContextId = undefined
    currentSessionContext = undefined
    currentSessionBinding = emptySessionBinding
  } else {
    if (currentSessionContextId !== next.sessionId || currentSessionContext === undefined) {
      currentSessionContextId = next.sessionId
      currentSessionContext = new Context()
    }
    currentSessionBinding = {
      key: next.sessionId,
      ctx: currentSessionContext,
      hooks: Object.freeze({}),
      keyedHooks: Object.freeze({}),
      props: Object.freeze({ sessionId: next.sessionId, inputActions: createInputActions() }),
    }
  }
  for (const listener of sessionListeners) listener()
}

function createInputActions(): Record<string, (...args: never[]) => unknown> {
  return {
    captureInsertion: (() => ({ sessionId: inputState.sessionId, revision: inputState.revision, start: inputState.selectionStart, end: inputState.selectionEnd })) as never,
    insertText: ((text: string, span: { sessionId?: string; revision?: number; start?: number; end?: number }) =>
      typeof span.sessionId === 'string'
      && typeof span.revision === 'number'
      && typeof span.start === 'number'
      && typeof span.end === 'number'
      && window.AndroidPluginUiBridge?.insertInputText(text, span.start, span.end, span.revision, span.sessionId) === true) as never,
    insertQuote: ((text: string) => {
      const span = { sessionId: inputState.sessionId, revision: inputState.revision, start: inputState.selectionStart, end: inputState.selectionEnd }
      const quote = `${text.trim().split('\\n').map(line => `> ${line}`).join('\\n')}\\n`
      return window.AndroidPluginUiBridge?.insertInputText(quote, span.start, span.end, span.revision, span.sessionId ?? '') === true
    }) as never,
    setDraft: ((text: string) => { window.AndroidPluginUiBridge?.setInputDraft(text, inputState.sessionId ?? '') }) as never,
    persistDraft: (() => undefined) as never,
    addAttachments: (() => false) as never,
    removeAttachment: (() => undefined) as never,
    pruneAttachments: (() => undefined) as never,
    submit: (() => { if (inputState.sessionId) window.AndroidPluginUiBridge?.submitInput(inputState.sessionId) }) as never,
  }
}

function AndroidLocalePlugin(ctx: CordisContext): void {
  const locale = new AndroidLocaleRuntime()
  ctx.provide('locale', locale)
  ctx.slots.installLocale(locale)
  window.__dshAndroidPluginUi?.setLocale(window.__DSH_ANDROID_LANGUAGE__ ?? 'en')
}

function routeFor(row: Pick<WebBootEntry, 'id' | 'rev'>): string {
  return `/plugins/??${row.id}/client.js&rev=${encodeURIComponent(row.rev)}`
}

function makeGraph(packages: readonly UiPluginPackage[]): WebBootGraph {
  const rows: WebBootEntry[] = [
    { id: MODULES_ID, rev: 'android-client-modules-0.2.2-rc.3', url: routeFor({ id: MODULES_ID, rev: 'android-client-modules-0.2.2-rc.3' }), inject: [], immediately: true },
    { id: RENDERER_ID, rev: 'android-ui-renderer-0.2.2-rc.3', url: routeFor({ id: RENDERER_ID, rev: 'android-ui-renderer-0.2.2-rc.3' }), inject: [MODULES_ID], immediately: true },
    { id: LOCALE_ID, rev: 'android-locale-1', url: routeFor({ id: LOCALE_ID, rev: 'android-locale-1' }), inject: [RENDERER_ID] },
    { id: FRAME_ID, rev: 'android-frame-1', url: routeFor({ id: FRAME_ID, rev: 'android-frame-1' }), inject: [RENDERER_ID, LOCALE_ID] },
  ]
  for (const plugin of packages) {
    if (!plugin.enabled) continue
    let manifest: Record<string, unknown>
    try { manifest = JSON.parse(plugin.manifestJson) as Record<string, unknown> }
    catch { continue }
    const dsh = manifest.dsh as Record<string, unknown> | undefined
    const client = dsh?.client as Record<string, unknown> | undefined
    if (client?.platform !== 'web') continue
    const rev = plugin.sourceSha256 || `${plugin.version}-unverified`
    rows.push({
      id: plugin.packageName,
      rev,
      url: routeFor({ id: plugin.packageName, rev }),
      ...(Array.isArray(client.inject) ? { inject: client.inject.filter((value): value is string => typeof value === 'string') } : {}),
      ...(Array.isArray(client.external) ? { external: client.external.filter((value): value is string => typeof value === 'string') } : {}),
      ...(client.immediately === true ? { immediately: true } : {}),
    })
  }
  const batches = rows.map((row) => ({ phase: 'application' as const, url: row.url, rev: row.rev, entries: [row.id] }))
  return {
    rev: rows.map((row) => `${row.id}:${row.rev}`).join('|'),
    release: { version: '0.2.2-rc.3-android-client' },
    entries: rows,
    batches,
  }
}

async function publishRuntimeStatuses(packages: readonly UiPluginPackage[]): Promise<void> {
  await context?.loader.await()
  if (context) publishStatuses(runtimeStatus(context, packages.filter((plugin) => plugin.enabled)))
}

async function startRuntime(): Promise<void> {
  if (mounting) return mounting
  mounting = (async () => {
    if (!ROOT) throw new Error('Plugin UI root is missing')
    const packages: UiPluginPackage[] = []
    const graph = makeGraph(packages)
    const staticModules = {
      ...getStaticModules(),
      [RENDERER_ID]: UiRenderer,
      [LOCALE_ID]: { name: LOCALE_ID, inject: ['slots'], apply: AndroidLocalePlugin },
      [FRAME_ID]: { name: FRAME_ID, inject: ['slots', 'locale'], apply: AndroidPluginFrame },
    }
    const target = window.__ModuleLoader__
    if (!target) throw new Error('Upstream Client Module Loader bootstrap is missing')
    moduleSystem = ClientModules.createClientModuleSystem(target as never, {
      id: MODULES_ID,
      exports: ClientModules as unknown as Record<string, unknown>,
    }, {
      boot: graph,
      staticModules,
    })
    context = new Context()
    await bootClient({ ctx: context, modules: moduleSystem, manifest: moduleSystem.manifest })
    await mountClient(context, ROOT)
    await publishRuntimeStatuses(packages)
    window.AndroidPluginUiBridge?.onRuntimeReady()
  })().catch((error: unknown) => {
    const message = error instanceof Error ? error.stack ?? error.message : String(error)
    window.AndroidPluginUiBridge?.onRuntimeError(message)
    document.body.dataset.runtimeError = message
    throw error
  })
  return mounting
}

async function syncPlugins(payload: string): Promise<void> {
  await startRuntime()
  const parsed = JSON.parse(payload) as {
    packages?: UiPluginPackage[]
    language?: string
    focusedPackageName?: string | null
    surface?: 'plugin-ui' | 'chat-input'
    activeSessionId?: string | null
    inputText?: string
    selectionStart?: number
    selectionEnd?: number
    inputRevision?: number
    inputLocked?: boolean
  }
  setLocale(parsed.language ?? 'en')
  surface = parsed.surface === 'chat-input' ? 'chat-input' : 'plugin-ui'
  setAndroidInputState({
    sessionId: parsed.activeSessionId || undefined,
    text: parsed.inputText ?? '',
    selectionStart: parsed.selectionStart ?? (parsed.inputText ?? '').length,
    selectionEnd: parsed.selectionEnd ?? (parsed.inputText ?? '').length,
    revision: parsed.inputRevision ?? 0,
    locked: parsed.inputLocked === true,
  })
  const packages = Array.isArray(parsed.packages) ? parsed.packages : []
  activePackages.clear()
  for (const plugin of packages) activePackages.set(plugin.packageName, plugin)
    focusedPackageName = parsed.focusedPackageName || undefined
  const graph = makeGraph(packages)
  await moduleSystem?.entries.sync(graph)
  await publishRuntimeStatuses(packages)
}

function setLocale(language: string): void {
  document.documentElement.lang = language.toLowerCase().startsWith('zh') ? 'zh-CN' : 'en'
  document.body.dataset.language = document.documentElement.lang
  const locale = context?.get('locale') as AndroidLocaleRuntime | undefined
  locale?.setLocale(language)
}

async function dispose(): Promise<void> {
  const rootContext = context
  context = undefined
  try {
    if (rootContext) await rootContext.fiber.dispose()
  } finally {
    moduleSystem = undefined
    mounting = undefined
    publishStatuses([])
  }
}

window.__dshAndroidPluginUi = { syncPlugins, setLocale, dispose }
void startRuntime().then(() => window.AndroidPluginUiBridge?.onRuntimeReady()).catch(() => undefined)
