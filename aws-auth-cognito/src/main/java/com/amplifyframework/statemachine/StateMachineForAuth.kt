package com.amplifyframework.statemachine

import com.amplifyframework.auth.cognito.AuthEnvironment
import com.amplifyframework.statemachine.codegen.data.AuthStateRepo
import com.amplifyframework.statemachine.codegen.data.isSessionEstablished
import com.amplifyframework.statemachine.codegen.states.AuthState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext

/**
 * Model, mutate and process effects of a system as a finite state automaton. It consists of:
 * State - which represents the current state of the system
 * Resolver - a mechanism for mutating state in response to events and returning side effects called Actions
 * Listener - which accepts and enqueues incoming events
 * StateChangedListeners - which are notified whenever the state changes
 * EffectExecutor - which resolves and executes side Effects/Actions
 * @implements EventDispatcher
 * @param resolver responsible for mutating state based on incoming events
 * @param environment holds system specific environment info accessible to Effects/Actions
 * @param executor responsible for invoking effects
 * @param initialState starting state of the system (resolver default state will be used if omitted)
 */
internal open class StateMachineForAuth(
    resolver: StateMachineResolver<AuthState>,
    val environment: AuthEnvironment,
    private val dispatcherQueue: CoroutineDispatcher = Dispatchers.Default,
    private val executor: EffectExecutor = ConcurrentEffectExecutor(dispatcherQueue),
    private val initialState: AuthState? = null
) : EventDispatcher {

    private val resolver = resolver.eraseToAnyResolver()

    private val authStateRepo: AuthStateRepo = AuthStateRepo.getInstance(environment.context)

    // The current state of the state machine. Consumers can collect or read the current state from the read-only StateFlow
    private val _state = MutableStateFlow(initialState ?: resolver.defaultState)
    val state = _state.asStateFlow()

    private fun getAuthStateForUser(username: String?, ignoreUsername: Boolean = false): AuthState {
        if (username.isNullOrEmpty() || ignoreUsername) {
            return _state.value
        }
        return authStateRepo.get(username) ?: authStateRepo.getDefaultConfiguredState()
    }

    private fun setAuthState(userName: String, value: AuthState) {
        if (userName.isNotEmpty()) {
            authStateRepo.put(userName, value)
        }
        // Reset state to the default configured state if session is established.
        // so we can login again with different credentials.
        _state.value = if (value.isSessionEstablished) authStateRepo.getDefaultConfiguredState() else value
    }

    // Manage consistency of internal state machine state and limits invocation of listeners to a minimum of one at a time.
    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    protected val stateMachineContext = SupervisorJob() + newSingleThreadContext("StateMachineContext")
    private val stateMachineScope = CoroutineScope(stateMachineContext)

    // weak wrapper ??
    private val subscribers: MutableMap<StateChangeListenerToken, (AuthState) -> Unit> = mutableMapOf()

    // atomic value ??
    private val pendingCancellations: MutableSet<StateChangeListenerToken> = mutableSetOf()

    /**
     * Start listening to state changes updates. Asynchronously invoke listener on a background queue with the current state.
     * Both `listener` and `onSubscribe` will be invoked on a background queue.
     * @param listener listener to be invoked on state changes
     * @param onSubscribe callback to invoke when subscription is complete
     * @return token that can be used to unsubscribe the listener
     */
    @Deprecated("Collect from state flow instead")
    fun listen(
        username: String,
        token: StateChangeListenerToken,
        listener: (AuthState) -> Unit,
        onSubscribe: OnSubscribedCallback?
    ) {
        stateMachineScope.launch {
            addSubscription(username, token, listener, onSubscribe)
        }
    }

    fun listen(
        token: StateChangeListenerToken,
        listener: (AuthState) -> Unit,
        onSubscribe: OnSubscribedCallback?
    ) {
        stateMachineScope.launch {
            addSubscription(
                username = authStateRepo.activeStateKey(),
                token = token,
                listener = listener,
                onSubscribe = onSubscribe
            )
        }
    }

    /**
     * Stop listening to state changes updates. Register a pending cancellation if a new event comes in between the time
     * `cancel` is called and the time the pending cancellation is processed, the event will not be dispatched to the listener.
     * @param token identifies the listener to be removed
     */
    @Deprecated("Collect from state flow instead")
    fun cancel(token: StateChangeListenerToken) {
        pendingCancellations.add(token)
        stateMachineScope.launch {
            removeSubscription(token)
        }
    }

    /**
     * Invoke `completion` with the current state for the given user [username].
     * @param completion callback to invoke with the current state
     */
    fun getCurrentState(username: String, completion: (AuthState) -> Unit) {
        stateMachineScope.launch {
            completion(getAuthStateForUser(username))
        }
    }

    /**
     * Invoke `completion` with the state for the last active user (if exists).
     */
    fun getCurrentState(completion: (AuthState) -> Unit) {
        stateMachineScope.launch {
            completion(authStateRepo.activeState() ?: getAuthStateForUser(null))
        }
    }

    suspend fun getCurrentState() =
        withContext(stateMachineContext) { authStateRepo.activeState() ?: getAuthStateForUser(null) }

    /**
     * Register a listener.
     * @param token token, which will be retained in the subscribers map
     * @param listener listener to invoke when the state has changed
     * @param onSubscribe callback to invoke when subscription is complete
     */
    private fun addSubscription(
        username: String? = null,
        token: StateChangeListenerToken,
        listener: (AuthState) -> Unit,
        onSubscribe: OnSubscribedCallback?
    ) {
        if (pendingCancellations.contains(token)) return
        val currentState = getAuthStateForUser(username)
        subscribers[token] = listener
        onSubscribe?.invoke()
        stateMachineScope.launch(dispatcherQueue) {
            listener.invoke(currentState)
        }
    }

    /**
     * Unregister a listener.
     * @param token token of the listener to remove
     */
    private fun removeSubscription(token: StateChangeListenerToken) {
        pendingCancellations.remove(token)
        subscribers.remove(token)
    }

    /**
     * Send `event` to the StateMachine for resolution, and applies any effects and new states returned from the resolution.
     * @param event event to send to the system
     */
    override fun send(event: StateMachineEvent) {
        stateMachineScope.launch {
            process(authStateRepo.activeStateKey().orEmpty(), event)
        }
    }

    override fun send(event: StateMachineEvent, username: String, ignoreUsername: Boolean) {
        stateMachineScope.launch {
            process(username, event, ignoreUsername)
        }
    }

    /**
     * Notify all the listeners with the new state.
     * @param subscriber pair containing the subscriber token and listener
     * @param newState new state to be sent
     * @return true if the subscriber was notified, false if the token was null or a cancellation was pending
     */
    private fun notifySubscribers(
        subscriber: Map.Entry<StateChangeListenerToken, (AuthState) -> Unit>,
        newState: AuthState
    ): Boolean {
        val token = subscriber.key
        if (pendingCancellations.contains(token)) return false
        subscriber.value(newState)
        return true
    }

    /**
     * Resolver mutates the state based on current state and incoming event, and returns resolution with new state and
     * effects. If the state machine's state after resolving is not equal to the state before the event, update the
     * state machine's state and invoke listeners with the new state. Regardless of whether the state is new or not,
     * the state machine will execute any effects from the event resolution process.
     * @param event event to apply on current state for resolution
     */
    private fun process(username: String, event: StateMachineEvent, ignoreUsername: Boolean = false) {
        val currentState = getAuthStateForUser(username, ignoreUsername)
        val resolution = resolver.resolve(currentState, event)
        if (currentState != resolution.newState) {
            setAuthState(username, resolution.newState)
            val subscribersToRemove = subscribers.filter { !notifySubscribers(it, resolution.newState) }
            subscribersToRemove.forEach { subscribers.remove(it.key) }
        }
        execute(resolution.actions)
    }

    /**
     * Execute resolution side effects asynchronously.
     */
    private fun execute(actions: List<Action>) {
        executor.execute(actions, this, environment)
    }
}