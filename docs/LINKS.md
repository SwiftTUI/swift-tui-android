# Link activation

How a hyperlink in a hosted SwiftTUI scene becomes an Android dispatch: who
decides that a link was activated, which destinations may open, and what
happens to the ones that may not.

## Routes

One party dispatches a given activation. Which party depends on the Swift host
library packaged in the app. The host chooses once per session, before the
scene starts, and never runs both routes.

| Swift host library | Route | Decides an activation |
| --- | --- | --- |
| Advertises `SWIFT_TUI_ANDROID_OP_OPEN_LINK` | Runtime | The SwiftTUI runtime |
| Does not (swift-tui 0.22.1 and earlier) | Legacy tap | This host |

### Runtime route

The runtime's default open-link action queues the destination, and the host
drains that queue on every poll (about 33 ms) through
`swift_tui_android_copy_open_link`, dispatching each destination once, in
request order. A destination enters the queue when:

- the runtime activates an enabled `Link`, or a link interpolated into `Text`:
  a tap released on the link, Return on the focused link, or a semantic
  `activate` action the runtime accepts; or
- authored code calls the default `\.openLinkAction`.

The runtime's decision is final, so on this route:

- A custom `OpenLinkAction` (`.openLinkAction(_:)`) runs in Swift. Nothing
  reaches Android.
- A disabled link registers no action. Nothing opens.
- The host's own tap handling opens nothing. A tap therefore cannot dispatch
  twice, even though the tapped cell carries a hyperlink.
- A press released off the link opens nothing, and neither does a press the
  runtime hands to a scroll view as a pan. A press that wanders and is
  released on the same link activates it, as for every SwiftTUI control.
- A hyperlink cell that no runtime link owns is presentation only.

`\.openLinkAction` returns `true` when the destination was queued for this
host. It does not report that anything opened.

### Legacy tap route

With a host library that predates the operation, the runtime's default action
does nothing on Android, so the host opens the hyperlink under a tap itself:

- The pointer must stay in the pressed cell. Crossing a cell boundary makes the
  gesture a drag, and a drag never opens a link.
- The pressed cell must carry the same hyperlink in the frame showing at the
  press and in the frame showing at the release. A frame that moved or
  replaced the link mid-gesture opens nothing.

This route sees cells, not controls. It cannot honor a custom `OpenLinkAction`
or a disabled link, and key and semantic activation open nothing.

## URI policy

Both routes dispatch through one `SwiftTUILinkDispatcher`, which applies a
`SwiftTUILinkPolicy` before anything reaches the platform. The destination is
dispatched exactly as the runtime produced it, or not at all: the host never
trims, decodes or re-encodes it.

| Destination | Outcome | Dispatched |
| --- | --- | --- |
| Admitted, and the platform accepted it | `Opened` | Once |
| Empty; contains whitespace or a control character; longer than 8192 UTF-8 bytes; no RFC 3986 scheme; nothing after the scheme; `http`/`https` without a host; not UTF-8 | `Malformed` | No |
| Well formed, scheme outside the allow-list | `UnsupportedScheme` | No |
| Admitted, and nothing on the device handles it | `NoHandler` | Attempted once |
| Admitted, and the opener threw | `Failed` | Attempted once |

The default allow-list is `http`, `https`, `mailto` and `tel`, compared without
regard to case. Every other scheme is refused, including `javascript`, `file`,
`content`, `intent`, `data` and app-specific schemes.

No outcome interrupts the host: input, frames and later links continue after a
refusal, a missing handler or a failed dispatch. The default dispatcher writes
one `SwiftTUILinks` warning per link that opened nothing, naming the outcome
and the scheme. It never logs the destination.

## Platform dispatch

The default opener is `SwiftTUIUriHandlerLinkOpener` over the composition's
`LocalUriHandler`: one `Intent.ACTION_VIEW` started from the hosting activity,
with no chooser, category or flags. `UriHandler.openUri` reports a destination
nothing resolves as an `IllegalArgumentException`, which the opener maps to
`NoHandler`.

## Changing the policy or the opener

`rememberSwiftTUIHostState(links:)` takes the app's own dispatcher:

```kotlin
val uriHandler = LocalUriHandler.current
val links = remember(uriHandler) {
  SwiftTUILinkDispatcher(
    opener = SwiftTUIUriHandlerLinkOpener(uriHandler),
    policy = SwiftTUILinkPolicy(SwiftTUILinkPolicy.DEFAULT_ALLOWED_SCHEMES + "geo"),
    onOutcome = { outcome ->
      if (outcome is SwiftTUILinkOutcome.NoHandler) showNoAppMessage(outcome.uri)
    }
  )
}
SwiftTUIHostView(state = rememberSwiftTUIHostState(links))
```

`SwiftTUILinkOpener` is a one-method interface, so an app can open admitted
destinations its own way (a custom tab, an in-app screen) and tests can
observe dispatch without Android. It returns `false` for a missing handler and
may throw; both are reported, never propagated. The state keeps the dispatcher
it was first remembered with.

## Limitations

- The host shows no message for a link that opened nothing. Supply `onOutcome`
  to present one.
- The policy is per host state. A single link cannot carry its own allow-list.
- The runtime resolves a tap against its current frame, like every other
  pointer event. A tap on a frame the runtime has already replaced activates
  whatever now occupies that cell.
- The accessibility overlay does not open links itself and ignores the wire's
  `opensLink` flag. A semantic `activate` reaches a link only through the
  runtime route. Connected TalkBack acceptance has not been established.
- The legacy tap route keeps the limits listed under it for as long as the app
  packages an older Swift host library.
