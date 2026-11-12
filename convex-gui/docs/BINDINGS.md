# Swing bindings

`convex.gui.binding.Bindings` follows domain getters and updates ordinary Swing
components. It is a small, read-only first step: existing panels can adopt one
binding at a time, without changing their domain components or other widgets.

```java
Bindings bindings = Bindings.on(panel);
bindings.text(valueLabel, counter::value);
bindings.text(informationArea, account::description);
bindings.enabled(deleteButton, item::canDelete);
```

Create the scope and register bindings on Swing's event dispatch thread (EDT).
Getters run in the background and must be safe to read from another thread; they
must not access Swing components. The scope applies changed results on the EDT.
Text bindings use the value's `toString()`, with null displayed as an empty string.
An equal text result leaves the document, caret and selection alone.

## Lifetime and refresh

All bindings in a scope share one refresh. They load once after registration,
then sample every 200 ms while the owning component is showing. Hiding a tab or
closing its window stops sampling; showing it again reads fresh values. There is
at most one background batch and its EDT delivery outstanding. Refresh requests
coalesce, and an update requested during a read causes another read afterwards.

The owner retains its scope through a hierarchy listener. Ordinary panels need
no fields or teardown methods just to keep a binding alive. `close()` permanently
releases the scope and suppresses pending delivery; it is safe from any thread.
An `AbstractGUI` can use `own(Bindings.on(this))` to make that lifetime explicit.
Closing does not interrupt a getter already running.

The existing event-driven models also work:

```java
Bindings.on(panel, stateModel).text(infoArea,
    () -> describe(stateModel.getValue()));
```

This overload subscribes to `StateModel` changes while showing instead of polling.
It subscribes before requesting a fresh read, so changes while hidden are picked
up on return. The account overview uses this route; other model listeners can
remain in place during migration.

Call `bindings.refresh()` on the EDT after a local command when an immediate read
is useful. Suppliers should be short local reads, not network requests: a slow
supplier delays the other bindings in its scope. A failed read retains that
binding's previous display, is logged at debug level, and is retried on the next
refresh; other bindings still update.

## Domain boundaries

The scope only reads. Domain methods still own edits, validation, signing and
publication under [CAD035](https://docs.convex.world/docs/cad/cursors) and
[CAD045](https://docs.convex.world/docs/cad/lattice_apps). It neither changes
cursors nor implements the proposed general lattice watch or acquisition APIs.

Bind to a component's working state to display its private draft, or to its
destination to display published state. The caller chooses when to publish.
Missing locations should be handled by the domain getter or an explicit fallback;
the binding does not equate absence with a stored nil or create state on a read.

Each getter must obtain a consistent snapshot for what it displays. Separate
getters may see different versions. For a coherent multi-field summary, read one
component value inside a getter and derive the summary from that value. Collection
models and editable fields are not yet part of this API.

## Runnable example

Run `convex.gui.examples.LatticeBindingsDemo.main` from an IDE, or with an
assembled jar containing this change:

```sh
java -cp convex.jar convex.gui.examples.LatticeBindingsDemo
```

The example shows two labels bound to a lattice component and its fork.
**Increment draft** changes only the draft. **Apply** reconciles it into the
published value. **Advance published value** changes the destination independently;
the binding discovers it without an explicit refresh. The component uses max
merge, so Apply keeps the greater value; it does not add concurrent increments.
The example is local and uses neither a network nor a persistent store.

This first migration removes the account overview's custom listener and refresh
method, plus an unused listener in its window. The shared machinery adds code at
this stage; further adapters should be justified by reductions in real callers.
