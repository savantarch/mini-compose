package com.savantarch.minicompose

/**
 * MiniCompose: a pure Kotlin, terminal-based simulation of the Compose Runtime.
 * No Android framework, just a flat 1D array and a cursor showing how the engine works under the hood.
 * Run the script to see:
 * - Why Compose can skip re-rendering a function without comparing every parameter.
 * - Why a "backwards write" can cause recomposition to occur on every frame, endlessly.
 * - What happens in memory when your if/else changes which composable is on screen.
 * - How mutating state in a child re-runs it bypassing its parent entirely.
 */
// ===========================================================================
// MAIN ENTRY POINT
// ===========================================================================

fun main() {
    runSingleCompose()
    runNestedCompose()
    runConditionalCompose()
    runBackwardsWriteDetect()
}

// ===========================================================================
// EXAMPLE 1: SINGLE COMPOSE APP
// ===========================================================================

/**
 * The actual Compose equivalent of this function:
 * ```kotlin
 * @Composable
 * fun SingleCompose(title: String) {
 *     val countState = remember { mutableStateOf(0) }
 *     Text("Title: $title, Count: ${countState.value}")
 * }
 * ```
 * Note: Returning state from a Composable is a demo convenience to trigger mutations.
 * In real Compose, state is hoisted or shared via CompositionLocal.
 */
fun singleCompose(title: String, composer: MiniComposer, changed: Int): MiniState<Int> {
    // 100 is the mock compiler-generated positional key for this specific function site
    composer.startRestartGroup(key = 100)

    var dirty = changed
    // Parameter 1 (title): Uses bits 1, 2, 3. Mask is 0b1110
    if ((changed and 0b1110) == 0) {
        val titleChanged = composer.changed(title)
        dirty = dirty or if (titleChanged) 0b0100 else 0b0010
    } else {
        composer.skipValue() // Advance the cursor since we skipped the slot evaluation!
    }

    val countState = composer.remember { MiniState(initialValue = 0) }

    // Smart Recomposition Logic
    val forceRender = (dirty and 0b0001) != 0
    val paramsUnchanged = (dirty and 0b1110) == 0b0010

    if (forceRender || !paramsUnchanged) {
        composer.renderCount++
        println("[SingleCompose] UI Rendered -> Title: $title, Count: ${countState.value}")
    } else {
        println("[SingleCompose] UI Skipped! -> Title identical, State unchanged.")
    }

    // The compiler injects updateScope at the bottom. 
    // It will safely skip lambda creation if no state was read!
    val scope = composer.endRestartGroup()
    scope?.updateScope {
        // Why `changed or 0b0001`? 
        // This lambda is ONLY called by the engine during Targeted Re-entry when an internal State mutates.
        // Because the parameters (like `title`) haven't changed, the bitmask logic would normally SKIP the UI.
        // By flipping the 0th bit (Force Render) to 1, we explicitly tell the function:
        // "Ignore your parameters, you MUST re-render because your internal state changed!"
        singleCompose(title, composer, updateChangedFlags(changed) or 0b0001)
    }

    return countState
}

fun runSingleCompose() {
    println("========== RUNNING SINGLE COMPOSE ==========")
    val composer = MiniComposer()

    println("--- 1. INITIAL COMPOSITION ---")
    composer.startFrame()
    // changed = 0 indicates the compiler is "Uncertain" about the inputs, forcing a check
    val stateReference = singleCompose(title = "My Counter", composer = composer, changed = 0)
    composer.endFrame()
    check(composer.renderCount == 1) { "Expected 1 render, got ${composer.renderCount}" }

    println("\n--- 2. RECOMPOSITION (NO CHANGES) ---")
    composer.startFrame()
    singleCompose(title = "My Counter", composer = composer, changed = 0)
    composer.endFrame()
    check(composer.renderCount == 0) { "Expected 0 renders, got ${composer.renderCount}" }

    println("\n--- 3. RECOMPOSITION (STATE MUTATED) ---")
    stateReference.value = 1
    composer.applyRecomposition()
    check(composer.renderCount == 1) { "Expected 1 render, got ${composer.renderCount}" }

    println("\n--- 4. RECOMPOSITION (STATE DEDUPLICATION) ---")
    stateReference.value = 1 // Same value as before!
    composer.applyRecomposition()
    check(composer.renderCount == 0) { "Expected 0 renders due to deduplication, got ${composer.renderCount}" }

    println("\n--- 5. RECOMPOSITION (TITLE CHANGES) ---")
    composer.startFrame()
    singleCompose(title = "My Counter 2", composer = composer, changed = 0)
    composer.endFrame()
    check(composer.renderCount == 1) { "Expected 1 render, got ${composer.renderCount}" }

    println()
}

