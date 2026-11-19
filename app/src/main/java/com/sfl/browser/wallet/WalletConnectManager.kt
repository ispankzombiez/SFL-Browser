package com.sfl.browser.wallet

import android.app.Application
import android.content.Context
import android.util.Log
import com.reown.android.Core
import com.reown.android.CoreClient
import com.reown.sign.client.Sign
import com.reown.sign.client.SignClient
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Own side of the WalletConnect v2 connection: initialise the Reown SDK, pair
 * with a `wc:` URI, keep the active EVM session, and forward JSON-RPC requests
 * to the wallet that approved it.
 *
 * Only **one** thing in the app talks to `SignClient` — the injected
 * [EthereumBridge] — so all session bookkeeping lives here and the bridge stays
 * a thin translation layer.
 *
 * Threading: Reown's own callbacks arrive on its internal scope, and
 * `getListOfActiveSessions()` blocks, so everything public here either hops to
 * a background thread or is documented as such. UI-facing state is published
 * through [listener] on the main thread.
 *
 * Sessions are persisted by the SDK in its own database, so an app restart
 * restores the connection without re-scanning a QR code.
 */
object WalletConnectManager {

    private const val TAG = "SflWallet"

    /** Chains we ask the wallet for. The game lives on Polygon; ETH is the fallback. */
    val CHAINS = listOf("eip155:137", "eip155:1")

    private const val NAMESPACE = "eip155"

    private val WALLET_METHODS = listOf(
        "eth_sendTransaction",
        "eth_signTransaction",
        "personal_sign",
        "eth_sign",
        "eth_signTypedData",
        "eth_signTypedData_v4",
        "wallet_switchEthereumChain",
        "wallet_addEthereumChain",
        "wallet_watchAsset",
    )

    private val WALLET_EVENTS = listOf("chainChanged", "accountsChanged", "disconnect")

    /** Anything the page can ask for without a wallet (routed to a public RPC). */
    private val READ_ONLY_METHODS = setOf(
        "eth_blockNumber", "eth_call", "eth_estimateGas", "eth_gasPrice", "eth_feeHistory",
        "eth_getBalance", "eth_getBlockByHash", "eth_getBlockByNumber", "eth_getCode",
        "eth_getLogs", "eth_getProof", "eth_getStorageAt", "eth_getTransactionByBlockHashAndIndex",
        "eth_getTransactionByBlockNumberAndIndex", "eth_getTransactionByHash",
        "eth_getTransactionCount", "eth_getTransactionReceipt", "eth_maxPriorityFeePerGas",
        "eth_syncing", "net_version", "web3_clientVersion", "eth_chainId",
    )

    /** Methods that answer straight from session state, no wallet round-trip. */
    private val LOCAL_METHODS = setOf("eth_accounts", "eth_chainId", "net_version")

    /** Page-facing callbacks. All invoked on the main thread. */
    interface Listener {
        /** Session appeared, changed or vanished; [address] is null when disconnected. */
        fun onSessionChanged(address: String?, chainId: String) {}

        /** An EIP-1193 event (`accountsChanged`, `chainChanged`, …) from the wallet. */
        fun onWalletEvent(name: String, data: String) {}

        fun onConnectionState(available: Boolean) {}

        fun onError(message: String) {}
    }

    var listener: Listener? = null

    @Volatile
    var initialized = false
        private set

    /** The session topic the bridge sends requests over, or null when disconnected. */
    @Volatile
    var sessionTopic: String? = null
        private set

    /** `0x…` address from the active session, or null. */
    @Volatile
    var address: String? = null
        private set

    /** `eip155:137` from the active session — the chain the page sees by default. */
    @Volatile
    var chainId: String = CHAINS.first()
        private set

    private val pendingConnect = CopyOnWriteArrayList<(Result<String>) -> Unit>()

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Guards [init] against two callers racing into a double setup. */
    private val initLock = Any()

    @Volatile
    private var initStarted = false

    // ------------------------------------------------------------ lifecycle

