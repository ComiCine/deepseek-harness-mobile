/* Android bridge around the original Cordis and DeepSeek Harness packages. */
(() => {
  const fs = require('node:fs');
  const path = require('node:path');
  const crypto = require('node:crypto');
  const { createRequire } = require('node:module');
  const root = globalThis.__dshPluginRoot;
  const npmRoot = globalThis.__dshNpmRoot;
  const rootRequire = createRequire(path.join(root, 'package.json'));
  const { importOriginalPlugin } = rootRequire(path.join(root, 'plugin-importer.mjs'));
  const pluginEntries = new Map();
  const agents = new Map();
  const sessionRoot = path.join(root, 'sessions');
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
      && pkg.dependencies?.['@deepseek-ai/dsh-tools'] === '0.2.2-rc.3') return;
    await runNpm('install', [
      '@deepseek-ai/cordis@4.1.0',
      '@deepseek-ai/cordis-plugin-loader@1.1.0',
      '@deepseek-ai/dsh-agent@0.2.2-rc.3',
      '@deepseek-ai/dsh-llm@0.2.2-rc.3',
      '@deepseek-ai/dsh-scope@0.2.2-rc.3',
      '@deepseek-ai/dsh-session@0.2.2-rc.3',
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

      const basePlugins = [
        '@deepseek-ai/dsh-agent',
        '@deepseek-ai/dsh-system-prompt',
        '@deepseek-ai/dsh-tools',
        '@deepseek-ai/dsh-tool-executor-native',
        '@deepseek-ai/dsh-session-projection',
      ];
      for (const specifier of basePlugins) await ctx.plugin(asPlugin(appRequire(specifier)));
      await ctx.fiber.await();
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

  function persistSession(agent) {
    fs.mkdirSync(sessionRoot, { recursive: true });
    const target = sessionFile(agent.id);
    const temporary = `${target}.${process.pid}.tmp`;
    fs.writeFileSync(temporary, JSON.stringify(agent.session.snapshotEvents()));
    fs.renameSync(temporary, target);
  }

  async function sessionAgent(sessionId) {
    if (agents.has(sessionId)) return agents.get(sessionId).agent;
    const { Session } = appRequire('@deepseek-ai/dsh-session');
    const { brandString } = appRequire('@deepseek-ai/dsh-brand');
    const { createScope } = appRequire('@deepseek-ai/dsh-scope');
    const id = brandString(sessionId);
    let seed;
    try { seed = JSON.parse(fs.readFileSync(sessionFile(sessionId), 'utf8')); } catch {}
    const session = Session.create(id, Array.isArray(seed) && seed.length ? seed : undefined);
    const agent = {
      id,
      options: {},
      session,
      model: { current: () => undefined, select() {} },
      inbox: { nextTurn: [], nextStep: [], clear() {}, append() {}, prepend() {}, replace() { return false; }, remove() { return false; }, splice() { return []; } },
      status: 'idle',
      followup() {}, steer() {}, inject() {}, send() {}, cancel() {},
      runMaintenance: async (task) => task(new AbortController().signal),
      whenIdle: async () => {},
    };
    const scope = createScope(ctx, agent);
    agent.ctx = scope.ctx;
    const unregister = await ctx.agents.register(agent);
    agents.set(sessionId, { agent, scope, unregister });
    return agent;
  }

  async function schemas(sessionId) {
    await boot();
    const agent = await sessionAgent(sessionId);
    return JSON.stringify(ctx.tools.schemas(agent));
  }

  async function executeTool(sessionId, callId, name, argumentsJson) {
    await boot();
    const agent = await sessionAgent(sessionId);
    const batch = await ctx.tools.execute({
      calls: [{ type: 'tool-call', id: callId, name, arguments: argumentsJson }],
      signal: new AbortController().signal,
      agent,
    });
    persistSession(agent);
    return JSON.stringify(batch);
  }

  async function preStep(sessionId, hasUserMessage) {
    await boot();
    const agent = await sessionAgent(sessionId);
    const messages = hasUserMessage ? [{ role: 'user', source: { kind: 'user' }, content: [] }] : [];
    await ctx.waterfall('agent/pre-step', { agent, messages }, async () => undefined);
    persistSession(agent);
    return 'true';
  }

  async function postExecute(sessionId, name, argumentsJson, resultJson) {
    await boot();
    const agent = await sessionAgent(sessionId);
    const exec = { agent, name, arguments: JSON.parse(argumentsJson) };
    const result = JSON.parse(resultJson);
    const downstream = await ctx.waterfall('tools/post-execute', exec, result, async () => result);
    persistSession(agent);
    return JSON.stringify(downstream?.additionalContexts ?? []);
  }

  async function sessionEvents(sessionId) {
    await boot();
    const agent = await sessionAgent(sessionId);
    return JSON.stringify(agent.session.snapshotEvents());
  }

  async function disposeSession(sessionId) {
    const state = agents.get(sessionId);
    if (!state) return 'true';
    agents.delete(sessionId);
    if (typeof state.unregister === 'function') await state.unregister();
    await state.scope.dispose();
    return 'true';
  }

  async function deleteSessionData(sessionId) {
    await disposeSession(sessionId);
    fs.rmSync(sessionFile(sessionId), { force: true });
    return 'true';
  }

  async function dispose() {
    if (ctx) {
      for (const sessionId of [...agents.keys()]) await disposeSession(sessionId);
      await ctx.fiber.dispose();
      ctx = undefined;
      agents.clear();
      pluginEntries.clear();
    }
    return 'true';
  }

  globalThis.__dshCordisHost = {
    installPackage, packageMetadata, activate, deactivate, updateConfig, uninstall,
    schemas, executeTool, preStep, postExecute, sessionEvents, disposeSession, deleteSessionData, dispose,
  };
})();
