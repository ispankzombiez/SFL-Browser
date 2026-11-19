package com.sfl.browser.wallet

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.reown.sign.client.Sign
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Injects `window.ethereum` (an EIP-1193 provider) into every page and routes
 * what the page asks for:
 *
 *  - `eth_accounts` / `eth_chainId` / `net_version` — answered from session
 *    state, no round trip;
 *  - read-only calls (`eth_call`, `eth_getBalance`, …) — a plain HTTPS POST to
 *    a public RPC, because they need no signature and asking the wallet would
 *    be both slower and more intrusive;
 *  - everything else (`personal_sign`, `eth_sendTransaction`, …) — forwarded to
 *    the wallet over WalletConnect.
 *
 * The plumbing is a promise bridge: `request()` hands Kotlin a callback id, and
 * Kotlin settles it later by evaluating `window.__sflResolve(id, ok, json)`.
 * Nothing blocks the JavaBridge thread — read-only calls go to an executor and
 * wallet calls are inherently asynchronous.
 *
 * The page talks to Kotlin through `SflBridge.request(...)`, exposed with
 * `addJavascriptInterface`. Kotlin talks back only through `evaluateJavascript`,
 * which is why every settlement is posted to the main thread.
 */
class EthereumBridge(private val webView: WebView) : WalletConnectManager.Listener {

    /** Opened when the page asks for accounts and no session exists yet. */
    var onConnectRequested: (() -> Unit)? = null

