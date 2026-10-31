# Studio interaction review

Reviewed on 2026-10-31 in Chrome on macOS, at the native 1506 × 719 viewport and a temporary 390 × 844 responsive viewport. The goal was to follow real task content, move modules and inspect results without scrolling the entire canvas by mistake. Inputs were synthetic and the configured processors were `rms-vad`, `transcript-gate` and `echo`.

The first candidate failed this review. A wheel gesture inside the input column left its scroll position at zero and moved the canvas from `translate(69.45px, 12px)` to `translate(69.45px, -707px)`, leaving the workspace blank. Main connections were mostly hidden behind panels, task lines crossed scrollbar tracks, and long identifiers displaced content from the detail panel's first screen.

| Step | Final result | Evidence |
| --- | --- | --- |
| 1. Identify modules and task handoffs | Passed: visible directional main arcs; separate card handoffs; uncluttered ports and spaced Chinese headings | [Overview](../../docs/assets/studio-overview.jpg) |
| 2. Scroll a task column and its end boundary | Passed: native column scroll advances; canvas transform stays fixed; clipped cards lose their dangling links | [Scrolled column](../../docs/assets/studio-scroll.jpg) |
| 3. Grab the scrollbar and use PageDown | Passed: a permanent 12 px scrollbar can be dragged; keyboard paging scrolls the focused column | Native pointer/keyboard checks; [regression fixture](../../scripts/test_console.cjs) |
| 4. Drag modules at 105% and 87% zoom | Passed: module and task endpoints follow movement; canvas and task/output counts stay unchanged; refresh preserves layout | [Moved module](../../docs/assets/studio-drag.jpg) |
| 5. Open task details and enter the debugger | Passed: content and debug action appear first; full identifiers remain expandable; debugger is a separate view | [Task details](../../docs/assets/studio-details.jpg), [debugger](../../docs/assets/studio-debug.jpg) |
| 6. Pan, reset and inspect narrow layout | Passed: empty-space dragging pans independently; reset restores module and column positions; 390 px layout has no document overflow | Responsive browser check; viewport override restored afterward |

![Reviewed task overview](../../docs/assets/studio-overview.jpg)

![Independent column scrolling](../../docs/assets/studio-scroll.jpg)

![Module movement with attached connections](../../docs/assets/studio-drag.jpg)

![Content-first detail panel](../../docs/assets/studio-details.jpg)

The scrollbar issue had a second cause: standard scrollbar colors selected macOS overlay scrollbars and hid the intended WebKit width. The final Chromium rendering reserves 12 px (`offsetWidth 214`, `clientWidth 202`) and exposes a persistent, grabbable thumb. Column wheel events no longer become canvas pan, and outbound handoff ports sit outside the module so lines avoid the scroll track.

Keyboard focus, explicit labels, reduced-motion/pause behavior, bounded task state and hidden-view cleanup have automated coverage. The ten browser-platform fixtures include a separate geometry oracle for drag movement, frame-based handoff identity, clipping, cancellation and refresh. Passing these fixtures does not prove full accessibility compliance.

Responsive screenshots and computed touch-action boundaries were checked: the viewport allows native behavior, the background pan surface and module handles own manual dragging, and task columns allow vertical native scrolling. A physical touchscreen and screen reader were not exercised; browser resizing alone is not presented as a physical touch test. Microphone/image automation limits remain recorded in the [Studio verification](README.md).
