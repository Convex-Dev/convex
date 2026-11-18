# Swing views of lattice components

A `LatticePanel<V>` binds one lattice domain component to a whole Swing view.
The caller constructs the view with its component; the view builds ordinary Swing
widgets and implements one `render(snapshot)` method. There are no field bindings
or setter registrations.

```java
class CounterPanel extends LatticePanel<AInteger> {
    private final JLabel value = new JLabel();
    private final JLabel next = new JLabel();

    CounterPanel(Counter counter) {
        super(counter);
        add(value);
        add(next);
    }

    @Override protected void render(AInteger snapshot) {
        value.setText(snapshot.toString());
        next.setText("Next: " + snapshot.inc());
    }
}
```

The render operation may transform the snapshot into text, a table, a chart or a
different arrangement of child components. Every derived value in that operation
comes from the same immutable snapshot. User actions call domain methods normally;
the base class does not edit, sign, publish or persist state.

## Update triggers

The trigger only signals that the view should read its source again. It carries
no state. The view reads in the background and renders on Swing's event dispatch
thread (EDT) when the snapshot differs by value from the last successful render.
Construct views on the EDT and keep rendering short. Rendering is deferred until
the view is showing, after its constructor has completed.

The default constructor uses a local polling trigger every 200 ms. A view can
instead call `super(component, trigger)`. `UpdateTrigger` has one operation:

```java
Runnable subscribe(Runnable refresh);
```

Subscription and the returned unsubscribe action run on the EDT. The callback may
be invoked from any thread; bursts coalesce. A view subscribes before requesting
its initial read, unsubscribes when hidden, and catches up when shown again.
`refresh()` also requests a read while showing, for example after a local command.
Neither the rendering code nor the domain component needs to change when the
source of signals changes. General lattice notifications are not implemented by
this GUI adapter.

## Incremental migration

An existing model can supply both the snapshot and the trigger:

```java
super(() -> model.getValue().getAccount(address), UpdateTrigger.changes(model));
```

`AccountInfoPanel` uses this constructor with its existing `StateModel<State>`.
It also accepts an `ALatticeComponent<AccountStatus>` directly. Both routes use
the same render method. Neighbouring panels and existing domain APIs can remain
unchanged while views migrate individually.

The supplier must return a consistent immutable snapshot, support background
reads and avoid Swing access. A changed snapshot rerenders the whole view; widget
diffing, selection preservation and any expensive preparation belong to the view.

## Domain and lifecycle boundaries

Binding to a fork displays its private working state; binding to its destination
displays published state. Publication remains explicit under
[CAD035](https://docs.convex.world/docs/cad/cursors) and
[CAD045](https://docs.convex.world/docs/cad/lattice_apps).

Stored nil is delivered to `render(null)`. An absent location calls
`renderAbsent()`, which defaults to `render(null)` and can be overridden to display
absence differently. Reads create no state. Read and render failures call
`onFailure` on the EDT; by default it logs a warning. A later signal retries.

Hiding discards pending delivery and stops the trigger. `close()` permanently stops
the view and is safe from any thread; it does not interrupt a read already running.
`AbstractGUI` already closes child components implementing `AutoCloseable`, so
these panels require no separate binding scope or teardown registration.

## Runnable example

Run `convex.gui.examples.LatticeViewDemo.main` from an IDE, or with an assembled
jar containing this change:

```sh
java -cp convex.jar convex.gui.examples.LatticeViewDemo
```

Two instances of the same `CounterPanel` display a published component and its
fork. **Increment draft** edits only the draft; **Apply** reconciles it into the
published value. **Advance published value** edits the destination independently,
which polling discovers without an explicit refresh. Each panel derives both its
current and next values in one render operation. The component uses max merge,
which keeps the greater value rather than adding concurrent increments. The
example uses no network or persistent store.