    /**
     * Initialise Core + Sign exactly once. Safe to call repeatedly; the
     * callback fires when the SDK is ready (or immediately if it already was).
     */
    fun init(context: Context, onReady: (Boolean) -> Unit = {}) {
        synchronized(initLock) {
            if (initialized) {
                onReady(true)
                return
            }
            // A first attempt is still in flight — ride along on it rather
            // than running setup twice (Core's Koin modules do not like that).
            if (initStarted) {
                initWaiters += onReady
                return
            }
            initStarted = true
            initWaiters += onReady
        }

        val app = context.applicationContext as? Application
        if (app == null) {
            finishInit(false)
            return
        }

        val metaData = Core.Model.AppMetaData(
            name = "SFL-Browser",
            description = "Sunflower Land browser with native wallet support",
            url = "https://sunflower-land.com",
            icons = listOf("https://sunflower-land.com/favicon.svg"),
            // Wallets deep-link here after approving; matches the manifest filter.
            redirect = "sflbrowser://wc",
        )

        // Core reports both synchronous setup failures (bad project id) and
        // later relay drops through the same callback, so only the first
        // invocation is allowed to settle the init.
        var setupFailed = false
        CoreClient.initialize(
            application = app,
            projectId = com.sfl.browser.BuildConfig.WC_PROJECT_ID,
            metaData = metaData,
        ) { error ->
            Log.e(TAG, "Core init failed", error.throwable)
            listener?.onError(error.throwable.message ?: "Wallet core failed to start")
            if (!setupFailed) {
                setupFailed = true
                finishInit(false)
            }
        }
        if (setupFailed) return

        SignClient.initialize(Sign.Params.Init(core = CoreClient), onSuccess = {
            initialized = true
            attachDelegate()
            restoreSession()
            finishInit(true)
        }, onError = { error ->
            Log.e(TAG, "Sign init failed", error.throwable)
            listener?.onError(error.throwable.message ?: "Wallet SDK failed to start")
            finishInit(false)
        })
    }

    private val initWaiters = ArrayList<(Boolean) -> Unit>()

    /** Deliver the init outcome to every caller waiting on it, exactly once. */
    private fun finishInit(ok: Boolean) {
        val waiting: List<(Boolean) -> Unit>
        synchronized(initLock) {
            if (initSettled) {
                if (ok) initialized = true
                return
            }
            initSettled = true
            if (ok) {
                initialized = true
            } else {
                // A failed setup must stay retryable: Core tears its Koin
                // modules down, so a later call can start over cleanly.
                initStarted = false
                initSettled = false
            }
            waiting = initWaiters.toList()
            initWaiters.clear()
        }
        waiting.forEach { it(ok) }
    }

    @Volatile
    private var initSettled = false

    private fun attachDelegate() {
        SignClient.setDappDelegate(object : SignClient.DappDelegate {
            override fun onSessionApproved(approvedSession: Sign.Model.ApprovedSession) {
                sessionTopic = approvedSession.topic
                adoptAccounts(approvedSession.namespaces)
                publishSession()
                // Resolve every connect attempt still waiting on the wallet.
                flushConnect(Result.success(address ?: ""))
            }

            override fun onSessionRejected(rejectedSession: Sign.Model.RejectedSession) {
                flushConnect(Result.failure(Exception(rejectedSession.reason)))
            }

            override fun onSessionUpdate(updatedSession: Sign.Model.UpdatedSession) {
                adoptAccounts(updatedSession.namespaces)
                publishSession()
            }

            @Deprecated("Legacy event channel; the wallet also emits through onEvent.")
            override fun onSessionEvent(sessionEvent: Sign.Model.SessionEvent) = Unit

            override fun onSessionEvent(event: Sign.Model.Event) {
                when (event.name) {
                    "accountsChanged" -> {
                        val first = parseAddress(event.data)
                        address = first ?: address
                        publishSession()
                    }

                    "chainChanged" -> chainId = normalizeChain(event.data)
                }
                listener?.onWalletEvent(event.name, event.data)
            }

            override fun onSessionExtend(session: Sign.Model.Session) = publishSession()

            override fun onSessionDelete(deletedSession: Sign.Model.DeletedSession) {
                sessionTopic = null
                address = null
                publishSession()
                flushConnect(Result.failure(Exception("Session closed")))
            }

            override fun onSessionRequestResponse(response: Sign.Model.SessionRequestResponse) {
                // `result.id` is the JSON-RPC id the SDK minted for our request,
                // the same value `SentRequest.requestId` reports.
                val waiter = takeWaiter(response.result.id, response.method)
                if (waiter != null) {
                    waiter(response.result)
                } else {
                    // No matching call — the SDK's wallet-service path emits a
                    // response without ever calling `onSuccess`, so a waiter
                    // can still be registered under its method alone; only a
                    // genuinely orphaned reply lands here.
                    Log.w(TAG, "Orphaned wallet response for ${response.method}")
                }
            }

            override fun onProposalExpired(proposal: Sign.Model.ExpiredProposal) = Unit

            override fun onRequestExpired(request: Sign.Model.ExpiredRequest) {
                takeWaiter(request.id, null)
                    ?.invoke(Sign.Model.JsonRpcResponse.JsonRpcError(request.id, -32000, "Request expired"))
            }

            override fun onConnectionStateChange(state: Sign.Model.ConnectionState) {
                listener?.onConnectionState(state.isAvailable)
            }

            override fun onError(error: Sign.Model.Error) {
                Log.e(TAG, "Sign error", error.throwable)
                listener?.onError(error.throwable.message ?: "Wallet error")
            }
        })
    }