    /** SDK-level problems worth telling the user about. Not on the main thread. */
    var onWalletError: ((message: String) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val rpcExecutor = Executors.newCachedThreadPool()

    @Volatile
    private var detached = false

    private var lastAccounts: List<String> = emptyList()
    private var lastChain: String = WalletConnectManager.chainId

    /** Page calls waiting for the user to approve a connection: id -> timeout. */
    private val pendingAccounts = HashMap<Int, Runnable>()

    // ------------------------------------------------------------ lifecycle

    /** Call before the first page load; also (re)arms the shim on navigation. */
    fun attach() {
        lastAccounts = listOfNotNull(WalletConnectManager.address)
        lastChain = WalletConnectManager.chainId
        webView.addJavascriptInterface(Bridge(), INTERFACE_NAME)
    }

    fun detach() {
        detached = true
        if (WalletConnectManager.listener === this) WalletConnectManager.listener = null
        // Every unsettled promise would otherwise hang for the life of the page.
        rejectAllPendingAccounts("Browser closed")
        try {
            webView.removeJavascriptInterface(INTERFACE_NAME)
        } catch (_: Exception) {
            // WebView already torn down.
        }
    }

    /**
     * Install the provider. Idempotent, and safe to call on every navigation —
     * `onPageStarted` runs before the page's own scripts in practice, while the
     * `onPageFinished` pass catches the rare page that beat us to it.
     */
    fun installShim() {
        evaluate(SHIM)
        syncStateToPage()
    }

    /** Push current session state into a freshly loaded page without events. */
    private fun syncStateToPage() {
        val accounts = JSONArray(listOfNotNull(WalletConnectManager.address))
        val hex = chainIdToHex(WalletConnectManager.chainId)
        evaluate(
            "window.__sflSet&&window.__sflSet(" +
                "${JSONObject.quote(accounts.toString())},${JSONObject.quote(hex)});",
        )
    }

    // ------------------------------------------------------- JS -> Kotlin

    /** Entry point for the page; see [Bridge] for what is actually exposed. */
    fun request(method: String, paramsJson: String, callbackId: Int) {
        when {
            method in LOCAL_METHODS -> resolveLocal(method, callbackId)

            method == "wallet_switchEthereumChain" || method == "wallet_addEthereumChain" ->
                switchChain(paramsJson, callbackId)

            method in READ_ONLY_METHODS ->
                rpcExecutor.execute { callPublicRpc(method, parseParams(paramsJson), callbackId) }

            else -> requestWallet(method, paramsJson, callbackId)
        }
    }

    // ---------------------------------------------------------- local answers

    private fun resolveLocal(method: String, callbackId: Int) {
        val accounts = listOfNotNull(WalletConnectManager.address)
        when (method) {
            "eth_accounts" -> resolve(callbackId, JSONArray(accounts).toString())

            "eth_requestAccounts" ->
                if (accounts.isNotEmpty()) {
                    resolve(callbackId, JSONArray(accounts).toString())
                } else {
                    // Hold the promise open: the user is off approving in the
                    // wallet, and resolving now would make the page give up.
                    awaitConnection(callbackId)
                }

            "eth_chainId" -> resolve(callbackId, JSONObject.quote(chainIdToHex(WalletConnectManager.chainId)))

            "net_version" -> resolve(
                callbackId,
                JSONObject.quote(chainIdToHex(WalletConnectManager.chainId).substring(2).toLongOrNull(16)?.toString()
                    ?: "137"),
            )

            else -> reject(callbackId, -32601, "Method not found: $method")
        }
    }

    private fun awaitConnection(callbackId: Int) {
        val timeout = Runnable {
            if (pendingAccounts.remove(callbackId) != null) {
                reject(callbackId, 4900, "No wallet connected")
            }
        }
        pendingAccounts[callbackId] = timeout
        mainHandler.postDelayed(timeout, CONNECT_TIMEOUT_MS)
        onConnectRequested?.invoke()
    }

    /** Settles every page call that was waiting for a connection to appear. */
    private fun resolvePendingAccounts() {
        if (pendingAccounts.isEmpty()) return
        val accounts = JSONArray(listOfNotNull(WalletConnectManager.address)).toString()
        val waiting = ArrayList(pendingAccounts.keys)
        pendingAccounts.values.forEach { mainHandler.removeCallbacks(it) }
        pendingAccounts.clear()
        waiting.forEach { resolve(it, accounts) }
    }

    private fun rejectAllPendingAccounts(reason: String) {
        if (pendingAccounts.isEmpty()) return
        val waiting = ArrayList(pendingAccounts.keys)
        pendingAccounts.values.forEach { mainHandler.removeCallbacks(it) }
        pendingAccounts.clear()
        waiting.forEach { reject(it, 4900, reason) }
    }

    // ------------------------------------------------------- chain switching

    private fun switchChain(paramsJson: String, callbackId: Int) {
        val params = parseParams(paramsJson)
        val target = (params as? JSONArray)?.optJSONObject(0)?.optString("chainId").orEmpty()
        val caip2 = hexToChainId(target)
        if (caip2 == null || caip2 !in WalletConnectManager.CHAINS) {
            // EIP-3326: the chain is not one this provider knows how to serve.
            reject(callbackId, 4902, "Unrecognized chain ID: $target")
            return
        }
        // Both chains are in the session namespace, so switching is a local
        // decision — no wallet round trip, no chance of the two disagreeing.
        // `noteChain` republishes the session, which reaches the page as a
        // `chainChanged` event.
        WalletConnectManager.noteChain(caip2)
        resolve(callbackId, "null")
    }

    // ------------------------------------------------------------- public RPC

    private fun callPublicRpc(method: String, params: Any?, callbackId: Int) {
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put("method", method)
            .put("params", params ?: JSONArray())
            .toString()

        val connection = try {
            URL(rpcUrl(WalletConnectManager.chainId)).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            reject(callbackId, -32603, e.message ?: "Cannot reach RPC")
            return
        }

        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20_000
            connection.readTimeout = 20_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()

            if (text.isBlank()) {
                reject(callbackId, -32603, "RPC returned HTTP $code")
                return
            }
            val response = JSONObject(text)
            when {
                response.has("error") -> {
                    val error = response.getJSONObject("error")
                    reject(
                        callbackId,
                        error.optInt("code", -32603),
                        error.optString("message", "RPC error"),
                    )
                }

                response.has("result") -> resolve(callbackId, jsonLiteral(response.get("result")))
                else -> reject(callbackId, -32603, "Malformed RPC response")
            }
        } catch (e: Exception) {
            reject(callbackId, -32603, e.message ?: "RPC request failed")
        } finally {
            connection.disconnect()
        }
    }

    // ---------------------------------------------------------- wallet calls