// ===========================================================================
// EXAMPLE 2: NESTED COMPOSE APP
// ===========================================================================

/**
 * The actual Compose equivalent of this function:
 * ```kotlin
 * @Composable
 * fun ParentCompose(userName: String) {
 *     val clicksState = remember { mutableStateOf(0) }
 *     Column {
 *         Text("Root Layout")
 *         ChildCompose(userName, clicksState.value)
 *     }
 * }
 * ```
 */
fun parentCompose(userName: String, composer: MiniComposer, changed: Int): Pair<MiniState<Int>, MiniState<Int>> {
    // 200 is the mock compiler-generated positional key for this specific function site
    composer.startRestartGroup(key = 200)

    var dirty = changed
    // Parameter 1 (userName): Uses bits 1, 2, 3. Mask is 0b1110
    if ((changed and 0b1110) == 0) {
        val userNameChanged = composer.changed(userName)
        dirty = dirty or if (userNameChanged) 0b0100 else 0b0010
    } else {
        composer.skipValue()
    }

    val clicksState = composer.remember { MiniState(initialValue = 0) }
    var childState: MiniState<Int>?

    val forceRender = (dirty and 0b0001) != 0
    val paramsUnchanged = (dirty and 0b1110) == 0b0010

    if (forceRender || !paramsUnchanged) {
        composer.renderCount++
        println("[ParentCompose] UI Rendered -> Root Layout")
        // Passes the composer down the tree!
        childState = childCompose(userName, clicksState.value, composer, changed = 0)
    } else {
        println("[ParentCompose] UI Skipped!")
        childState = childCompose(userName, clicksState.value, composer, changed = 0)
    }

    val scope = composer.endRestartGroup()
    scope?.updateScope {
        parentCompose(userName, composer, updateChangedFlags(changed) or 0b0001)
    }

    return Pair(clicksState, childState)
}

/**
 * The actual Compose equivalent of this function:
 * ```kotlin
 * @Composable
 * fun ChildCompose(name: String, clicks: Int) {
 *     val likesState = remember { mutableStateOf(0) }
 *     Text("User: $name, Clicks: $clicks, Likes: ${likesState.value}")
 * }
 * ```
 */
fun childCompose(name: String, clicks: Int, composer: MiniComposer, changed: Int): MiniState<Int> {
    // 300 is the mock compiler-generated positional key for this specific function site
    composer.startRestartGroup(key = 300)

    var dirty = changed
    // Parameter 1 (name): Uses bits 1, 2, 3. Mask is 0b0000_1110
    if ((changed and 0b0000_1110) == 0) {
        val nameChanged = composer.changed(name)
        dirty = dirty or if (nameChanged) 0b0000_0100 else 0b0000_0010
    } else {
        composer.skipValue()
    }

    // Parameter 2 (clicks): Uses bits 4, 5, 6. Mask is 0b0111_0000
    if ((changed and 0b0111_0000) == 0) {
        val clicksChanged = composer.changed(clicks)
        dirty = dirty or if (clicksChanged) 0b0010_0000 else 0b0001_0000
    } else {
        composer.skipValue()
    }

    val likesState = composer.remember { MiniState(initialValue = 0) }

    val forceRender = (dirty and 0b0001) != 0
    // To skip, BOTH params must be SAME (0b0001_0010)
    // Mask 0b0111_1110 isolates bits 1-6. 
    val paramsUnchanged = (dirty and 0b0111_1110) == 0b0001_0010

    if (forceRender || !paramsUnchanged) {
        composer.renderCount++
        println("   [ChildCompose] UI Rendered -> User: $name, Clicks: $clicks, Likes: ${likesState.value}")
    } else {
        println("   [ChildCompose] UI Skipped! -> Inputs identical, State unchanged.")
    }

    val scope = composer.endRestartGroup()
    scope?.updateScope {
        childCompose(name, clicks, composer, updateChangedFlags(changed) or 0b0001)
    }

    return likesState
}

