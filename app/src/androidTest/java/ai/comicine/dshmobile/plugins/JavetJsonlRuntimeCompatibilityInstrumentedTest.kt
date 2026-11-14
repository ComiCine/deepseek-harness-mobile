package ai.comicine.dshmobile.plugins

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Device probes for Node APIs used by the original JSONL persistence package. */
@RunWith(AndroidJUnit4::class)
class JavetJsonlRuntimeCompatibilityInstrumentedTest {
    private lateinit var context: Context
    private lateinit var runtime: EmbeddedNodeRuntime

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        runtime = EmbeddedNodeRuntime(context)
    }

    @After
    fun tearDown() {
        runtime.close()
    }

    @Test
    fun probesJsonlZstdExportsCompressionFramesAndTruncatedRecovery() = runBlocking {
        val result = evaluateProbe(ZSTD_PROBE)
        Log.i(TAG, "JSONL zlib/Zstd probe: $result")
        assertProbeReported("node:zlib JSONL Zstd", result)
    }

    @Test
    fun probesWorkerThreadsDataMessagesExitAndTermination() = runBlocking {
        val workerEntry = File(runtime.pluginsRoot, "javet-worker-probe-${UUID.randomUUID()}.cjs")
        workerEntry.writeText(WORKER_FIXTURE, Charsets.UTF_8)
        try {
            val expression = WORKER_PROBE.replace("__WORKER_ENTRY__", JSONObject.quote(workerEntry.absolutePath))
            val result = evaluateProbe(expression, timeoutMs = 45_000L)
            Log.i(TAG, "JSONL worker_threads probe: $result")
            assertProbeReported("node:worker_threads JSONL migration verifier", result)
        } finally {
            workerEntry.delete()
        }
    }

    @Test
    fun probesRegisterHooksMappingUpstreamFlockSpecifierToLocalEsmAdapter() = runBlocking {
        val adapter = File(runtime.pluginsRoot, "javet-flock-adapter-${UUID.randomUUID()}.mjs")
        val probe = File(runtime.pluginsRoot, "javet-flock-import-probe-${UUID.randomUUID()}.mjs")
        adapter.writeText(FLOCK_ADAPTER, Charsets.UTF_8)
        probe.writeText(FLOCK_PROBE_MODULE, Charsets.UTF_8)
        try {
            val expression = FLOCK_HOOK_EVALUATOR
                .replace("__PROBE_URL__", JSONObject.quote(probe.toURI().toString()))
                .replace("__ADAPTER_PATH__", JSONObject.quote(adapter.absolutePath))
            val result = evaluateProbe(expression, timeoutMs = 30_000L)
            Log.i(TAG, "JSONL flock ESM-hook probe: $result")
            assertProbeReported("node:module.registerHooks flock adapter mapping", result)
        } finally {
            adapter.delete()
            probe.delete()
        }
    }

    private suspend fun evaluateProbe(expression: String, timeoutMs: Long = 30_000L): JSONObject = try {
        JSONObject(runtime.evaluateAsync(expression, timeoutMs))
    } catch (error: Throwable) {
        JSONObject()
            .put("supported", false)
            .put("status", "unsupported-or-failed")
            .put("reason", error.stackTraceToString())
            .put("timeoutMs", timeoutMs)
    }

    private fun assertProbeReported(capability: String, result: JSONObject) {
        assertTrue(
            "$capability did not return a structured compatibility result: $result",
            result.has("supported"),
        )
        if (!result.optBoolean("supported")) assertTrue("unsupported capabilities need an explicit cause: $result", result.has("reason"))
    }

    private companion object {
        const val TAG = "JavetJsonlRuntimeProbe"

        val ZSTD_PROBE = """
            (async () => {
              const zlib = require('node:zlib');
              const requiredFunctions = [
                'zstdCompress',
                'zstdDecompress',
                'zstdDecompressSync',
                'createZstdCompress',
                'createZstdDecompress',
              ];
              const missingFunctions = requiredFunctions.filter((name) => typeof zlib[name] !== 'function');
              const checksumFlag = zlib.constants && zlib.constants.ZSTD_c_checksumFlag;
              const flushFlag = zlib.constants && zlib.constants.ZSTD_e_flush;
              const report = {
                supported: false,
                nodeVersion: process.version,
                missingFunctions: missingFunctions,
                constants: {
                  ZSTD_c_checksumFlag: typeof checksumFlag === 'number' ? checksumFlag : null,
                  ZSTD_e_flush: typeof flushFlag === 'number' ? flushFlag : null,
                },
                probes: {},
              };
              if (missingFunctions.length > 0 || typeof checksumFlag !== 'number' || typeof flushFlag !== 'number') {
                report.reason = 'Required upstream node:zlib Zstandard exports or constants are absent.';
                return report;
              }

              const inputOne = Buffer.from('{"type":"session.header","id":"frame-one"}\n');
              const inputTwo = Buffer.from('{"type":"session.event","id":"frame-two"}\n');
              const compress = require('node:util').promisify(zlib.zstdCompress);
              const decompress = require('node:util').promisify(zlib.zstdDecompress);
              const checkedOptions = { params: { [checksumFlag]: 1 } };
              let frameOne = null;

              async function measure(name, operation) {
                try {
                  report.probes[name] = { supported: true, details: await operation() };
                } catch (error) {
                  report.probes[name] = {
                    supported: false,
                    reason: String(error && (error.stack || error.message) || error),
                  };
                }
              }

              await measure('checksummedAsyncCompressionAndDecompression', async () => {
                frameOne = await compress(inputOne, checkedOptions);
                const decoded = await decompress(frameOne);
                if (!decoded.equals(inputOne)) throw new Error('Checksummed async Zstd round-trip changed the JSONL bytes.');
                return { compressedBytes: frameOne.length, plainBytes: decoded.length };
              });

              await measure('publicSynchronousFrameDecoder', async () => {
                if (frameOne === null) throw new Error('Async compression did not produce a frame for the sync decoder probe.');
                const decoded = zlib.zstdDecompressSync(frameOne);
                if (!decoded.equals(inputOne)) throw new Error('zstdDecompressSync changed the JSONL frame bytes.');
                return { compressedBytes: frameOne.length, plainBytes: decoded.length };
              });

              await measure('streamCompressionAndDecompression', async () => {
                function collect(stream) {
                  return new Promise((resolve, reject) => {
                    const chunks = [];
                    stream.on('data', (chunk) => chunks.push(Buffer.from(chunk)));
                    stream.once('error', reject);
                    stream.once('end', () => resolve(Buffer.concat(chunks)));
                  });
                }
                const compressor = zlib.createZstdCompress(checkedOptions);
                const encodedPromise = collect(compressor);
                compressor.end(inputTwo);
                const encoded = await encodedPromise;
                const decompressor = zlib.createZstdDecompress();
                const decodedPromise = collect(decompressor);
                decompressor.end(encoded);
                const decoded = await decodedPromise;
                if (!decoded.equals(inputTwo)) throw new Error('Zstd stream round-trip changed the JSONL bytes.');
                return { compressedBytes: encoded.length, plainBytes: decoded.length };
              });

              await measure('concatenatedJsonlFrames', async () => {
                const first = await compress(inputOne, checkedOptions);
                const second = await compress(inputTwo, checkedOptions);
                const joined = Buffer.concat([first, second]);
                const firstDecoded = zlib.zstdDecompressSync(joined.subarray(0, first.length));
                const secondDecoded = zlib.zstdDecompressSync(joined.subarray(first.length));
                if (!firstDecoded.equals(inputOne) || !secondDecoded.equals(inputTwo)) {
                  throw new Error('The framewise public decoder did not recover both concatenated JSONL frames.');
                }
                return {
                  frameCount: 2,
                  totalCompressedBytes: joined.length,
                  firstFrameBytes: first.length,
                  secondFrameBytes: second.length,
                };
              });

              await measure('truncatedFinalFrameRecovery', async () => {
                if (frameOne === null || frameOne.length < 5) {
                  throw new Error('A complete checksummed frame was unavailable for prefix recovery.');
                }
                const prefix = frameOne.subarray(0, frameOne.length - 4);
                const recovered = await decompress(prefix, { finishFlush: flushFlag });
                if (!recovered.equals(inputOne)) {
                  throw new Error('ZSTD_e_flush did not recover the JSONL data before its truncated checksum.');
                }
                return { originalFrameBytes: frameOne.length, prefixBytes: prefix.length, recoveredBytes: recovered.length };
              });

              const failed = Object.entries(report.probes)
                .filter((entry) => !entry[1].supported)
                .map((entry) => entry[0] + ': ' + entry[1].reason);
              report.supported = failed.length === 0;
              if (failed.length > 0) report.reason = failed.join(' | ');
              return report;
            })()
        """.trimIndent()

        const val WORKER_FIXTURE = """
            'use strict';
            const { parentPort, workerData } = require('node:worker_threads');
            if (workerData && workerData.mode === 'echo') {
              parentPort.postMessage({ kind: 'echo', value: workerData.value });
            } else {
              parentPort.postMessage({ kind: 'ready', mode: workerData && workerData.mode });
              setInterval(() => {}, 1000);
            }
        """

        val WORKER_PROBE = """
            (async () => {
              const report = { supported: false, nodeVersion: process.version };
              let echoWorker = null;
              let holdWorker = null;
              function waitForEvent(worker, eventName, timeoutMs) {
                return new Promise((resolve, reject) => {
                  const timer = setTimeout(() => {
                    cleanup();
                    reject(new Error('Timed out waiting for worker ' + eventName + '.'));
                  }, timeoutMs);
                  function cleanup() {
                    clearTimeout(timer);
                    worker.removeListener(eventName, onEvent);
                    worker.removeListener('error', onError);
                  }
                  function onEvent() {
                    const args = Array.prototype.slice.call(arguments);
                    cleanup();
                    resolve(args.length === 1 ? args[0] : args);
                  }
                  function onError(error) {
                    cleanup();
                    reject(error);
                  }
                  worker.once(eventName, onEvent);
                  if (eventName !== 'error') worker.once('error', onError);
                });
              }

              try {
                const workerThreads = require('node:worker_threads');
                if (typeof workerThreads.Worker !== 'function') {
                  throw new Error('node:worker_threads does not export Worker.');
                }
                const entry = __WORKER_ENTRY__;
                echoWorker = new workerThreads.Worker(entry, {
                  workerData: { mode: 'echo', value: 'jsonl-worker-data-round-trip' },
                });
                const echoMessagePromise = waitForEvent(echoWorker, 'message', 10000);
                const echoExitPromise = waitForEvent(echoWorker, 'exit', 10000);
                const echoResults = await Promise.all([echoMessagePromise, echoExitPromise]);
                const echoMessage = echoResults[0];
                const echoExitEvent = echoResults[1];
                const echoExitCode = Array.isArray(echoExitEvent) ? echoExitEvent[0] : echoExitEvent;
                if (!echoMessage || echoMessage.kind !== 'echo' || echoMessage.value !== 'jsonl-worker-data-round-trip') {
                  throw new Error('workerData or parentPort message round-trip failed: ' + JSON.stringify(echoMessage));
                }
                if (echoExitCode !== 0) throw new Error('Echo worker exited with code ' + echoExitCode + '.');

                holdWorker = new workerThreads.Worker(entry, { workerData: { mode: 'hold' } });
                const ready = await waitForEvent(holdWorker, 'message', 10000);
                if (!ready || ready.kind !== 'ready') throw new Error('The long-lived worker did not send its ready message.');
                const holdExitPromise = waitForEvent(holdWorker, 'exit', 10000);
                const terminationResult = await holdWorker.terminate();
                const holdExitEvent = await holdExitPromise;
                const holdExitCode = Array.isArray(holdExitEvent) ? holdExitEvent[0] : holdExitEvent;
                if (typeof terminationResult !== 'number' || typeof holdExitCode !== 'number') {
                  throw new Error('Worker termination or exit did not return numeric exit codes.');
                }
                report.supported = true;
                report.workerDataAndMessage = echoMessage;
                report.echoExitCode = echoExitCode;
                report.terminateResult = terminationResult;
                report.terminatedWorkerExitCode = holdExitCode;
              } catch (error) {
                report.reason = String(error && (error.stack || error.message) || error);
              } finally {
                const remaining = [echoWorker, holdWorker].filter(Boolean);
                await Promise.all(remaining.map((worker) => worker.terminate().catch(() => undefined)));
              }
              return report;
            })()
        """.trimIndent()

        const val FLOCK_ADAPTER = """
            export async function tryLockExclusive(fd) {
              return { adapter: 'local-esm-adapter', fd: fd };
            }
            export default { tryLockExclusive };
        """

        val FLOCK_PROBE_MODULE = """
            import { registerHooks } from 'node:module';
            import { pathToFileURL } from 'node:url';

            export async function run(adapterPath) {
              const report = { supported: false, nodeVersion: process.version, platform: process.platform, arch: process.arch };
              let hooks = null;
              try {
                if (typeof registerHooks !== 'function') {
                  throw new Error('node:module.registerHooks is not exported by this embedded runtime.');
                }
                const target = '@deepseek-ai/node-addon-system/flock';
                const adapterUrl = pathToFileURL(adapterPath).href;
                hooks = registerHooks({
                  resolve(specifier, context, nextResolve) {
                    if (specifier === target) return { url: adapterUrl, shortCircuit: true };
                    return nextResolve(specifier, context);
                  },
                });
                if (!hooks || typeof hooks.deregister !== 'function') {
                  throw new Error('registerHooks did not return a usable deregistration handle.');
                }
                const flock = await import(target);
                if (!flock || typeof flock.tryLockExclusive !== 'function') {
                  throw new Error('The exact upstream flock specifier did not resolve to the ESM adapter export.');
                }
                const result = await flock.tryLockExclusive(17);
                if (!result || result.adapter !== 'local-esm-adapter' || result.fd !== 17) {
                  throw new Error('The imported module was not the expected local ESM adapter.');
                }
                report.supported = true;
                report.specifier = target;
                report.adapterUrl = adapterUrl;
                report.adapterResult = result;
              } catch (error) {
                report.reason = String(error && (error.stack || error.message) || error);
              } finally {
                if (hooks && typeof hooks.deregister === 'function') {
                  try { hooks.deregister(); }
                  catch (error) { report.deregisterError = String(error && (error.stack || error.message) || error); }
                }
              }
              if (report.deregisterError) report.supported = false;
              return report;
            }
        """.trimIndent()

        val FLOCK_HOOK_EVALUATOR = """
            (async () => {
              const path = require('node:path');
              const { createRequire } = require('node:module');
              const root = globalThis.__dshPluginRoot;
              const rootRequire = createRequire(path.join(root, 'package.json'));
              const { importOriginalPlugin } = rootRequire(path.join(root, 'plugin-importer.mjs'));
              const probe = await importOriginalPlugin(__PROBE_URL__);
              const report = await probe.run(__ADAPTER_PATH__);
              return JSON.stringify(report);
            })()
        """.trimIndent()
    }
}
