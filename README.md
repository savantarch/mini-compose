# Compose Internals: A Deep Dive Primer

Most developers think of Compose as a declarative UI framework. This README presents a conceptual model of Compose
internals built around compiler-generated code, slot-table state, and snapshot-driven invalidation.

**MiniCompose**, the code in this repository, is a runnable, conceptual simulation of the Compose Runtime built in
pure Kotlin. It strips away the "magic" of the `@Composable` annotation to demonstrate exactly what the compiler
generates under the hood. The entry point is `src/MiniCompose.kt`; run it directly to see the console output
referenced throughout this document.

This document is a companion guide to the codebase, synthesizing the core mechanics of how the engine works.

## The Intuition: $UI = f(state)$

At its core, the Compose paradigm is about describing the UI as a pure mathematical function of its parameters and
internal states.

Unlike traditional UI systems where you manually mutate widgets over time, your Compose function is designed to handle
*all possible states at all times*. The code you write evaluates what the UI should look like *right now* based on the
current parameters and internal states, completely independent of whatever the previous states happened to be.

But this creates a massive performance challenge: If your function describes the entire UI for every single state
change, how does the app not grind to a halt?

The answer is the engine under the hood. To make this paradigm computationally viable, the Compose Runtime is built on
three foundational pillars:

1. **The Compiler Plugin**: A Kotlin compiler plugin that rewrites your `@Composable` functions, injecting a `Composer`
   instance and change-tracking metadata to help the runtime decide whether work can be skipped.
2. **The Slot Table**: A runtime data structure that stores composition state in execution order so Compose can
   associate remembered values and groups with specific call positions.
3. **The Snapshot State System**: An isolated state tracking system that records which functions read which state
   objects, allowing the Composer to precisely invalidate the affected scopes.

The rest of this primer explores how these three pillars interact during a frame of execution.

---

## 1. The Compiler Plugin

The `@Composable` annotation does not use reflection, nor does it simply flag a function for a UI builder. It triggers a
Kotlin Compiler Plugin that completely rewrites the function signature at compile time.

When you write:

```kotlin
@Composable
fun UserProfile(name: String) {
    ...
}
```

The compiler transforms it into something resembling this:

```kotlin
fun UserProfile(name: String, composer: Composer, changed: Int) {
    ...
}
```

The framework injects two critical parameters into every composable function:

1. **`composer`**: The engine itself. It is passed down the entire call tree, giving every function access to the
   underlying data structure.
2. **`changed`**: Integer bitmasks used for fast skip decisions.

---

## 2. Smart Skipping with Integer Bitmasks

A question arises: *How does Compose know to skip a function without doing expensive `.equals()` checks on every
parameter?*

The answer lies in the change-tracking bitmasks. Instead of relying on object equality during runtime, the compiler
generates bitwise logic to summarize whether parameters may have changed.

Each parameter is encoded using **three bits** representing a tri-state:

| State       | Meaning                                                         |
|-------------|-----------------------------------------------------------------|
| `uncertain` | The runtime doesn't yet know whether this value changed         |
| `same`      | This value is confirmed unchanged from the previous composition |
| `different` | This value is confirmed changed                                 |

For parameter types the compiler can prove are stable (e.g., primitives, `@Stable` classes), it can sometimes
pre-determine the state at the call site, entirely skipping the runtime check. For unstable types, the bit starts as
`uncertain` and is resolved at runtime.

Inside the compiled function, Compose performs a fast bitwise `AND` operation:

```kotlin
// Checks if the 'name' parameter bits indicate it is the SAME as last time
if ((changed and 0b1110) == 0) {
    dirty = dirty or if (composer.changed(name)) 0b0100 else 0b0010
}
```

> **Simplification note**: The bit patterns above are illustrative. Production Compose may emit multiple `$changed`
> integers for functions with many parameters, and additional integers for default-parameter handling. The essential
> point is that the state-per-parameter is a three-way categorization, not a simple boolean.

If the runtime determines that the inputs are unchanged and no tracked state has mutated, Compose can skip executing the
rest of the function. This is why Compose is so incredibly fast—it avoids evaluating unchanged UI work using simple
integer operations.

---

## 3. Positional Memoization and the Slot Table

If Compose doesn't store *composition state* in a traditional nested tree structure, where does it keep the UI
state, depth, and `remember` values?

It uses a **Slot Table**.

The Slot Table is a runtime structure that stores composition data in execution order. There is no traditional "depth"
property stored on nodes. Instead, the UI structure is implicitly defined by the **pre-order traversal** of your
functions. As the `composer` executes your code from top to bottom, a `cursor` moves through the table in call order.