fun runNestedCompose() {
    println("========== RUNNING NESTED COMPOSE ==========")
    val composer = MiniComposer()

    println("--- 1. INITIAL COMPOSITION ---")
    composer.startFrame()
    val (parentState, childState) = parentCompose("Alice", composer, changed = 0)
    composer.endFrame()
    check(composer.renderCount == 2) { "Expected 2 renders, got ${composer.renderCount}" }

    println("\n--- 2. RECOMPOSITION (NO CHANGES) ---")
    composer.startFrame()
    parentCompose("Alice", composer, changed = 0)
    composer.endFrame()
    check(composer.renderCount == 0) { "Expected 0 renders, got ${composer.renderCount}" }

    println("\n--- 3. RECOMPOSITION (PARENT STATE CHANGES) ---")
    parentState.value = 5
    composer.applyRecomposition()
    check(composer.renderCount == 2) { "Expected 2 renders, got ${composer.renderCount}" }

    println("\n--- 4. RECOMPOSITION (CHILD STATE CHANGES ONLY) ---")
    childState.value = 1
    composer.applyRecomposition()
    check(composer.renderCount == 1) { "Expected 1 render, got ${composer.renderCount}" }
    println()
}

// ===========================================================================
// EXAMPLE 3: CONDITIONAL COMPOSE (STRUCTURAL CHANGES)
// ===========================================================================

/**
 * The actual Compose equivalent of this function:
 * @Composable
 * fun ConditionalCompose() {
 *     val showChild2 = remember { mutableStateOf(false) }
 *     if (showChild2.value) {
 *         Child2Compose()
 *     } else {
 *         Child1Compose()
 *     }
 * }
 */
fun conditionalCompose(composer: MiniComposer, changed: Int): MiniState<Boolean> {
    composer.startRestartGroup(key = 400)

    val showChild2State = composer.remember { MiniState(initialValue = false) }

    val forceRender = (changed and 0b0001) != 0
    val paramsUnchanged = (changed and 0b1110) == 0b0010
    if (forceRender || !paramsUnchanged) {
        composer.renderCount++
        // Control Flow Group (Compiler injects ReplaceableGroups around if/else blocks)
        if (showChild2State.value) {
            composer.startReplaceableGroup(402) // Key for 'true' branch
            println("[ConditionalCompose] Rendering Child2Compose")
            child2Compose(composer, changed = 0)
            composer.endReplaceableGroup()
        } else {
            composer.startReplaceableGroup(401) // Key for 'false' branch
            println("[ConditionalCompose] Rendering Child1Compose")
            child1Compose(composer, changed = 0)
            composer.endReplaceableGroup()
        }
    } else {
        println("[ConditionalCompose] UI Skipped!")
        if (showChild2State.value) {
            composer.startReplaceableGroup(key = 402)
            child2Compose(composer, 0b0010) // Simulate parent passing SAME
            composer.endReplaceableGroup()
        } else {
            composer.startReplaceableGroup(key = 401)
            child1Compose(composer, changed = 0b0010) // Simulate parent passing SAME
            composer.endReplaceableGroup()
        }
    }

    val scope = composer.endRestartGroup()
    scope?.updateScope { conditionalCompose(composer, updateChangedFlags(changed) or 1) }

    return showChild2State
}

/**
 * The actual Compose equivalent of this function:
 * ```kotlin
 * @Composable
 * fun Child1Compose() {
 *     val state = remember { mutableStateOf("Data 1") }
 *     Text("Child1 Rendered with state: ${state.value}")
 * }
 * ```
 */
fun child1Compose(composer: MiniComposer, changed: Int) {
    composer.startRestartGroup(key = 501)

    val state = composer.remember { MiniState(initialValue = "Data 1") }

    val forceRender = (changed and 0b0001) != 0
    val paramsUnchanged = (changed and 0b1110) == 0b0010
    if (forceRender || !paramsUnchanged) {
        composer.renderCount++
        println("   [Child1Compose] Rendered with state: ${state.value}")
    } else {
        println("   [Child1Compose] UI Skipped!")
    }

    val scope = composer.endRestartGroup()
    scope?.updateScope { child1Compose(composer, updateChangedFlags(changed) or 1) }
}

/**
 * The actual Compose equivalent of this function:
 * ```kotlin
 * @Composable
 * fun Child2Compose() {
 *     val state = remember { mutableStateOf("Data 2") }
 *     Text("Child2 Rendered with state: ${state.value}")
 * }
 * ```
 */