    private fun requestWallet(method: String, paramsJson: String, callbackId: Int) {
        val chain = WalletConnectManager.chainId
        // `paramsJson` is already the exact JSON the page built, so it is passed
        // through untouched rather than re-serialised through jsonLiteral.
        WalletConnectManager.request(method, paramsJson, chain) { response ->
            when (response) {
                is Sign.Model.JsonRpcResponse.JsonRpcResult ->
                    resolve(callbackId, jsonLiteral(response.result))

                is Sign.Model.JsonRpcResponse.JsonRpcError ->
                    reject(callbackId, response.code, response.message.ifEmpty { "Wallet error" })
            }
        }
    }

    // ------------------------------------------------------------- Kotlin -> JS

    override fun onSessionChanged(address: String?, chainId: String) {
        val accounts = listOfNotNull(address)
        // `emitAccountsChanged` already raises `disconnect` on the transition to
        // an empty account list, so this only has to notice the change.
        emitAccountsChanged(accounts)
        if (chainId != lastChain) emitChainChanged(chainId)
        resolvePendingAccounts()
    }

    override fun onWalletEvent(name: String, data: String) {
        when (name) {
            // `onSessionChanged` already covers these, and the guards below
            // make a duplicate emission a no-op rather than a second event.
            "accountsChanged" -> emitAccountsChanged(parseAddressList(data))

            "chainChanged" -> hexToChainId(data)?.let { emitChainChanged(it) }

            else -> emitRaw(name, JSONObject.quote(data))
        }
    }

    override fun onConnectionState(available: Boolean) = Unit

    override fun onError(message: String) {
        onWalletError?.invoke(message)
    }

    private fun emitAccountsChanged(accounts: List<String>) {
        if (accounts == lastAccounts) return
        lastAccounts = accounts
        emitRaw("accountsChanged", JSONArray(accounts).toString())
        if (accounts.isEmpty()) {
            emitRaw(
                "disconnect",
                JSONObject().put("code", 4900).put("message", "Wallet disconnected").toString(),
            )
        }
    }

    private fun emitChainChanged(caip2: String) {
        if (caip2 == lastChain) return
        lastChain = caip2
        emitRaw("chainChanged", JSONObject.quote(chainIdToHex(caip2)))
    }

    private fun emitRaw(event: String, jsonValue: String) {
        evaluate("window.__sflEmit&&window.__sflEmit(${JSONObject.quote(event)},$jsonValue);")
    }

    private fun resolve(callbackId: Int, jsonValue: String) {
        evaluate("window.__sflResolve&&window.__sflResolve($callbackId,true,${JSONObject.quote(jsonValue)});")
    }

    private fun reject(callbackId: Int, code: Int, message: String) {
        val error = JSONObject().put("code", code).put("message", message).toString()
        evaluate("window.__sflResolve&&window.__sflResolve($callbackId,false,${JSONObject.quote(error)});")
    }

    private fun evaluate(script: String) {
        if (detached) return
        mainHandler.post {
            if (detached) return@post
            try {
                webView.evaluateJavascript(script, null)
            } catch (_: Exception) {
                // WebView went away between the check and the call.
            }
        }
    }

    // -------------------------------------------------------------- helpers

    private fun parseParams(paramsJson: String): Any? {
        if (paramsJson.isBlank()) return JSONArray()
        return try {
            JSONTokener(paramsJson).nextValue()
        } catch (_: Exception) {
            JSONArray()
        }
    }