    /** Re-adopts a session the SDK kept in its database from a previous run. */
    private fun restoreSession() {
        Thread {
            try {
                val session = SignClient.getListOfActiveSessions()
                    .firstOrNull { it.namespaces.containsKey(NAMESPACE) }
                if (session != null) {
                    sessionTopic = session.topic
                    adoptAccounts(session.namespaces)
                    publishSession()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not restore session", e)
            }
        }.start()
    }

    // -------------------------------------------------------------- pairing

    /**
     * Pair with a `wc:` URI (from a QR code or a deep link) and propose a
     * session on it. [onResult] receives the wallet's approved address.
     */
    fun connect(uri: String, onResult: (Result<String>) -> Unit) {
        if (!initialized) {
            onResult(Result.failure(Exception("Wallet SDK not ready")))
            return
        }
        pendingConnect += onResult

        // A pairing is created first; `connect` then proposes the session on it.
        CoreClient.Pairing.pair(Core.Params.Pair(uri), onSuccess = {
            val topic = uri.substringAfter("wc:", "").substringBefore("@")
            val pairing = CoreClient.Pairing.getPairings().firstOrNull { it.topic == topic }
            if (pairing == null) {
                flushConnect(Result.failure(Exception("Could not create pairing")))
                return@pair
            }
            propose(pairing)
        }, onError = { error ->
            Log.e(TAG, "Pairing failed", error.throwable)
            flushConnect(Result.failure(error.throwable))
        })
    }

    /**
     * Mint a pairing URI of our own and propose a session on it, so the UI can
     * hand a `wc:` link to a wallet app. [onUri] receives that link once the
     * proposal is away; [onResult] then resolves when the wallet answers —
     * it is queued before the proposal is even sent, so an approval that beats
     * the URI display still lands.
     *
     * `CoreClient.Pairing.create()` blocks on the current thread, hence the
     * dedicated thread.
     */
    fun createConnect(onUri: (Result<String>) -> Unit, onResult: (Result<String>) -> Unit) {
        if (!initialized) {
            onResult(Result.failure(Exception("Wallet SDK not ready")))
            return
        }
        pendingConnect += onResult

        Thread {
            try {
                val pairing = CoreClient.Pairing.create()
                if (pairing == null) {
                    flushConnect(Result.failure(Exception("Could not create a pairing")))
                    return@Thread
                }
                propose(pairing, onUri)
            } catch (e: Exception) {
                Log.e(TAG, "Pairing creation failed", e)
                flushConnect(Result.failure(e))
            }
        }.start()
    }

    private fun propose(pairing: Core.Model.Pairing, onUri: ((Result<String>) -> Unit)? = null) {
        val namespaces = mapOf(
            NAMESPACE to Sign.Model.Namespace.Proposal(
                chains = CHAINS,
                methods = WALLET_METHODS,
                events = WALLET_EVENTS,
            )
        )
        SignClient.connect(
            Sign.Params.ConnectParams(sessionNamespaces = namespaces, pairing = pairing),
            onSuccess = { uri -> onUri?.let { mainHandler.post { it(Result.success(uri)) } } },
            onError = { error ->
                Log.e(TAG, "Session proposal failed", error.throwable)
                flushConnect(Result.failure(error.throwable))
            },
        )
    }

    fun disconnect() {
        val topic = sessionTopic ?: return
        SignClient.disconnect(Sign.Params.Disconnect(topic), onSuccess = {
            sessionTopic = null
            address = null
            publishSession()
        }, onError = { error -> listener?.onError(error.throwable.message ?: "Disconnect failed") })
    }

    // ------------------------------------------------------------- requests

    /**
     * Send a JSON-RPC request to the wallet. [onResponse] fires once, with the
     * wallet's result or the error it returned.
     */
    fun request(
        method: String,
        params: String,
        chainId: String,
        onResponse: (Sign.Model.JsonRpcResponse) -> Unit,
    ) {
        val topic = sessionTopic
        if (topic == null) {
            onResponse(Sign.Model.JsonRpcResponse.JsonRpcError(0, 4900, "No wallet connected"))
            return
        }

        // Registered before dispatch: a fast wallet can reply before the
        // publish ack runs `onSuccess`, and the SDK's wallet-service path
        // never calls it at all. Either way the waiter is already in place.
        val waiter = Waiter(method, onResponse)
        synchronized(waiterLock) { waiters += waiter }

        SignClient.request(
            Sign.Params.Request(
                sessionTopic = topic,
                method = method,
                params = params,
                chainId = chainId,
            ),
            onSuccess = { sent -> waiter.sdkId = sent.requestId },
            onError = { error ->
                removeWaiter(waiter)?.invoke(
                    Sign.Model.JsonRpcResponse.JsonRpcError(0, -32603, error.throwable.message ?: "Request failed"),
                )
            },
        )
    }

    /** One in-flight call from the page to the wallet. */
    private class Waiter(
        val method: String,
        val callback: (Sign.Model.JsonRpcResponse) -> Unit,
    ) {
        /** JSON-RPC id once the SDK reports it; null while still in flight. */
        @Volatile
        var sdkId: Long? = null
    }

    private val waiterLock = Any()
    private val waiters = ArrayList<Waiter>()

    /**
     * Claim the waiter a reply belongs to: exact JSON-RPC id first, then —
     * for a reply that beat the publish ack — the oldest call still awaiting
     * its id for the same method. Returns null when nothing matches.
     */
    private fun takeWaiter(id: Long, method: String?): ((Sign.Model.JsonRpcResponse) -> Unit)? {
        synchronized(waiterLock) {
            val byId = waiters.firstOrNull { it.sdkId == id }
            if (byId != null) {
                waiters.remove(byId)
                return byId.callback
            }
            if (method != null) {
                val byMethod = waiters.firstOrNull { it.sdkId == null && it.method == method }
                if (byMethod != null) {
                    waiters.remove(byMethod)
                    return byMethod.callback
                }
            }
        }
        return null
    }

    private fun removeWaiter(waiter: Waiter): ((Sign.Model.JsonRpcResponse) -> Unit)? =
        synchronized(waiterLock) {
            if (waiters.remove(waiter)) waiter.callback else null
        }

    // ------------------------------------------------------------- helpers

    fun isReadOnly(method: String): Boolean = method in READ_ONLY_METHODS

    fun isLocal(method: String): Boolean = method in LOCAL_METHODS

    fun isWalletMethod(method: String): Boolean = method !in READ_ONLY_METHODS && method !in LOCAL_METHODS

    /** `0x…` for the currently connected session, or null. */
    fun connectedAddress(): String? = address

    fun connectedChainId(): String = chainId

    /**
     * Apply a chain switch the page asked for. Only chains already in the
     * session namespace are accepted, so the wallet never sees a request for a
     * chain it did not agree to serve.
     */
    fun noteChain(caip2: String) {
        if (caip2 !in CHAINS) return
        chainId = caip2
        publishSession()
    }

    private fun adoptAccounts(namespaces: Map<String, Sign.Model.Namespace.Session>) {
        val session = namespaces[NAMESPACE] ?: return
        val account = session.accounts.firstOrNull()
        if (account != null) address = account.substringAfterLast(":")

        // Prefer the namespace's explicit chain list; fall back to the chain
        // prefix of the first account (`eip155:137:0x…`).
        val chain = session.chains?.firstOrNull()
            ?: account?.substringBefore(":", "")?.takeIf { it.isNotEmpty() }
        if (chain != null) chainId = chain
    }

    private fun publishSession() {
        val addr = address
        val chain = chainId
        mainHandler.post { listener?.onSessionChanged(addr, chain) }
    }

    private fun flushConnect(result: Result<String>) {
        val pending = pendingConnect.toList()
        pendingConnect.clear()
        pending.forEach { it(result) }
    }

    /**
     * `chainChanged` arrives as a bare EIP-1193 chain id (`0x89`), while the
     * rest of this class speaks CAIP-2 (`eip155:137`). Normalise on the way in.
     */
    private fun normalizeChain(data: String): String {
        if (data.isEmpty()) return chainId
        if (!data.startsWith("0x")) return data
        val decimal = data.substring(2).toLongOrNull(16) ?: return data
        return "eip155:$decimal"
    }

    /** `"accountsChanged"` payloads arrive as a JSON array of addresses. */
    private fun parseAddress(data: String): String? =
        data.trim().removePrefix("[").removeSuffix("]")
            .split(",").firstOrNull()
            ?.trim()?.trim('"')?.takeIf { it.startsWith("0x") }
}