fun child2Compose(composer: MiniComposer, changed: Int) {
    composer.startRestartGroup(key = 502)

    val state = composer.remember { MiniState(initialValue = "Data 2") }

    val forceRender = (changed and 0b0001) != 0
    val paramsUnchanged = (changed and 0b1110) == 0b0010
    if (forceRender || !paramsUnchanged) {
        composer.renderCount++
        println("   [Child2Compose] Rendered with state: ${state.value}")
    } else {
        println("   [Child2Compose] UI Skipped!")
    }

    val scope = composer.endRestartGroup()
    scope?.updateScope { child2Compose(composer, updateChangedFlags(changed) or 1) }
}

fun runConditionalCompose() {
    println("========== RUNNING CONDITIONAL COMPOSE ==========")
    val composer = MiniComposer()

    println("--- 1. INITIAL COMPOSITION (Child1Compose) ---")
    composer.startFrame()
    val showChild2State = conditionalCompose(composer, changed = 0)
    composer.endFrame()
    check(composer.renderCount == 2) { "Expected 2 renders, got ${composer.renderCount}" }

    println("\n--- 2. RECOMPOSITION (NO CHANGES) ---")
    composer.startFrame()
    conditionalCompose(composer, changed = 0b0010) // Simulate parent passing SAME
    composer.endFrame()
    check(composer.renderCount == 0) { "Expected 0 renders, got ${composer.renderCount}" }

    println("\n--- 3. RECOMPOSITION (SWAP TO Child2Compose) ---")
    showChild2State.value = true
    composer.applyRecomposition()
    check(composer.renderCount == 2) { "Expected 2 renders, got ${composer.renderCount}" }
    println()
}

// ===========================================================================
// EXAMPLE 4: BACKWARDS WRITE DETECTION
// ===========================================================================

/**
 * The actual Compose equivalent of this function:
 * ```kotlin
 * @Composable
 * fun BackwardsWriteCompose() {
 *     val state = remember { mutableStateOf(0) }
 *     // INCORRECT: Mutating state directly in the Composable body!
 *     state.value = 1 
 * }
 * ```
 */
fun backwardsWriteCompose(composer: MiniComposer, changed: Int) {
    composer.startRestartGroup(key = 601)

    val state = composer.remember { MiniState(0) }
    
    // Simulate a developer accidentally mutating state directly in the Composable body
    println("   [BackwardsWriteCompose] Attempting to mutate state during composition...")
    try {
        state.value = 1
    } catch (e: IllegalStateException) {
        println("   [Success] Engine caught illegal mutation: ${e.message}")
    }

    val scope = composer.endRestartGroup()
    scope?.updateScope { backwardsWriteCompose(composer, updateChangedFlags(changed) or 1) }
}

fun runBackwardsWriteDetect() {
    println("========== RUNNING BACKWARDS WRITE DETECT ==========")
    val composer = MiniComposer()
    
    composer.startFrame()
    backwardsWriteCompose(composer, changed = 0)
    composer.endFrame()
    
    println()
}

// ===========================================================================
// MINI COMPOSE ENGINE IMPLEMENTATION
// ===========================================================================

/**
 * Converts any "Different" flags in the changed bitmask into "Same" flags.
 * Used exclusively in the `updateScope` lambda when a Composable recomposes itself.
 * Since the parent hasn't re-executed, the parameters physically couldn't have changed.
 * Converting "Different" to "Same" prevents the Composable from degrading its children
 * to "Uncertain", saving them from wasting CPU cycles running `.equals()` checks.
 */
fun updateChangedFlags(flags: Int): Int {
    // A 32-bit mask isolating the "Different" bit (the `0b100` bit) for all 10 possible parameters.
    // In hex, this magic number is 0x24924924.
    val differentBits = flags and 0b0010_0100_1001_0010_0100_1001_0010_0100
    
    // Clear the old "Different" bits, shift them right by 1, and insert them as "Same" bits.
    return (flags and differentBits.inv()) or (differentBits shr 1)
}

// A global reference so State objects can discover their execution context.
// Why a global? State objects are instantiated and read by user code without explicitly passing a 
// Composer (e.g., `state.value`). Because the public API hides the Composer, State MUST use 
// a side-channel to register its observers.
// Note: In real Compose, this is tracked via thread-local snapshot observers, meaning it is safe
// for concurrency. This file-level global is a demo simplification.
var activeComposer: MiniComposer? = null

