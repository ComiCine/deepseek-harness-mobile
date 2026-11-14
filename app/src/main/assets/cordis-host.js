/* Android bridge around the original Cordis and DeepSeek Harness packages. */
(() => {
  const fs = require('node:fs');
  const path = require('node:path');
  const crypto = require('node:crypto');
  const { createRequire } = require('node:module');
  const root = globalThis.__dshPluginRoot;
  const npmRoot = globalThis.__dshNpmRoot;
  const migrationRoot = globalThis.__dshMigrationRoot;
  const rootRequire = createRequire(path.join(root, 'package.json'));
  const { importOriginalPlugin } = rootRequire(path.join(root, 'plugin-importer.mjs'));
  const DEEPSEEK_PROVIDER_PACKAGE = '@deepseek-ai/dsh-llm-deepseek-api-key';
  const pluginEntries = new Map();
  const agents = new Map();
  const activeCredentialKeys = new Map();
  const runtimeEventQueues = new Map();
  const pendingApprovals = new Map();
  const usedApprovalAuditIds = new Set();
  const runtimeEventOverflows = new Set();
  const sessionRoot = path.join(root, 'sessions');
  let providerBaseURL;
  let keystoreApiKey = '';
  let ctx;
  let booting;

  function asPlugin(module) {
    return module?.default ?? module;
  }

  function appRequire(specifier) {
    return rootRequire(specifier);
  }

  async function runNpm(command, args) {
    const previous = process.cwd();
    process.chdir(root);
    const Npm = require(path.join(npmRoot, 'package/lib/npm.js'));
    const npm = new Npm({ npmRoot: path.join(npmRoot, 'package'), argv: [] });
    try {
      await npm.load();
      npm.config.set('ignore-scripts', true, 'cli');
      npm.config.set('audit', false, 'cli');
      npm.config.set('fund', false, 'cli');
      npm.config.set('save-exact', true, 'cli');
      npm.config.set('registry', 'https://registry.npmjs.org/', 'cli');
      npm.config.set('cache', path.join(root, '.npm-cache'), 'cli');
      npm.config.set('loglevel', 'silent', 'cli');
      await npm.exec(command, args);
    } finally {
      try { await npm.unload(); } finally { process.chdir(previous); }
    }
  }

  async function ensurePackageManager() {
    if (fs.existsSync(path.join(root, 'package.json'))) return;
    fs.mkdirSync(root, { recursive: true });
    fs.writeFileSync(path.join(root, 'package.json'), JSON.stringify({
      name: 'deepseek-harness-android-local-plugins', version: '1.0.0', private: true,
    }, null, 2));
  }

  async function ensureCorePackages() {
    await ensurePackageManager();
    const pkg = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));
    if (pkg.dependencies?.['@deepseek-ai/dsh-agent'] === '0.2.2-rc.3'
      && pkg.dependencies?.['@deepseek-ai/dsh-agent-loop'] === '0.2.2-rc.3'
      && pkg.dependencies?.['@deepseek-ai/dsh-llm-deepseek-api-key'] === '0.2.2-rc.3'
      && pkg.dependencies?.['@deepseek-ai/dsh-credentials'] === '0.2.2-rc.3'
      && pkg.dependencies?.['@deepseek-ai/dsh-tools'] === '0.2.2-rc.3') return;
    await runNpm('install', [
      '@deepseek-ai/cordis@4.1.0',
      '@deepseek-ai/cordis-plugin-loader@1.1.0',
      '@deepseek-ai/dsh-agent@0.2.2-rc.3',
      '@deepseek-ai/dsh-agent-loop@0.2.2-rc.3',
      '@deepseek-ai/dsh-credentials@0.2.2-rc.3',
      '@deepseek-ai/dsh-llm@0.2.2-rc.3',
      '@deepseek-ai/dsh-llm-deepseek@0.2.2-rc.3',
      '@deepseek-ai/dsh-llm-deepseek-api-key@0.2.2-rc.3',
      '@deepseek-ai/dsh-scope@0.2.2-rc.3',
      '@deepseek-ai/dsh-session@0.2.2-rc.3',
      '@deepseek-ai/dsh-session-persistence@0.2.2-rc.3',
      '@deepseek-ai/dsh-session-projection@0.2.2-rc.3',
      '@deepseek-ai/dsh-system-prompt@0.2.2-rc.3',
      '@deepseek-ai/dsh-tools@0.2.2-rc.3',
      '@deepseek-ai/dsh-tool-executor-native@0.2.2-rc.3',
      '@deepseek-ai/dsh-user-approval@0.2.2-rc.3',
      '@deepseek-ai/dsh-typert-protocol@0.2.2-rc.3',
      '@deepseek-ai/dsh-util-values@0.2.2-rc.3',
      '@deepseek-ai/dsh-workspace@0.2.2-rc.3',
    ]);
  }

  async function boot() {
    if (ctx) return;
    if (booting) return booting;
    booting = (async () => {
      await ensureCorePackages();
      const { Context } = appRequire('@deepseek-ai/cordis');
      const Loader = asPlugin(appRequire('@deepseek-ai/cordis-plugin-loader'));
      ctx = new Context();
      await ctx.plugin(Loader);
      // The ESM importer lives in the local npm project so native dynamic import
      // preserves Node's ESM resolution and async-module semantics for plugins.
      ctx.loader.internal = {
        version: 'v2',
        async import(specifier) { return importOriginalPlugin(specifier); },
      };

      const [
        llmModule,
        sessionModule,
        sessionProjectionModule,
        systemPromptModule,
        toolsModule,
        nativeExecutorModule,
        agentModule,
        agentLoopModule,
        approvalModule,
        credentialModule,
      ] = await Promise.all([
        importOriginalPlugin('@deepseek-ai/dsh-llm'),
        importOriginalPlugin('@deepseek-ai/dsh-session'),
        importOriginalPlugin('@deepseek-ai/dsh-session-projection'),
        importOriginalPlugin('@deepseek-ai/dsh-system-prompt'),
        importOriginalPlugin('@deepseek-ai/dsh-tools'),
        importOriginalPlugin('@deepseek-ai/dsh-tool-executor-native'),
        importOriginalPlugin('@deepseek-ai/dsh-agent'),
        importOriginalPlugin('@deepseek-ai/dsh-agent-loop'),
        importOriginalPlugin('@deepseek-ai/dsh-user-approval'),
        importOriginalPlugin('@deepseek-ai/dsh-credentials'),
      ]);
      const corePlugins = [
        llmModule.default,
        sessionModule.default,
        sessionProjectionModule.default ?? sessionProjectionModule,
        systemPromptModule.default,
        toolsModule.default,
        nativeExecutorModule.default ?? nativeExecutorModule,
        agentModule.default,
        approvalModule.default,
      ];
      for (const plugin of corePlugins) await ctx.plugin(plugin);

      const { CredentialProvider } = credentialModule;
      class AndroidKeystoreCredentialProvider extends CredentialProvider {
        async resolve(ref) {
          if (ref !== 'DEEPSEEK_API_KEY' || !keystoreApiKey) return undefined;
          return { value: keystoreApiKey, source: 'android-keystore' };
        }

        async describe(ref) {
          const configured = ref === 'DEEPSEEK_API_KEY' && Boolean(keystoreApiKey);
          return {
            configured,
            ...(configured ? { source: 'android-keystore', masked: '••••' } : {}),
            writable: false,
          };
        }

        async set() { throw new Error('Android Keystore credentials are read-only through Cordis'); }
        async unset() { throw new Error('Android Keystore credentials are read-only through Cordis'); }
        async readRecord() { return undefined; }
        async describeRecord() { return { configured: false, writable: false }; }
        async listRecords() { return []; }
        async modifyRecord() { throw new Error('Android Keystore credentials are read-only through Cordis'); }
        async deleteRecord() { throw new Error('Android Keystore credentials are read-only through Cordis'); }
      }
      await ctx.plugin(AndroidKeystoreCredentialProvider);
      await ctx.plugin(agentLoopModule.default, { agents: [] });
      await ctx.fiber.await();
      ctx.on('session/event', (session, event) => {
        persistSession(session);
        queueRuntimeEvent(session.id, {
          kind: 'session',
          eventType: event.type,
          event: JSON.parse(JSON.stringify(event)),
        });
      }, { global: true });
      ctx.on('agent/assistant-stream', ({ agent, frame }) => {
        queueRuntimeEvent(agent.session.id, {
          kind: 'assistant-stream',
          frame: JSON.parse(JSON.stringify(frame)),
        });
      }, { global: true });
      ctx.on('agent/pre-step', ({ agent, step }, next) => {
        const state = agents.get(agent.session.id);
        const limit = state?.configuration.maxAgentSteps;
        if (Number.isSafeInteger(limit) && limit > 0 && step > limit) {
          queueRuntimeEvent(agent.session.id, { kind: 'step-limit', step, limit });
          return { kind: 'reject' };
        }
        return next();
      }, { global: true });
      ctx.on('approval/request', (request) => requestAndroidApproval(request), { global: true });
    })();
    try { await booting; } catch (error) { ctx = undefined; throw error; } finally { booting = undefined; }
  }

  function packagePath(name) {
    if (typeof name !== 'string' || !/^(@[a-z0-9._-]+\/)?[a-z0-9._-]+$/i.test(name)) {
      throw new TypeError('Expected an npm package name, not a file path or URL');
    }
    return path.join(root, 'node_modules', name);
  }

  function hashTree(directory) {
    const hash = crypto.createHash('sha256');
    function walk(current) {
      for (const entry of fs.readdirSync(current, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
        const absolute = path.join(current, entry.name);
        const relative = path.relative(directory, absolute).split(path.sep).join('/');
        if (entry.isDirectory()) walk(absolute);
        else if (entry.isFile()) {
          hash.update(relative); hash.update('\0'); hash.update(fs.readFileSync(absolute)); hash.update('\0');
        }
      }
    }
    walk(directory);
    return hash.digest('hex');
  }

  function packageMetadata(name) {
    const manifestPath = path.join(packagePath(name), 'package.json');
    const json = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
    const lock = JSON.parse(fs.readFileSync(path.join(root, 'package-lock.json'), 'utf8'));
    const lockPath = `node_modules/${name}`;
    return {
      id: name,
      packageName: json.name,
      displayName: json.name,
      version: json.version,
      entryPath: lockPath,
      manifestJson: JSON.stringify(json),
      sourceSha256: hashTree(path.dirname(manifestPath)),
      integrity: lock.packages?.[lockPath]?.integrity ?? '',
      description: json.description ?? '',
      license: json.license ?? '',
    };
  }

  async function installPackage(specifier, localTarball, localPackageName) {
    await ensureCorePackages();
    if (typeof specifier !== 'string' || !specifier.trim()) throw new TypeError('Package name and version are required');
    const args = [];
    if (localTarball) args.push(localTarball);
    else args.push(specifier.trim());
    await runNpm('install', args);
    const installedName = localPackageName || (specifier.trim().startsWith('@')
      ? specifier.trim().split('@').slice(0, 2).join('@')
      : specifier.trim().split('@')[0]);
    const actualName = JSON.parse(fs.readFileSync(path.join(packagePath(installedName), 'package.json'), 'utf8')).name;
    if (actualName !== installedName) throw new Error(`Installed package name mismatch: expected ${installedName}, got ${actualName}`);
    if (!installedName) throw new Error('NPM installed the package, but its package name could not be resolved.');
    return packageMetadata(installedName);
  }

  function entryId(name) {
    return 'android_' + Buffer.from(name, 'utf8').toString('base64url');
  }

  async function ensureDeepSeekProvider(baseURL) {
    if (typeof baseURL !== 'string' || !baseURL.trim()) throw new TypeError('DeepSeek Messages base URL is required');
    const normalized = baseURL.trim().replace(/\/$/, '');
    if (providerBaseURL === normalized && pluginEntries.has(DEEPSEEK_PROVIDER_PACKAGE)) return;
    if (providerBaseURL !== undefined && providerBaseURL !== normalized
      && [...agents.values()].some(state => state.activeRun?.status === 'running')) {
      throw new Error('Cannot change the shared DeepSeek provider endpoint while an Agent turn is running');
    }
    const id = entryId(DEEPSEEK_PROVIDER_PACKAGE);
    const config = { baseURL: normalized, apiKeyEnv: 'DEEPSEEK_API_KEY' };
    if (pluginEntries.has(DEEPSEEK_PROVIDER_PACKAGE)) {
      await ctx.loader.update(id, { disabled: false, config });
    } else {
      await ctx.loader.create({ id, name: DEEPSEEK_PROVIDER_PACKAGE, config });
    }
    await ctx.loader.await();
    const entry = ctx.loader.resolve(id);
    if (!entry.fiber) throw new Error('Cordis Loader could not activate the original DeepSeek API-key provider');
    pluginEntries.set(DEEPSEEK_PROVIDER_PACKAGE, id);
    providerBaseURL = normalized;
  }

  async function activate(name, configJson) {
    await boot();
    const id = entryId(name);
    const config = JSON.parse(configJson || '{}');
    const current = pluginEntries.get(name);
    if (current) {
      await ctx.loader.update(id, { disabled: false, config });
    } else {
      await ctx.loader.create({ id, name, config });
    }
    await ctx.loader.await();
    const entry = ctx.loader.resolve(id);
    if (!entry.fiber) throw new Error(`Cordis Loader did not activate ${name}; inspect the native plugin log for its original import/configuration error.`);
    pluginEntries.set(name, id);
    return JSON.stringify({ id: name, enabled: true, config: entry.fiber.effectiveConfig ?? config });
  }

  async function deactivate(name) {
    await boot();
    const id = pluginEntries.get(name) ?? entryId(name);
    try { await ctx.loader.update(id, { disabled: true }); }
    catch (error) { throw new Error(`Cordis plugin deactivation failed: ${error.message}`); }
    pluginEntries.delete(name);
    return 'true';
  }

  async function updateConfig(name, configJson) {
    await boot();
    const id = pluginEntries.get(name);
    if (!id) return JSON.stringify(JSON.parse(configJson || '{}'));
    const config = JSON.parse(configJson || '{}');
    await ctx.loader.update(id, { config });
    await ctx.loader.await();
    const entry = ctx.loader.resolve(id);
    if (!entry.fiber) throw new Error(`Cordis Loader did not keep ${name} active after configuration update.`);
    return JSON.stringify(entry.fiber.effectiveConfig ?? config);
  }

  async function uninstall(name) {
    await boot();
    const id = pluginEntries.get(name) ?? entryId(name);
    if (pluginEntries.has(name)) await deactivate(name);
    try { ctx.loader.remove(id); } catch { /* not activated in this process */ }
    pluginEntries.delete(name);
    await runNpm('uninstall', [name]);
    return 'true';
  }

  function sessionFile(sessionId) {
    return path.join(sessionRoot, crypto.createHash('sha256').update(sessionId).digest('hex') + '.json');
  }

  function persistSession(session) {
    writeSessionEvents(session.id, session.snapshotEvents());
  }

  function writeSessionEvents(sessionId, events) {
    fs.mkdirSync(sessionRoot, { recursive: true });
    const target = sessionFile(sessionId);
    const temporary = `${target}.${process.pid}.tmp`;
    fs.writeFileSync(temporary, JSON.stringify(events));
    fs.renameSync(temporary, target);
  }

  function appendImportedEvent(events, type, data, time, surface = false) {
    const previousTime = events.at(-1)?.time ?? 0;
    events.push({
      type,
      seq: events.length,
      time: Math.max(previousTime, Number.isSafeInteger(time) && time > 0 ? time : Date.now()),
      data,
      ...(surface ? { surfaceOp: 'append' } : {}),
    });
  }

  function makeImportedAssistantMessage(row, provider, model) {
    const content = [];
    if (typeof row.thinking === 'string' && row.thinking.length > 0) {
      content.push({ type: 'reasoning', text: row.thinking });
    }
    if (typeof row.content === 'string' && row.content.length > 0) {
      content.push({ type: 'text', text: row.content });
    }
    let calls = [];
    try {
      const parsed = typeof row.toolCallsJson === 'string' ? JSON.parse(row.toolCallsJson) : row.toolCallsJson;
      if (Array.isArray(parsed)) calls = parsed;
    } catch { /* A corrupt legacy tool-call field is kept out of the upstream event log. */ }
    for (const call of calls) {
      if (typeof call?.id !== 'string' || typeof call?.name !== 'string') continue;
      content.push({
        type: 'tool-call',
        id: call.id,
        name: call.name,
        arguments: typeof call.arguments === 'string' ? call.arguments : JSON.stringify(call.arguments ?? {}),
      });
    }
    if (content.length === 0) content.push({ type: 'text', text: '' });
    return {
      id: typeof row.id === 'string' && row.id ? row.id : crypto.randomUUID(),
      role: 'assistant',
      source: { kind: 'model', provider, model },
      content,
    };
  }

  async function importLegacySessionFile(sessionId, inputPath, provider, model) {
    await boot();
    if (typeof sessionId !== 'string' || !sessionId.trim()) throw new TypeError('session id is required');
    if (typeof provider !== 'string' || !provider.trim()) throw new TypeError('provider is required');
    if (typeof model !== 'string' || !model.trim()) throw new TypeError('model is required');
    const resolvedInput = path.resolve(inputPath);
    const resolvedRoot = path.resolve(migrationRoot);
    if (!resolvedInput.startsWith(resolvedRoot + path.sep)) throw new Error('Legacy transcript must come from the app cache directory');
    const existing = readSessionEvents(sessionId);
    if (Array.isArray(existing) && existing.length > 0) {
      return JSON.stringify({ imported: false, eventCount: existing.length });
    }
    const rows = JSON.parse(fs.readFileSync(resolvedInput, 'utf8'));
    if (!Array.isArray(rows)) throw new TypeError('Legacy transcript must be a JSON array');
    const events = [];
    let turn = 0;
    let step = 0;
    let turnOpen = false;
    let stepOpen = false;
    const closeStep = () => {
      if (!stepOpen) return;
      appendImportedEvent(events, 'step/end', { turn, step }, Date.now());
      stepOpen = false;
    };
    const closeTurn = () => {
      if (!turnOpen) return;
      closeStep();
      appendImportedEvent(events, 'turn/end', { turn, reason: { kind: 'completed' } }, Date.now());
      turnOpen = false;
    };
    const beginTurn = () => {
      if (turnOpen) return;
      turn += 1;
      step = 0;
      turnOpen = true;
      appendImportedEvent(events, 'turn/start', { turn }, Date.now());
    };
    const beginStep = () => {
      if (stepOpen) return;
      step += 1;
      stepOpen = true;
      appendImportedEvent(events, 'step/start', { turn, step }, Date.now());
    };

    for (const row of rows) {
      if (!row || typeof row !== 'object') continue;
      const role = row.role;
      const createdAt = Number.isSafeInteger(row.createdAt) ? row.createdAt : Date.now();
      if (role === 'user' || role === 'context') {
        closeTurn();
        beginTurn();
        appendImportedEvent(events, 'user/message', {
          id: typeof row.id === 'string' && row.id ? row.id : crypto.randomUUID(),
          role: 'user',
          source: { kind: 'user' },
          content: [{ type: 'text', text: typeof row.content === 'string' ? row.content : '' }],
        }, createdAt, true);
      } else if (role === 'assistant') {
        beginTurn();
        closeStep();
        beginStep();
        const message = makeImportedAssistantMessage(row, provider, model);
        appendImportedEvent(events, 'assistant/message', {
          turn,
          step,
          message,
          stream: [],
          ...(row.status === 'interrupted' ? { interrupted: true } : {}),
        }, createdAt, true);
      } else if (role === 'tool' && typeof row.toolCallId === 'string' && row.toolCallId.length > 0) {
        beginTurn();
        beginStep();
        const message = {
          id: typeof row.id === 'string' && row.id ? row.id : crypto.randomUUID(),
          role: 'tool',
          source: { kind: 'tool', callId: row.toolCallId },
          toolCallId: row.toolCallId,
          ...(row.isError ? { isError: true } : {}),
          content: [{ type: 'text', text: typeof row.content === 'string' ? row.content : '' }],
        };
        appendImportedEvent(events, 'tool/result', { turn, step, message }, createdAt, true);
      }
    }
    closeTurn();
    writeSessionEvents(sessionId, events);
    return JSON.stringify({ imported: true, eventCount: events.length });
  }

  function queueRuntimeEvent(sessionId, event) {
    const queue = runtimeEventQueues.get(sessionId) ?? [];
    if (queue.length >= 4096) {
      runtimeEventOverflows.add(sessionId);
      return;
    }
    queue.push(event);
    runtimeEventQueues.set(sessionId, queue);
  }

  function drainRuntimeEvents(sessionId) {
    const queue = runtimeEventQueues.get(sessionId) ?? [];
    runtimeEventQueues.set(sessionId, []);
    return queue;
  }

  function matchingApprovalAudit(request) {
    const events = request.agent.session.snapshotEvents();
    for (let index = events.length - 1; index >= 0; index -= 1) {
      const event = events[index];
      if (event.type !== 'approval/asked' || usedApprovalAuditIds.has(event.data.id)) continue;
      if (event.data.toolName !== request.toolName) continue;
      if (request.callId !== undefined && event.data.callId !== request.callId) continue;
      usedApprovalAuditIds.add(event.data.id);
      return event.data;
    }
    return undefined;
  }

  function matchingToolArguments(request) {
    if (request.callId === undefined) return '{}';
    const events = request.agent.session.snapshotEvents();
    for (let index = events.length - 1; index >= 0; index -= 1) {
      const event = events[index];
      if (event.type !== 'tool/call' || event.data.callId !== request.callId) continue;
      return typeof event.data.arguments === 'string'
        ? event.data.arguments
        : JSON.stringify(event.data.arguments ?? {});
    }
    return '{}';
  }

  async function requestAndroidApproval(request) {
    if (request.signal?.aborted) return 'cancelled';
    const audit = matchingApprovalAudit(request);
    const requestId = typeof audit?.id === 'string' ? audit.id : crypto.randomUUID();
    const sessionId = request.agent.session.id;
    let resolveAnswer;
    let settled = false;
    const answer = new Promise(resolve => { resolveAnswer = resolve; });
    const settle = (outcome) => {
      if (settled) return false;
      settled = true;
      request.signal?.removeEventListener('abort', onAbort);
      pendingApprovals.delete(requestId);
      resolveAnswer(outcome);
      queueRuntimeEvent(sessionId, { kind: 'approval-resolved', requestId, outcome });
      return true;
    };
    const onAbort = () => settle('cancelled');
    pendingApprovals.set(requestId, { sessionId, settle });
    request.signal?.addEventListener('abort', onAbort, { once: true });
    if (request.signal?.aborted) onAbort();
    if (!settled) {
      const displayReason = request.displayReason && typeof request.displayReason === 'object'
        ? request.displayReason
        : undefined;
      queueRuntimeEvent(sessionId, {
        kind: 'approval-request',
        requestId,
        toolName: request.toolName,
        ...(request.callId === undefined ? {} : { callId: request.callId }),
        ...(request.reason === undefined ? {} : { reason: request.reason }),
        ...(displayReason === undefined ? {} : { displayReason }),
        argumentsJson: matchingToolArguments(request),
      });
    }
    return answer;
  }

  async function resolveApproval(requestId, outcome) {
    if (!['allowed-once', 'rejected'].includes(outcome)) {
      throw new TypeError('Android approval outcome must be allowed-once or rejected');
    }
    const pending = pendingApprovals.get(requestId);
    if (!pending) return JSON.stringify({ resolved: false });
    return JSON.stringify({ resolved: pending.settle(outcome), sessionId: pending.sessionId });
  }

  function readSessionEvents(sessionId) {
    const target = sessionFile(sessionId);
    if (!fs.existsSync(target)) return undefined;
    const events = JSON.parse(fs.readFileSync(target, 'utf8'));
    if (!Array.isArray(events)) throw new Error(`stored session "${sessionId}" is not an upstream event array`);
    return events;
  }

  async function createOrRestoreAgent(
    sessionId,
    provider = 'deepseek-official',
    model = 'deepseek-flash',
    baseURL = 'https://api.deepseek.com/anthropic',
    configurationJson = '{}',
  ) {
    await boot();
    if (typeof sessionId !== 'string' || !sessionId.trim()) throw new TypeError('session id is required');
    if (typeof provider !== 'string' || !provider.trim()) throw new TypeError('provider is required');
    if (typeof model !== 'string' || !model.trim()) throw new TypeError('model is required');
    const configuration = JSON.parse(configurationJson || '{}');
    if (configuration === null || typeof configuration !== 'object' || Array.isArray(configuration)) {
      throw new TypeError('Agent configuration must be a JSON object');
    }
    if (configuration.reasoningEffort !== undefined
      && !['off', 'low', 'high', 'max'].includes(configuration.reasoningEffort)) {
      throw new TypeError('Unsupported DeepSeek reasoning effort');
    }
    if (configuration.maxTokens !== undefined
      && (!Number.isSafeInteger(configuration.maxTokens) || configuration.maxTokens <= 0)) {
      throw new TypeError('Agent maxTokens must be a positive safe integer');
    }
    if (configuration.maxAgentSteps !== undefined
      && (!Number.isSafeInteger(configuration.maxAgentSteps) || configuration.maxAgentSteps <= 0)) {
      throw new TypeError('Agent maxAgentSteps must be a positive safe integer');
    }
    if (configuration.systemPrompt !== undefined && typeof configuration.systemPrompt !== 'string') {
      throw new TypeError('Agent systemPrompt must be a string');
    }
    if (configuration.cwd !== undefined && (typeof configuration.cwd !== 'string' || !path.isAbsolute(configuration.cwd))) {
      throw new TypeError('Agent cwd must be an absolute Android path');
    }
    await ensureDeepSeekProvider(baseURL);
    const normalizedBaseURL = baseURL.trim().replace(/\/$/, '');
    const configurationKey = JSON.stringify({
      provider,
      model,
      baseURL: normalizedBaseURL,
      reasoningEffort: configuration.reasoningEffort,
      maxTokens: configuration.maxTokens,
      maxAgentSteps: configuration.maxAgentSteps,
      systemPrompt: configuration.systemPrompt ?? '',
      cwd: configuration.cwd,
    });
    const current = agents.get(sessionId);
    if (current) {
      if (current.activeRun?.status === 'running' && current.configurationKey !== configurationKey) {
        throw new Error('Agent ' + sessionId + ' is active with another provider configuration');
      }
      if (current.configurationKey !== configurationKey) {
        await releaseAgent(sessionId);
      } else {
        const selection = current.agent.model.current();
        if (selection?.provider !== provider || selection?.model !== model
          || selection?.reasoningEffort !== configuration.reasoningEffort) {
          current.agent.model.select({
            provider,
            model,
            ...(configuration.reasoningEffort === undefined ? {} : { reasoningEffort: configuration.reasoningEffort }),
          });
        }
        current.baseURL = normalizedBaseURL;
        return JSON.stringify({ sessionId, restored: current.restored, status: current.agent.status });
      }
    }
    const sessionModule = await importOriginalPlugin('@deepseek-ai/dsh-session');
    const stored = readSessionEvents(sessionId);
    const replay = Array.isArray(stored) && stored.length > 0 ? stored : undefined;
    const repaired = replay === undefined
      ? undefined
      : [...replay, ...sessionModule.interruptedTurnClosers(replay)];
    const agentOptions = {
      provider,
      model,
      ...(configuration.reasoningEffort === undefined ? {} : { reasoningEffort: configuration.reasoningEffort }),
      ...(configuration.maxTokens === undefined ? {} : { maxTokens: configuration.maxTokens }),
    };
    const handle = await ctx.agents.create({
      sessionId,
      ...(repaired === undefined ? {} : { seed: repaired }),
      ...(configuration.cwd === undefined ? {} : { meta: { cwd: configuration.cwd } }),
      agentOptions,
      setup: (agentCtx) => {
        if (typeof configuration.systemPrompt === 'string' && configuration.systemPrompt.length > 0) {
          agentCtx.systemPrompt.section({
            name: 'android:system-prompt',
            order: agentCtx.systemPrompt.getSectionOrder('DEPLOYMENT_PERSONA_PREFIX'),
            text: configuration.systemPrompt,
            complete: true,
          });
        }
      },
    });
    const state = {
      handle,
      agent: handle.agent,
      restored: replay !== undefined,
      baseURL: normalizedBaseURL,
      configuration,
      configurationKey,
      activeRun: null,
      lastRun: null,
    };
    agents.set(sessionId, state);
    if (repaired !== replay && repaired !== undefined) persistSession(handle.agent.session);
    return JSON.stringify({ sessionId, restored: state.restored, status: handle.agent.status });
  }

  async function requireAgent(sessionId) {
    await boot();
    const current = agents.get(sessionId);
    if (current) return current;
    await createOrRestoreAgent(
      sessionId,
      'deepseek-official',
      'deepseek-flash',
      providerBaseURL ?? 'https://api.deepseek.com/anthropic',
    );
    return agents.get(sessionId);
  }

  async function schemas(sessionId) {
    const state = await requireAgent(sessionId);
    return JSON.stringify(ctx.tools.schemas(state.agent));
  }

  async function executeTool(sessionId, callId, name, argumentsJson) {
    const state = await requireAgent(sessionId);
    const batch = await ctx.tools.execute({
      calls: [{ type: 'tool-call', id: callId, name, arguments: argumentsJson }],
      signal: new AbortController().signal,
      agent: state.agent,
    });
    return JSON.stringify(batch);
  }

  async function preStep(sessionId, hasUserMessage) {
    const state = await requireAgent(sessionId);
    const messages = hasUserMessage ? [{ role: 'user', source: { kind: 'user' }, content: [] }] : [];
    await ctx.waterfall('agent/pre-step', { agent: state.agent, messages }, async () => undefined);
    return 'true';
  }

  async function postExecute(sessionId, name, argumentsJson, resultJson) {
    const state = await requireAgent(sessionId);
    const exec = { agent: state.agent, name, arguments: JSON.parse(argumentsJson) };
    const result = JSON.parse(resultJson);
    const downstream = await ctx.waterfall('tools/post-execute', exec, result, async () => result);
    return JSON.stringify(downstream?.additionalContexts ?? []);
  }

  async function sessionEvents(sessionId) {
    await boot();
    const state = agents.get(sessionId);
    return JSON.stringify(state ? state.agent.session.snapshotEvents() : readSessionEvents(sessionId) ?? []);
  }

  function publishAvailableCredentialKey() {
    keystoreApiKey = activeCredentialKeys.values().next().value ?? '';
  }

  async function submitUserMessage(
    sessionId,
    text,
    apiKey = '',
    provider = 'deepseek-official',
    model = 'deepseek-flash',
    baseURL = 'https://api.deepseek.com/anthropic',
    configurationJson = '{}',
  ) {
    await createOrRestoreAgent(sessionId, provider, model, baseURL, configurationJson);
    const state = agents.get(sessionId);
    if (typeof text !== 'string' || !text.trim()) throw new TypeError('user message must not be empty');
    if (typeof apiKey !== 'string') throw new TypeError('API key must be a string');
    if (state.activeRun?.status === 'running') throw new Error(`agent "${sessionId}" already has an active user turn`);
    if ([...activeCredentialKeys.values()].some(activeKey => activeKey !== apiKey)) {
      throw new Error('Cannot use a different DeepSeek API key while another Agent turn is running');
    }
    const llmModule = await importOriginalPlugin('@deepseek-ai/dsh-llm');
    const run = { id: crypto.randomUUID(), status: 'running', error: undefined };
    state.activeRun = run;
    state.lastRun = run;
    if (apiKey.length > 0) activeCredentialKeys.set(run.id, apiKey);
    publishAvailableCredentialKey();
    try {
      state.agent.followup(llmModule.createUserMessage({
        source: { kind: 'user' },
        content: [{ type: 'text', text }],
      }));
      run.completion = state.agent.whenIdle().then(
        () => { run.status = 'completed'; },
        error => {
          run.status = 'failed';
          const message = String(error?.message ?? error);
          run.error = apiKey ? message.split(apiKey).join('[REDACTED]') : message;
        },
      ).finally(() => {
        activeCredentialKeys.delete(run.id);
        publishAvailableCredentialKey();
      });
    } catch (error) {
      activeCredentialKeys.delete(run.id);
      publishAvailableCredentialKey();
      state.activeRun = null;
      throw error;
    }
    return JSON.stringify({ sessionId, runId: run.id, status: run.status });
  }

  async function agentRunState(sessionId) {
    const state = await requireAgent(sessionId);
    return JSON.stringify({
      sessionId,
      status: state.activeRun?.status ?? state.agent.status,
      runId: state.activeRun?.id ?? state.lastRun?.id ?? null,
      error: state.activeRun?.error ?? state.lastRun?.error ?? null,
      events: drainRuntimeEvents(sessionId),
      eventOverflow: runtimeEventOverflows.has(sessionId),
    });
  }

  async function cancelAgent(sessionId) {
    const state = agents.get(sessionId);
    if (!state) return 'false';
    state.agent.cancel({ kind: 'user' });
    return 'true';
  }

  async function releaseAgent(sessionId) {
    const state = agents.get(sessionId);
    if (!state) return 'true';
    if (state.activeRun?.status === 'running') {
      state.agent.cancel({ kind: 'disposed' });
      await state.activeRun.completion;
    }
    agents.delete(sessionId);
    await state.handle.dispose();
    return 'true';
  }

  async function disposeSession(sessionId) {
    return releaseAgent(sessionId);
  }

  async function deleteSessionData(sessionId) {
    await disposeSession(sessionId);
    fs.rmSync(sessionFile(sessionId), { force: true });
    return 'true';
  }

  async function dispose() {
    if (ctx) {
      for (const sessionId of [...agents.keys()]) await releaseAgent(sessionId);
      await ctx.fiber.dispose();
      ctx = undefined;
      agents.clear();
      pluginEntries.clear();
      activeCredentialKeys.clear();
      keystoreApiKey = '';
    }
    return 'true';
  }

  globalThis.__dshCordisHost = {
    installPackage, packageMetadata, activate, deactivate, updateConfig, uninstall,
    schemas, executeTool, preStep, postExecute, createOrRestoreAgent,
    submitUserMessage, agentRunState, cancelAgent, releaseAgent, importLegacySessionFile,
    resolveApproval, sessionEvents, disposeSession, deleteSessionData, dispose,
  };
})();