> **Clarification**: Compose *does* build a `LayoutNode` tree for the layout and draw phases — this is what
> ultimately drives rendering. What the slot table avoids is storing *composition state* (remembered values, group
> bookmarks, parameter snapshots) in that tree. The two structures coexist: the slot table drives recomposition
> logic, the `LayoutNode` tree drives rendering.

### Visualizing the Array

Because Compose executes Top-Down, a nested tree structure is written sideways into the array as a sequence of items:

```text
The UI Tree:
ParentCompose("Alice")
  └── ChildCompose("Alice", 0)

Becomes a flat array in memory:

cursor
  ↓
[200] ["Alice"] [MiniState(0)] [300] ["Alice"] [0] [MiniState(0)] [null]
  ↑                              ↑
Group 200                    Group 300
(Parent)                      (Child)
```

> **Simplification note**: Groups in the real slot table also store a *size* field indicating how many slots the
> group occupies. This metadata is what allows the runtime to skip over an entire group efficiently during traversal
> and to perform gap buffer insertions without scanning the whole array.

1. The `cursor` starts at `0`.
2. `ParentCompose` executes: It writes its group key `200`, its parameter `"Alice"`, and its `remember` state into the
   first three slots.
3. Because `ChildCompose` is called *inside* `ParentCompose`, the cursor just keeps moving to the right, writing the
   child's group key `300`, its parameters, and its state.

When the next frame runs, the cursor snaps back to `0`. As `ParentCompose` executes again, it compares its new incoming
`"Alice"` string against the `"Alice"` string sitting in slot `1`. If they match, it skips its UI logic!

When you call `remember { mutableStateOf(0) }`, the Composer looks at the current `cursor` position in the array:

- If the slot is empty, it executes the lambda, stores the value in the array, and moves the cursor forward.
- If the slot is full, it retrieves the previously stored value and moves the cursor forward.

Because the data is tied to the *execution order*, `if/else` statements that add or remove Composables will shift the
execution order. The Gap Buffer efficiently slides data around in memory to accommodate these shifts without destroying
the rest of the array.

---

## 4. Restart Groups and Scope Optimization

When the runtime schedules recomposition, does it call the entire app again? It re-enters the relevant invalidated
scope. But how does it know where a specific Composable starts and ends in the composition data?

The compiler injects **Groups** into your code:

```kotlin
fun UserProfile(name: String, composer: Composer, changed: Int) {
    // 1. Mark the start of the component in the Slot Table
    composer.startRestartGroup(12345)

    // ... UI rendering ...

    // 2. Mark the end of the component
    val scope = composer.endRestartGroup()

    // 3. Define how to re-invoke this exact function
    scope?.updateScope {
        UserProfile(name, composer, changed or 1)
    }
}
```

### Writing to the Slot Table

When `startRestartGroup(12345)` is called, the integer `12345` (a **source-position hash** generated by the compiler
based on the file, line, and column of the call site) is written directly into the Slot Table array. Using the call
site position rather than the function identity is critical: two invocations of the same composable function at
different places in the source get *different* group keys, which is what lets the runtime distinguish them in the
flat array. This acts as a bookmark. If the Composer traverses the array and sees a different group key than
expected, it knows the UI structure has fundamentally changed and recreates that branch.

### The `updateScope` Optimization

Notice the safe-call `scope?.updateScope`.
If a Composable executes but *never reads any `State` objects*, `endRestartGroup()` may not need to create a restart
scope for later invalidation. This reduces overhead when a function is effectively static.

---

## 5. State Tracking and Invalidation

How does Compose know *when* to bypass the bitmask and force a Composable to render? It uses a scope-based invalidation
model tied to `RecomposeScope` and a purpose-built state observation system.

### The Snapshot System

Compose state objects like `mutableStateOf()` do not use simple field assignments under the hood. They participate in
a **Snapshot State System** — an MVCC-style (multi-version concurrency control) mechanism that allows state changes
to be observed consistently and atomically.

At any point in time, there is a *current snapshot* — a consistent, isolated view of all state values. When
recomposition runs, it executes inside a snapshot so it sees a stable picture of the world, even if writes are
happening concurrently on another thread.

### How Reads Are Intercepted

When you read `state.value` inside a Composable, it does not simply return a field. The `MutableState` implementation
notifies the active `SnapshotStateObserver` that the current scope has a dependency on this state object:

```
state.value read
      │
      ▼
SnapshotStateObserver.recordRead(state)
      │
      ▼
Current RecomposeScope is registered as a reader of `state`
```

This registration is the link between a piece of state and the scope that should recompose when it changes.

### How Writes Trigger Recomposition