/**
 * Represents the Recompose Scope created by startRestartGroup.
 * 
 * Why does MiniScope take a MiniComposer directly, while MiniState reads the global?
 * Because MiniScope is an internal engine class exclusively instantiated BY the MiniComposer 
 * itself. The engine passes its dependencies explicitly, whereas user-facing classes like State 
 * must rely on implicit thread-locals (the global) to keep the developer's public API clean.
 */
class MiniScope(val composer: MiniComposer, val anchorCursor: Int) {
    var updateBlock: (() -> Unit)? = null
        private set

    var stateRead = false // Tracks if any state was read during this group
        private set

    // Tracks the States this scope read. 
    // Required so we can unregister the scope from the states when it is deleted or re-runs, 
    // preventing memory leaks! Like `observingScopes` below, real Compose manages this 
    // reverse-lookup centrally in `SnapshotStateObserver` rather than maintaining circular references.
    private val observedStates = mutableSetOf<MiniState<*>>()

    fun updateScope(block: () -> Unit) {
        updateBlock = block
    }

    // Encapsulates the mutation so MiniState doesn't modify MiniScope directly
    fun addObservation(state: MiniState<*>) {
        stateRead = true
        observedStates.add(state)
    }

    fun invalidate() {
        // In real Compose, user code can call currentRecomposeScope.invalidate().
        // The scope doesn't track its own dirtiness; it simply queues itself in the Composer.
        composer.invalidatedScopes.add(this)
    }

    fun clearObservations() {
        observedStates.forEach { it.removeObserver(scope = this) }
        observedStates.clear()
        stateRead = false
    }
}

/**
 * 1. THE STATE SYSTEM
 * Tracks changes so the Composer knows when to bypass the "skip" logic.
 * This mimics the Compose SnapshotStateObserver mechanism.
 */
class MiniState<T>(initialValue: T) {
    private var _value = initialValue

    // Tracks the Scopes (UI nodes) that read this state.
    // Educational Note: In real Compose, State objects do not hold hard references 
    // to their observing scopes directly. Instead, this many-to-many relationship 
    // is managed by a centralized, highly-optimized `SnapshotStateObserver` manager.
    // We distribute this tracking directly into the objects to make the Observer Pattern 
    // extremely obvious and readable!
    private val observingScopes = mutableSetOf<MiniScope>()

    var value: T
        get() {
            // When read during composition, we mark the current scope as an observer!
            activeComposer?.currentScope?.let { scope ->
                observingScopes.add(scope)
                scope.addObservation(this)
            }
            return _value
        }
        set(newValue) {
            // Deduplication: Real Compose defaults to structuralEqualityPolicy().
            // If the new value equals the old value, it safely ignores the mutation
            // to prevent unnecessary recomposition cycles!
            if (_value == newValue) return

            // Backwards Write Detection!
            // If activeComposer is NOT null, it means we are actively executing a Composition frame.
            // Mutating state during composition is a "Backwards Write" and can cause recomposition 
            // to occur on every frame, endlessly. State must only be mutated in event callbacks.
            check(activeComposer == null) {
                "BACKWARDS WRITE DETECTED: State mutated during composition! This can cause recomposition to occur on every frame, endlessly."
            }

            _value = newValue
            println("   --- State Mutated! (Value = $newValue) ---")

            // Why do we iterate scopes instead of just calling `activeComposer?.invalidate()`?
            // Because state mutations (like a button click) usually happen OUTSIDE of composition 
            // when `activeComposer` is null! 
            //
            // Educational Note: In real Compose, State objects do not know about Composers or Scopes.
            // They only notify the thread-local `Snapshot` system. The `SnapshotStateObserver` listens 
            // to snapshot commits, looks up the affected `RecomposeScope`s, and calls `invalidate()` on them.
            // But the final step is exactly the same as below: the scope uses its HARD REFERENCE to the 
            // Composer to queue itself up, completely bypassing the need for an active/global composer!
            observingScopes.forEach { it.invalidate() }
        }

    fun removeObserver(scope: MiniScope) {
        observingScopes.remove(scope)
    }
}

/**
 * 2. THE COMPOSER & SLOT TABLE
 * An instantiable class passed down the UI tree, holding a flat array and a cursor.
 */
class MiniComposer {
    private val slotTable = Array<Any?>(100) { null }
    private var cursor = 0
    var renderCount = 0

    // The list of scopes that have been marked as dirty by state mutations.
    // Terminology Note: State mutates -> Scopes are "invalidated" -> The Composer tracks 
    // these "invalidations" -> The engine processes them during "recomposition".
    val invalidatedScopes = mutableListOf<MiniScope>()