    /**
     * Turn whatever the SDK handed back into a JSON literal the page can read.
     * Moshi decodes numbers as `Double`, so integral values are printed without
     * a trailing `.0` — `2` and `2.0` are both valid but only the first looks
     * like the chain id or block number the page expects.
     */
    private fun jsonLiteral(value: Any?): String = when (value) {
        null -> "null"
        is String -> JSONObject.quote(value)
        is Boolean -> value.toString()
        is Double -> when {
            !value.isFinite() -> "null"
            value == Math.floor(value) && abs(value) < 9e15 -> value.toLong().toString()
            else -> value.toString()
        }

        is Float -> jsonLiteral(value.toDouble())
        is Int, is Long -> value.toString()
        is Number -> value.toString()
        is Map<*, *> -> buildString {
            append('{')
            var first = true
            for ((k, v) in value) {
                if (!first) append(',')
                first = false
                append(JSONObject.quote(k.toString())).append(':').append(jsonLiteral(v))
            }
            append('}')
        }

        is Collection<*> -> buildString {
            append('[')
            value.forEachIndexed { i, v -> if (i > 0) append(','); append(jsonLiteral(v)) }
            append(']')
        }

        // Android's JSONArray/JSONObject do not implement Collection/Map, so
        // they would otherwise fall through to the string branch and come back
        // quoted — a JSON document buried inside a JSON string.
        is JSONArray, is JSONObject -> value.toString()
        else -> JSONObject.quote(value.toString())
    }

    private fun parseAddressList(data: String): List<String> = try {
        val array = JSONArray(data)
        buildList {
            for (i in 0 until array.length()) {
                array.optString(i).takeIf { it.startsWith("0x") }?.let { add(it) }
            }
        }
    } catch (_: Exception) {
        listOfNotNull(data.trim().takeIf { it.startsWith("0x") })
    }

    private fun chainIdToHex(caip2: String): String {
        val decimal = caip2.substringAfter(":", "137").toLongOrNull() ?: 137L
        return "0x" + decimal.toString(16)
    }

    private fun hexToChainId(hex: String): String? {
        val cleaned = hex.removePrefix("0x").removePrefix("0X")
        val decimal = cleaned.toLongOrNull(16) ?: return null
        return "eip155:$decimal"
    }

    private fun rpcUrl(chainId: String): String = when (chainId.substringAfter(":", "")) {
        "1" -> MAINNET_RPC
        else -> POLYGON_RPC
    }

    /**
     * The object handed to `addJavascriptInterface`. A named class rather than
     * the bridge itself so only this one entry point is ever exposed to pages.
     * Public and annotated because Android reaches it by reflection: a private
     * class or an unannotated method is simply not callable from JavaScript.
     */
    inner class Bridge {
        @JavascriptInterface
        fun request(method: String, paramsJson: String, callbackId: Int) =
            this@EthereumBridge.request(method, paramsJson, callbackId)
    }

    /** Exposed so the activity can tear down cleanly. */
    fun cancelPendingConnection() {
        rejectAllPendingAccounts("Connection cancelled")
    }

    /** Settles calls held open while the user approves in the wallet. */
    fun notifyConnected() = resolvePendingAccounts()