When you write `state.value = 1`, the snapshot system:

1. Records the mutation in the *current* snapshot (not directly visible to other snapshots yet).
2. On snapshot commit (which the Compose runtime triggers at the end of each frame or `withMutableSnapshot` block),
   the change becomes globally visible.
3. The snapshot system notifies all registered `SnapshotStateObserver` instances that certain state objects changed.
4. The observer looks up which `RecomposeScope`s were reading those objects and marks them **invalid**.
5. The runtime schedules those scopes for recomposition on the next frame.

This is why reading state outside a composable scope (e.g., in a plain coroutine) does not automatically trigger
recomposition — there is no active scope registered to receive the invalidation notification.

### Summary of the Flow

```
state.value = 1
      │
      ▼
Snapshot records mutation
      │
      ▼
Snapshot commits → change observable globally
      │
      ▼
SnapshotStateObserver notified
      │
      ▼
RecomposeScope(s) marked invalid
      │
      ▼
Runtime schedules recomposition
```

---

## 6. The Top-Down Execution Flow

When a state changes, the runtime schedules recomposition work. The execution is remarkably elegant:

1. **Targeted Re-entry:** The runtime jumps directly to the invalidated scope using the stored anchor cursor. It does
   not start at the root!
2. **The function** checks its own inputs and any tracked state dependencies.
3. **The render** uses the current state values to produce updated UI.
4. **The children** are either skipped (if their inputs didn't change) or re-rendered.

If you run the conceptual `MiniCompose` engine, the console output for a child state mutation demonstrates this "Aha!"
moment:

```text
--- 4. RECOMPOSITION (CHILD STATE CHANGES ONLY) ---
   --- State Mutated! (Value = 1) ---
   [ChildCompose] UI Rendered -> User: Alice, Clicks: 5, Likes: 1
```

Notice how `ParentCompose` is never even called! The runtime uses Targeted Re-entry to jump directly into the
`ChildCompose` scope. The parent function is completely bypassed, proving that Compose doesn't need to traverse from the
root to render a deeply nested component.

---

## 7. Structural Changes and Control Flow

What happens when you have an `if/else` statement that conditionally adds or removes a Composable?

```kotlin
if (showChild2) {
    Child2()
} else {
    Child1()
}
```

This is one reason Compose needs stable structural bookkeeping. The compiler associates control-flow branches with
distinct group identities so the runtime can tell when one branch replaces another.

When `showChild2` flips, the Composer sees that the structure has changed. It removes the old branch and inserts the new
one, while keeping the rest of the composition aligned with execution order.

This is precisely why the **Gap Buffer** (introduced in Section 3) exists. A naive flat array would require shifting
every element after the insertion point — an O(n) operation on every structural change. The gap buffer keeps an
intentional "gap" at the current cursor position so insertions and removals are amortized O(1). The runtime slides
the gap to the point of structural change, modifies that region, and continues traversal without disturbing the
surrounding slots.

### Summary

The Compose Runtime is a masterpiece of software engineering. By combining compiler-generated change tracking,
structured composition state, and highly localized state observation, it achieves declarative UI with excellent runtime
performance.

---

## 8. Advanced Nuances (For the Curious)

To keep the mental model accessible, this document makes a few necessary simplifications. Note that Compose runtime
internals evolve across versions, so the descriptions here should be treated as a conceptual model. For a more precise
understanding of the production engine, consider these technical nuances:

- **Multiple bitmask integers**: If a Composable has many parameters, the compiler may emit additional change-tracking
  integers, along with masks for default parameter handling.
- **The Snapshot State System**: Section 5 covers the read-interception and invalidation flow at a conceptual level.
  The production implementation also handles cross-thread state visibility, nested snapshots, and transactional
  writes via `withMutableSnapshot` — details that go beyond the scope of this primer.
- **The Slot Table**: The slot table is a structured runtime data store, not just a plain flat array in the literal
  sense.
- **Recomposition traversal**: Compose uses invalidation scopes to re-enter the relevant portion of the composition
  rather than blindly re-running everything.
- **Strong Skipping & Stability**: `MiniCompose` uses simple object equality (`==`) to compare parameters. Real Compose (since Kotlin 2.0.20) enables **Strong Skipping** by default, allowing functions with unstable parameters to skip rendering. To do this safely, it evaluates stability: stable parameters are compared using object equality (`Object.equals()`), while unstable parameters are compared using instance equality (`===`).

---

## 9. References

1. **KotlinConf 2019: The Compose Runtime, Demystified** by Leland Richardson
2. **Jetpack Compose Internals Book** by Jorge Castillo
3. **Jetpack Compose Runtime Release Notes**