    private val scopeStack = mutableListOf<MiniScope>()
    val currentScope: MiniScope?
        get() = scopeStack.lastOrNull()

    fun startFrame() {
        cursor = 0
        renderCount = 0
        activeComposer = this
        scopeStack.clear()
        invalidatedScopes.clear()
    }

    fun endFrame() {
        activeComposer = null
    }

    fun applyRecomposition() {
        activeComposer = this
        renderCount = 0
        val toRecompose = invalidatedScopes.toList()
        invalidatedScopes.clear()

        for (scope in toRecompose) {
            scope.clearObservations()
            // Targeted Re-entry: Jump the cursor to the exact location of the dirty scope!
            cursor = scope.anchorCursor
            scope.updateBlock?.invoke()
        }

        activeComposer = null
    }

    // Creates a new Recompose Scope for this function block
    // AND records the compiler-generated Group Key into the Slot Table!
    fun startRestartGroup(key: Int) {
        val existingKey = slotTable[cursor]
        if (existingKey != null && existingKey != key) {
            println("      [Gap Buffer] Structural Shift! Group $existingKey replaced by $key. Wiping old slots...")
            // Fake Gap Buffer: In reality, Compose tracks node counts and only deletes the departing group.
            // For our toy, we wipe the rest of the array. Note: this wipes siblings too, 
            // which the real gap buffer does not — adding composables after this conditional will break.
            for (i in cursor until slotTable.size) {
                // Clean up any orphaned scopes in the deleted branch!
                (slotTable[i] as? MiniScope)?.clearObservations()
                slotTable[i] = null
            }
            slotTable[cursor] = key
        } else if (existingKey == null) {
            slotTable[cursor] = key
        }

        val anchor = cursor
        cursor++

        // Don't touch the old scope yet — create new scope but don't commit it.
        // endRestartGroup decides whether to commit (render) or restore (skip).
        val newScope = MiniScope(composer = this, anchorCursor = anchor)
        scopeStack.add(newScope)
        cursor++
    }

    // Ends the group. Returns the scope ONLY if state was read, otherwise returns null!
    fun endRestartGroup(): MiniScope? {
        val scope = scopeStack.removeLast()
        val scopeSlot = scope.anchorCursor + 1
        return if (scope.stateRead) {
            // Render path: clear old scope's observations and commit the new one.
            (slotTable[scopeSlot] as? MiniScope)?.clearObservations()
            slotTable[scopeSlot] = scope
            scope
        } else {
            // Skip path: discard the new scope, leave the old scope in the slot table untouched.
            // Its observations remain valid for the next state mutation.
            null
        }
    }

    // Replaceable groups are used for control flow (if/else, for loops).
    // They track structural shifts in the Gap Buffer but do not create a RecomposeScope.
    fun startReplaceableGroup(key: Int) {
        val existingKey = slotTable[cursor]
        if (existingKey != null && existingKey != key) {
            println("      [Gap Buffer] Structural Shift! Group $existingKey replaced by $key. Wiping old slots...")
            for (i in cursor until slotTable.size) {
                (slotTable[i] as? MiniScope)?.clearObservations()
                slotTable[i] = null
            }
            slotTable[cursor] = key
        } else if (existingKey == null) {
            slotTable[cursor] = key
        }
        cursor++
    }

    fun endReplaceableGroup() {
        // Replaceable groups are just markers, no scope to pop.
    }

    // The bitmask equivalent: Did this parameter change?
    fun changed(param: Any?): Boolean {
        val old = slotTable[cursor]
        slotTable[cursor] = param
        cursor++
        return old != param
    }

    // Explicitly skips a slot without evaluating it.
    // Used when the bitmask proves a parameter is Unchanged, saving CPU cycles.
    // Note: This is implemented for architectural completeness to show how real Compose
    // bypasses `.equals()`. The current demos pass `changed = 0` (Uncertain) from the
    // top level, so this specific branch is not physically exercised in the console output.
    fun skipValue() {
        cursor++
    }

    // The remember equivalent: Read slot, or compute if empty
    fun <T> remember(calculation: () -> T): T {
        val old = slotTable[cursor]
        if (old == null) {
            val newVal = calculation()
            slotTable[cursor] = newVal
            cursor++
            return newVal
        }
        cursor++
        @Suppress("UNCHECKED_CAST")
        return old as T
    }
}