    companion object {
        private const val INTERFACE_NAME = "SflBridge"
        private const val CONNECT_TIMEOUT_MS = 180_000L

        private const val POLYGON_RPC = "https://polygon-rpc.com"
        private const val MAINNET_RPC = "https://cloudflare-eth.com"

        private val LOCAL_METHODS = setOf(
            "eth_accounts", "eth_requestAccounts", "eth_chainId", "net_version",
        )

        private val READ_ONLY_METHODS = setOf(
            "eth_blockNumber", "eth_call", "eth_estimateGas", "eth_gasPrice", "eth_feeHistory",
            "eth_getBalance", "eth_getBlockByHash", "eth_getBlockByNumber", "eth_getCode",
            "eth_getLogs", "eth_getProof", "eth_getStorageAt",
            "eth_getTransactionByBlockHashAndIndex",
            "eth_getTransactionByBlockNumberAndIndex", "eth_getTransactionByHash",
            "eth_getTransactionCount", "eth_getTransactionReceipt", "eth_maxPriorityFeePerGas",
            "eth_syncing", "web3_clientVersion", "eth_sendRawTransaction",
        )

        /**
         * Installed on every navigation. Deliberately free of `$` so it can live
         * in a Kotlin raw string, and of template literals for older WebViews.
         */
        private val SHIM = """
            (function () {
              'use strict';
              if (window.ethereum && window.ethereum.isSfl) return;

              var pending = {};
              var listeners = {};
              var nextId = 1;
              var accounts = [];
              var chainId = '0x89';
              var networkVersion = '137';

              function emit(name, data) {
                var fns = listeners[name];
                if (!fns) return;
                fns.slice().forEach(function (fn) {
                  try { fn(data); } catch (e) { /* a broken listener must not kill the rest */ }
                });
              }

              function applyState(nextAccounts, nextChain) {
                if (nextAccounts) accounts = nextAccounts;
                if (nextChain) {
                  chainId = nextChain;
                  networkVersion = String(parseInt(nextChain, 16));
                }
                ethereum.chainId = chainId;
                ethereum.networkVersion = networkVersion;
                ethereum.selectedAddress = accounts.length ? accounts[0] : null;
              }

              function call(method, params) {
                var id = nextId++;
                return new Promise(function (resolve, reject) {
                  pending[id] = { resolve: resolve, reject: reject };
                  try {
                    SflBridge.request(
                      method,
                      JSON.stringify(params === undefined || params === null ? [] : params),
                      id
                    );
                  } catch (e) {
                    delete pending[id];
                    reject({ code: -32603, message: String(e) });
                  }
                });
              }

              var ethereum = {
                isSfl: true,
                isMetaMask: true,
                chainId: chainId,
                networkVersion: networkVersion,
                selectedAddress: null,

                request: function (args) {
                  if (!args || typeof args.method !== 'string') {
                    return Promise.reject({ code: -32600, message: 'Invalid request' });
                  }
                  return call(args.method, args.params);
                },

                enable: function () { return call('eth_requestAccounts', []); },

                isConnected: function () { return accounts.length > 0; },

                send: function (method, params) {
                  if (method && typeof method === 'object') {
                    var payload = method;
                    if (typeof params === 'function') {
                      call(payload.method, payload.params).then(
                        function (r) { params(null, { id: payload.id, jsonrpc: '2.0', result: r }); },
                        function (e) { params(e, { id: payload.id, jsonrpc: '2.0', error: e }); }
                      );
                      return;
                    }
                    return call(payload.method, payload.params);
                  }
                  return call(method, params);
                },

                sendAsync: function (payload, callback) {
                  call(payload.method, payload.params).then(
                    function (r) { callback(null, { id: payload.id, jsonrpc: '2.0', result: r }); },
                    function (e) { callback(e, { id: payload.id, jsonrpc: '2.0', error: e }); }
                  );
                },

                on: function (name, fn) {
                  if (typeof fn !== 'function') return ethereum;
                  (listeners[name] = listeners[name] || []).push(fn);
                  return ethereum;
                },
                addListener: function (name, fn) { return ethereum.on(name, fn); },
                once: function (name, fn) {
                  var wrapper = function (data) {
                    ethereum.removeListener(name, wrapper);
                    fn(data);
                  };
                  return ethereum.on(name, wrapper);
                },
                removeListener: function (name, fn) {
                  var fns = listeners[name];
                  if (!fns) return ethereum;
                  var i = fns.indexOf(fn);
                  if (i >= 0) fns.splice(i, 1);
                  return ethereum;
                },
                off: function (name, fn) { return ethereum.removeListener(name, fn); },
                removeAllListeners: function (name) {
                  if (name) delete listeners[name]; else listeners = {};
                  return ethereum;
                }
              };

              window.ethereum = ethereum;

              window.__sflResolve = function (id, ok, text) {
                var entry = pending[id];
                if (!entry) return;
                delete pending[id];
                if (ok) {
                  var value;
                  try { value = JSON.parse(text); } catch (e) { value = text; }
                  entry.resolve(value);
                } else {
                  var error;
                  try { error = JSON.parse(text); }
                  catch (e) { error = { code: -32603, message: String(text) }; }
                  entry.reject(error);
                }
              };

              window.__sflEmit = function (name, text) {
                var data;
                try { data = JSON.parse(text); } catch (e) { data = text; }
                if (name === 'accountsChanged') applyState(data, null);
                else if (name === 'chainChanged') applyState(null, data);
                else if (name === 'disconnect') applyState([], null);
                emit(name, data);
              };

              window.__sflSet = function (accountsJson, nextChain) {
                var list;
                try { list = JSON.parse(accountsJson); } catch (e) { list = []; }
                applyState(list, nextChain);
              };
            })();
        """.trimIndent()
    }
}
