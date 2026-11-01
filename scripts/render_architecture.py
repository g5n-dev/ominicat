#!/usr/bin/env python3
"""Render five editable, dependency-free SVG architecture plates.

Run from any directory with Python 3. Source of truth: docs/architecture.md and
the core/server implementations. Run render_doc_previews.py afterwards to
refresh the PNGs used by Markdown readers without SVG/diagram support.
"""
import argparse
from html import escape
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BG, PANEL, INNER = '#0b1220', '#111d2e', '#142536'
TEXT, MUTED, LINE = '#e4edf8', '#9badc2', '#2a3f53'
TEAL, BLUE, AMBER = '#64d8c3', '#76adf7', '#e9b96e'
CHECK_ONLY = False


class Plate:
    def __init__(self, name, height, number, title, subtitle, description):
        self.name, self.height, self.parts = name, height, []
        self.parts.append(f'<svg xmlns="http://www.w3.org/2000/svg" width="1440" height="{height}" viewBox="0 0 1440 {height}" role="img" aria-labelledby="title desc">')
        self.parts += [f'<title id="title">OminiCat — {escape(title)}</title>', f'<desc id="desc">{escape(description)}</desc>',
                       '<defs><linearGradient id="background" x2="1" y2="1"><stop stop-color="#101e28"/><stop offset="1" stop-color="#0b1220"/></linearGradient>',
                       '<pattern id="dots" width="40" height="40" patternUnits="userSpaceOnUse"><circle cx="1" cy="1" r="1" fill="#6a91a8" opacity=".13"/></pattern>']
        for color, label in ((TEAL, 'teal'), (BLUE, 'blue'), (AMBER, 'amber'), (MUTED, 'muted')):
            self.parts.append(f'<marker id="arrow-{label}" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto" markerUnits="userSpaceOnUse"><path d="M1 1L7 4L1 7" fill="none" stroke="{color}" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></marker>')
        self.parts += ['</defs>', '<rect width="1440" height="100%" rx="22" fill="url(#background)"/>',
                       '<rect width="1440" height="100%" rx="22" fill="url(#dots)"/>',
                       '<g font-family="Arial,Helvetica,DejaVu Sans,sans-serif">']
        self.text(56, 55, 'OMINICAT / ARCHITECTURE', 14, TEAL, 700, spacing=2)
        self.text(1384, 55, number, 14, MUTED, anchor='end', spacing=2)
        self.text(56, 111, title, 40, TEXT, 700)
        self.text(56, 150, subtitle, 19, MUTED)

    def text(self, x, y, text, size=18, color=TEXT, weight=400, anchor=None, spacing=None):
        extra = (f' text-anchor="{anchor}"' if anchor else '') + (f' letter-spacing="{spacing}"' if spacing else '')
        self.parts.append(f'<text x="{x}" y="{y}" font-size="{size}" font-weight="{weight}" fill="{color}"{extra}>{escape(text)}</text>')

    def lines(self, x, y, lines, size=18, color=MUTED, gap=27, weight=400):
        for index, line in enumerate(lines):
            self.text(x, y + index * gap, line, size, color, weight)

    def box(self, x, y, width, height, fill=PANEL, stroke=LINE, radius=15, dashed=False):
        dash = ' stroke-dasharray="6 6"' if dashed else ''
        self.parts.append(f'<rect x="{x}" y="{y}" width="{width}" height="{height}" rx="{radius}" fill="{fill}" stroke="{stroke}" stroke-width="1.2"{dash}/>')

    def path(self, d, color=TEAL, arrow=True, dashed=False, width=2):
        marker = {TEAL: 'teal', BLUE: 'blue', AMBER: 'amber', MUTED: 'muted'}[color]
        end = f' marker-end="url(#arrow-{marker})"' if arrow else ''
        dash = ' stroke-dasharray="6 6"' if dashed else ''
        self.parts.append(f'<path d="{d}" fill="none" stroke="{color}" stroke-width="{width}" stroke-linecap="round" stroke-linejoin="round"{end}{dash}/>')

    def port(self, x, y, color=TEAL):
        self.parts.append(f'<circle cx="{x}" cy="{y}" r="4" fill="{BG}" stroke="{color}" stroke-width="1.5"/>')

    def pill(self, x, y, width, label, color=TEAL):
        self.box(x, y, width, 29, INNER, LINE, 7)
        self.text(x + 12, y + 20, label, 13, color, 700, spacing=.6)

    def node(self, x, y, width, height, title, lines=(), color=TEAL, title_size=21, size=17):
        self.box(x, y, width, height)
        self.parts.append(f'<rect x="{x}" y="{y + 18}" width="3" height="{height - 36}" rx="1.5" fill="{color}"/>')
        self.text(x + 22, y + 34, title, title_size, TEXT, 700)
        self.lines(x + 22, y + 63, lines, size, MUTED, 25)

    def footer(self, left, right='Java 17+ · mechanisms, not deployment guarantees'):
        y = self.height - 51
        self.path(f'M56 {y - 22}H1384', MUTED, False, width=.6)
        self.text(56, y + 6, left, 14, MUTED)
        self.text(1384, y + 6, right, 14, MUTED, anchor='end')

    def save(self):
        self.parts += ['</g>', '</svg>']
        destination = ROOT / 'docs/assets' / self.name
        content = '\n'.join(self.parts) + '\n'
        if CHECK_ONLY:
            if not destination.is_file() or destination.read_text(encoding='utf-8') != content:
                raise ValueError(f'Stale architecture figure: {destination.relative_to(ROOT)}')
        else:
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_text(content, encoding='utf-8')


def overview():
    p = Plate('architecture-overview.svg', 930, '01 / COMPOSITION', 'The microkernel, in context',
              'A small Java kernel. Optional conversation, provider and transport modules.',
              'Two entry points compose a JDK-only typed-frame microkernel. Optional conversation and provider modules implement the processor SPI. The optional server reads YAML, discovers factories at startup and creates fresh pipeline instances per WebSocket session; HTTP UI and diagnostics do not create pipelines.')
    p.text(56, 190, 'ENTRY POINTS', 13, MUTED, 700, spacing=1.6)
    p.text(1052, 190, 'OPTIONAL MODULES', 13, MUTED, 700, spacing=1.6)
    p.node(56, 210, 312, 126, 'Java embedding', ('Construct stages in Java', 'Direct calls or a session'), BLUE)
    p.pill(259, 223, 88, 'EMBED', BLUE)
    p.node(56, 370, 312, 158, 'Standalone container', ('ominicat-server', 'Netty NIO · HTTP / WebSocket', 'Edge codecs · auth · admission'), AMBER, 21, 16)
    p.path('M368 273H420')
    p.path('M368 448H420')
    p.port(368, 273); p.port(368, 448)
    p.box(420, 210, 584, 354, '#112b32', '#3e8f85', 18)
    p.text(448, 254, 'ominicat-core', 29, TEXT, 700)
    p.pill(862, 230, 114, 'JDK ONLY')
    p.text(448, 284, 'Open frames · envelopes · processor SPI', 18, MUTED)
    for x, width, label in ((448, 86, 'TEXT'), (544, 90, 'AUDIO'), (644, 88, 'IMAGE'), (742, 130, 'TRANSCRIPT'), (882, 94, 'CUSTOM')):
        p.pill(x, 308, width, label, TEAL)
    p.node(448, 365, 250, 116, 'Processor contracts', ('Processor / AsyncProcessor', 'Zero, one or many outputs'), TEAL, 20, 16)
    p.node(724, 365, 252, 116, 'Pipeline + session', ('Direct dispatch or FIFO', 'One owner per session'), TEAL, 20, 16)
    p.text(448, 522, 'Scheduling · cancellation · composition · reverse cleanup', 17, TEAL)
    p.node(1052, 210, 332, 140, 'ominicat-conversation', ('Transcript windows · prompts', 'Evaluations · step flows'), BLUE, 21, 17)
    p.node(1052, 388, 332, 140, 'ominicat-providers', ('PCM tools · transcript gate', 'Compatible HTTP model adapters'), BLUE, 21, 16)
    p.path('M1052 280H1028V394H1004', BLUE, True, True)
    p.path('M1052 458H1004', BLUE, True, True)
    p.text(1011, 375, 'SPI', 12, BLUE, 700)
    p.port(1004, 394, BLUE); p.port(1004, 458, BLUE)
    p.box(56, 606, 1328, 130, PANEL, LINE)
    p.text(80, 635, 'OPTIONAL SERVER ASSEMBLY', 13, AMBER, 700, spacing=1.4)
    p.text(80, 668, 'YAML + ServiceLoader / registered factories', 23, TEXT, 700)
    p.lines(80, 696, ('Discover factories at startup. New stages per WebSocket session.',), 17)
    p.box(962, 627, 396, 88, INNER, '#365463', 12)
    p.text(984, 660, 'New WS session → new pipeline', 20, TEAL, 700)
    p.text(984, 688, 'Fresh stateful processor instances', 17, MUTED)
    p.path('M212 528V606', AMBER, True, True)
    p.path('M962 627H934V564', BLUE, True, True)
    p.text(947, 593, 'creates', 14, BLUE)
    p.text(56, 785, 'Solid arrows: typed-frame entry', 16, TEAL)
    p.text(455, 785, 'Dashed arrows: assembly / SPI relationship', 16, BLUE)
    p.text(56, 817, 'Embedding can construct processors directly; YAML is not required by the kernel.', 18, MUTED)
    p.footer('01 / The dependency boundary', 'Transport and model adapters stay optional')
    p.save()


def execution():
    p = Plate('execution-path.svg', 1070, '02 / EXECUTION', 'Typed frames, two execution paths',
              'Choose caller-thread dispatch or a bounded session with a single owner worker.',
              'Direct dispatch is synchronous on the caller thread. A session admits typed frames to a bounded FIFO and runs stages on one worker. AsyncEmitter callbacks enqueue continuations into the same FIFO at the next stage; callback threads do not run downstream stages. JSON codecs remain at the network edge.')
    p.box(56, 194, 1328, 72, INNER)
    p.text(80, 224, 'OPTIONAL NETWORK EDGE', 13, AMBER, 700, spacing=1.2)
    p.text(80, 249, 'Netty NIO · WebSocket frames', 18, TEXT, 700)
    p.pill(413, 215, 164, 'DECODE / ENCODE', AMBER)
    p.text(613, 238, 'WebSocket JSON at the edge; HTTP UI / diagnostics stay separate.', 19, MUTED)
    p.box(56, 302, 1328, 194)
    p.text(80, 340, '01', 19, BLUE, 700)
    p.text(132, 340, 'Direct / synchronous', 25, TEXT, 700)
    p.text(132, 370, 'Pipeline.process(frame, sink)', 18, MUTED)
    p.pill(1175, 320, 184, 'CALLER THREAD', BLUE)
    for x, w, label in ((104, 198, 'Typed input'), (362, 250, 'Processor 1'), (672, 250, 'Processor 2'), (982, 354, 'FrameSink')):
        p.box(x, 393, w, 56, INNER)
        p.text(x + 22, 429, label, 20, TEXT, 700)
    for x1, x2 in ((302, 362), (612, 672), (922, 982)):
        p.path(f'M{x1} 421H{x2}'); p.port(x1, 421)
    p.text(104, 478, 'Same call stack · synchronous emissions · no session queue', 17, BLUE)

    p.box(56, 530, 1328, 364)
    p.text(80, 568, '02', 19, TEAL, 700)
    p.text(132, 568, 'Queued / session owned', 25, TEXT, 700)
    p.text(132, 598, 'openSession(...) → trySubmit(frame)', 18, MUTED)
    p.pill(1152, 550, 208, 'ONE OWNER WORKER')
    p.node(80, 654, 170, 91, 'Typed input', ('Driver / application',), TEAL, 19, 15)
    p.node(298, 654, 220, 91, 'Bounded FIFO', ('External + continuation',), TEAL, 20, 15)
    p.box(568, 632, 566, 131, '#112b32', '#3e8f85')
    p.text(588, 657, 'SESSION WORKER · dispatch by nextStage', 14, TEAL, 700)
    for x, w, title in ((588, 164, 'Processor'), (784, 164, 'Async stage'), (980, 134, 'Next stage')):
        p.box(x, 675, w, 61, INNER)
        p.text(x + 15, 713, title, 18, TEXT, 700)
    p.node(1182, 654, 178, 91, 'FrameSink', ('Typed output',), TEAL, 20, 16)
    p.path('M250 700H298'); p.port(250, 700)
    p.path('M518 700H568'); p.port(518, 700)
    p.path('M752 705H784')
    p.path('M948 705H980', BLUE, True, True)
    p.path('M1134 700H1182'); p.port(1134, 700)
    p.text(963, 778, 'after FIFO re-entry', 13, BLUE)
    p.node(784, 794, 330, 72, 'Provider callback', ('AsyncEmitter.tryEmit(frame)',), BLUE, 19, 16)
    p.path('M866 736V794', BLUE, True, True)
    p.path('M784 830H409V745', BLUE)
    p.text(437, 817, 'Same FIFO + originating generation', 16, BLUE)
    p.text(80, 878, 'Callback threads enqueue. The owner worker alone enters downstream stages.', 17, MUTED)
    p.text(56, 935, 'Decode + stage creation: Netty I/O. Normal processing + encode: session worker.', 18, MUTED)
    p.text(56, 965, 'Audio / image construction snapshots input bytes; frames stay typed between stages.', 18, MUTED)
    p.footer('02 / Solid: execution or admission · blue: callback handoff / continuation', 'WorkerThreads: platform on 17 · virtual on 21+')
    p.save()


def bounded():
    p = Plate('bounded-runtime.svg', 1180, '03 / OWNERSHIP', 'Bounded work, explicit ownership',
              'Independent limits protect each boundary; cancellation separates session generations.',
              'Each session owns its queue, live async handles and fresh processor instances. Queue capacity and async-handle capacity are separate limits using the same configured C. Optional parallel branches have fixed workers, a bounded task queue and per-branch output-reference limits. Netty accounts pending output payload bytes separately. Interruption fences old generations; cancellation remains cooperative.')
    p.box(56, 194, 1328, 86, INNER)
    p.text(80, 226, 'CONTAINER ADMISSION', 13, AMBER, 700, spacing=1.2)
    p.text(80, 255, 'Connections + sessions are capped', 21, TEXT, 700)
    p.text(662, 227, 'M = maxFrameBytes', 18, AMBER, 700)
    p.text(662, 255, 'Frame-size checks and per-connection outbound accounting are separate.', 17, MUTED)

    p.box(56, 318, 742, 590, '#0e202d', '#365463', 18)
    p.text(80, 358, 'Session A', 27, TEXT, 700)
    p.text(80, 389, 'Fresh pipeline + privately owned processor instances', 18, MUTED)
    p.node(80, 423, 304, 113, 'Pending work ≤ C', ('External + continuation items', 'ReentrantLock + ArrayDeque'), TEAL, 21, 15)
    p.node(410, 423, 364, 113, 'Live async operations ≤ C', ('Independent limit; same configured C', 'tryEmit → same FIFO at nextStage'), BLUE, 21, 16)
    p.path('M410 480H384', BLUE)
    p.box(80, 576, 694, 76, '#153039', '#3e8f85', 12)
    p.text(102, 608, 'One serial session worker', 23, TEXT, 700)
    p.text(102, 635, 'Stage calls · cancellation handles · reset hooks · reverse cleanup', 17, MUTED)
    p.path('M232 536V576'); p.port(232, 536)

    p.box(80, 692, 694, 192, PANEL, LINE, 12, True)
    p.text(100, 722, 'OPTIONAL ParallelProcessor', 17, BLUE, 700)
    p.node(100, 741, 278, 100, 'P branch workers', ('P-slot task queue', '≤ B output references / branch'), BLUE, 20, 15)
    p.node(422, 741, 330, 100, 'Ordered join', ('All branches succeed first', 'Owner emits in declared order'), BLUE, 20, 16)
    p.path('M378 791H422', BLUE)
    p.path('M232 652V692', BLUE, True, True)
    p.text(100, 865, 'Shared deadline: branch scheduling / wait. Join delivery may run longer.', 15, MUTED)

    p.box(836, 318, 548, 218)
    p.text(860, 358, 'Session B', 27, TEXT, 700)
    p.text(860, 389, 'A separate ownership boundary', 18, MUTED)
    p.box(860, 417, 182, 56, INNER)
    p.text(881, 452, 'Own FIFO', 20, TEAL, 700)
    p.box(1090, 417, 270, 56, INNER)
    p.text(1111, 452, 'Own worker + stages', 20, TEXT, 700)
    p.path('M1042 445H1090'); p.port(1042, 445)
    p.text(860, 510, 'Own async handles. No mutable stages shared with A.', 17, MUTED)

    p.box(836, 576, 548, 332, PANEL, '#715b42')
    p.text(860, 615, 'Netty output boundary', 25, TEXT, 700)
    p.text(860, 646, 'Encode → reserve → writeAndFlush', 18, MUTED)
    p.node(860, 684, 500, 91, 'Pending payload bytes ≤ 4 × M', ('Also check channel writability before each write',), AMBER, 23, 16)
    p.path('M774 614H816V729H860', AMBER)
    p.port(774, 614, AMBER)
    p.lines(860, 811, ('Release reservation on write-future completion.', 'Overload closes the connection explicitly.'), 17, MUTED, 29)
    p.text(860, 877, 'Payload budget ≠ all Netty tasks or native memory.', 16, AMBER)

    p.box(56, 946, 1328, 102, '#17282f', '#3e686b', 14)
    p.text(80, 979, 'Interrupt advances the generation', 23, TEXT, 700)
    p.text(80, 1012, 'Invalidate old work', 18, TEAL, 700)
    p.path('M273 1006H318')
    p.text(340, 1012, 'Drop queue + interrupt I/O', 18, TEXT)
    p.path('M610 1006H655')
    p.text(677, 1012, 'Reset before next generation', 18, TEXT)
    p.path('M963 1006H1008')
    p.text(1030, 1012, 'Reject stale callbacks', 18, BLUE, 700)
    p.text(56, 1080, 'Cancellation is cooperative. Frame-count limits are not byte/RSS limits; started external effects cannot be undone.', 17, MUTED)
    p.footer('03 / C: session capacity · P: branch count · B: per-branch output reference limit', 'P = 1…64 · no durable or atomic external commit')
    p.save()


def locking():
    p = Plate('locking-boundaries.svg', 1310, '04 / LOCKING', 'Locks guard state. Workers own execution.',
              'Independent synchronization boundaries; one owner worker per session.',
              'Submitters, lifecycle callers and asynchronous emitters use the session ReentrantLock to update a bounded FIFO, generation and handles. Processor, output and cancellation calls run after this lock is released. Registry, observation, HTTP body and transport locks have separate scopes; this is not a lock-free runtime.')
    p.text(56, 197, 'CONCURRENT CALLERS', 13, MUTED, 700, spacing=1.5)
    entries = ((224, 'Application / driver', ('trySubmit(frame) / submit(...)',), TEAL),
               (348, 'External callback', ('tryEmit / complete / fail',), BLUE),
               (472, 'Lifecycle caller', ('interrupt / finish / close',), AMBER))
    for y, title, lines, color in entries:
        p.node(56, y, 350, 96, title, lines, color, 21, 17)
        p.path(f'M406 {y+48}H462', color)
        p.port(406, y+48, color)
    p.box(462, 224, 922, 344, '#112b32', '#3e8f85', 18)
    p.text(488, 263, 'PipelineSession · ReentrantLock + Condition', 27, TEXT, 700)
    p.pill(1302, 239, 58, 'Ls')
    p.node(488, 294, 414, 113, 'Admission + FIFO', ('State / generation / queue', 'Atomic check-and-enqueue decisions'), TEAL, 22, 17)
    p.node(926, 294, 432, 113, 'Async bookkeeping', ('Active handles / cancellation queue', 'Metrics and lifecycle signals'), BLUE, 22, 17)
    p.text(488, 446, 'Condition waits release Ls; wakeups recheck state and capacity.', 19, MUTED)
    p.text(488, 479, 'trySubmit / tryEmit skip capacity waiting; lock acquisition may still wait.', 18, MUTED)
    p.text(488, 535, 'Ls ends before stage calls, output, cancellation actions and cleanup.', 20, TEAL, 700)
    p.path('M922 568V628')
    p.text(946, 605, 'Take pending work; release Ls', 17, TEAL)

    p.box(56, 628, 1328, 226, PANEL, LINE, 18)
    p.text(80, 668, 'ONE OWNER WORKER', 14, TEAL, 700, spacing=1.5)
    p.text(428, 668, 'Stage entry and final output each check the originating generation.', 19, MUTED)
    nodes = ((80, 260, 'valid(g)', ('Check under Ls', 'Then release Ls')),
             (380, 300, 'Processor call', ('Run user code outside Ls', 'Scoped synchronous emissions')),
             (720, 260, 'Next stage', ('Repeat generation check', 'Same owner worker')),
             (1020, 340, 'FrameSink / transport', ('Output runs outside Ls', 'Separate transport accounting')))
    for x, width, title, lines in nodes:
        p.node(x, 696, width, 106, title, lines, TEAL, 21, 16)
    for start, end in ((340, 380), (680, 720), (980, 1020)):
        p.path(f'M{start} 749H{end}')
    p.text(80, 834, 'SynchronousSink: invocation thread only; invalidate and detach delegate in finally.', 18, BLUE)

    p.text(56, 903, 'SEPARATE LOCK SCOPES', 13, MUTED, 700, spacing=1.5)
    p.node(56, 926, 420, 185, 'Registry monitor',
           ('create(): factory code inside', 'describe(): callback outside', 'Creation is separate from dispatch.'), AMBER, 23, 17)
    p.node(510, 926, 420, 185, 'Observation monitors',
           ('EnvelopeStream → ObservedEmitter', 'Then Ls for callback admission', 'ActivityJournal.json() stays locked.'), BLUE, 23, 17)
    p.node(964, 926, 420, 185, 'HTTP / transport',
           ('HTTP body: separate stateLock', 'Netty output: outputLock + atomics', 'Output lock is per connection.'), AMBER, 23, 17)
    p.box(56, 1145, 1328, 65, INNER, LINE, 12)
    p.text(80, 1185, 'Outside Ls does not mean outside every lock. Built-in HTTP stages remain synchronous.', 21, MUTED)
    p.footer('04 / Scope is local to each lock; callback handoff is not output delivery',
             'Source: PipelineSession · Pipeline · adapters')
    p.save()


def lifecycle():
    p = Plate('session-lifecycle.svg', 1360, '05 / LIFECYCLE', 'One session. Explicit drain and cancellation.',
              'Actual PipelineSession states; completion is published after cleanup.',
              'OPEN transitions to DRAINING on finish, CANCELLED on close, or FAILED on a current-generation exception or live emitter failure. Graceful exit waits for an empty queue and no active async handles. Every exit uses worker-finally cancellation and reverse stage cleanup before publishing TERMINATED and completing the future. Interrupt is an OPEN-to-OPEN generation change; socket inactivity and worker completion jointly release WebSocket session admission.')
    p.text(56, 197, 'ENTRY / DECISION', 13, MUTED, 700, spacing=1.5)
    p.text(516, 197, 'SESSION STATE', 13, MUTED, 700, spacing=1.5)
    p.text(1032, 197, 'COMMON WORKER FINALLY', 13, MUTED, 700, spacing=1.3)

    p.node(56, 226, 344, 144, 'OPEN', ('Accept external frames', 'and live continuations'), TEAL, 25, 18)
    p.node(516, 226, 350, 144, 'DRAINING', ('Reject external frames', 'Continue live callbacks'), BLUE, 25, 18)
    p.path('M400 298H516', BLUE)
    p.text(423, 279, 'finish()', 18, BLUE, 700)
    p.text(516, 402, 'Graceful drain: queue empty + no live handles.', 16, BLUE)

    p.node(56, 452, 344, 128, 'OPEN / DRAINING', ('Cancellation requested',), AMBER, 23, 18)
    p.node(516, 452, 350, 128, 'CANCELLED', ('generation++ · drop queued work', 'Retire handles; interrupt worker'), AMBER, 25, 16)
    p.path('M400 516H516', AMBER)
    p.text(423, 497, 'close()', 18, AMBER, 700)

    p.node(56, 666, 344, 144, 'OPEN / DRAINING', ('Current-generation exception', 'or live emitter.fail(error)'), AMBER, 23, 17)
    p.node(516, 666, 350, 144, 'FAILED', ('generation++ · drop queued work', 'Retire handles; interrupt worker'), AMBER, 25, 16)
    p.path('M400 738H516', AMBER)
    p.text(428, 719, 'failure', 18, AMBER, 700)

    # All exits merge before entering the common cleanup block, rather than skipping a cleanup step.
    for y, color in ((298, BLUE), (516, AMBER), (738, AMBER)):
        p.path(f'M866 {y}H944', color, False)
        p.port(866, y, color)
        p.port(944, y, MUTED)
    p.path('M944 738V298H1032', MUTED)
    p.box(1032, 226, 352, 812, '#112b32', '#3e8f85', 18)
    p.text(1054, 265, 'One cleanup path', 24, TEXT, 700)
    p.text(1054, 296, 'All exits use worker finally.', 17, MUTED)
    p.node(1054, 336, 308, 142, 'Retire handles',
           ('Detach cancellation hooks', 'Clear active handle set', 'Under Ls'), BLUE, 22, 17)
    p.path('M1208 478V514')
    p.node(1054, 514, 308, 154, 'Close resources',
           ('Run cancellation actions', 'Close stages in reverse', 'Record cleanup failures', 'Outside Ls'), TEAL, 22, 16)
    p.path('M1208 668V704')
    p.node(1054, 704, 308, 154, 'TERMINATED',
           ('Publish terminal state', 'Complete future after cleanup', 'Success or exception'), TEAL, 24, 16)
    p.lines(1054, 924, ('Future callbacks may run here.', 'This is not Thread.join().'), 17, MUTED, 29)

    p.box(56, 864, 810, 174, PANEL, '#3e8f85', 15)
    p.text(80, 902, 'interrupt(): OPEN → OPEN', 24, TEAL, 700)
    p.lines(80, 938, ('generation++ → discard queue + retire handles + interrupt worker',
                     'Worker: actions → onInterrupt() → dispatch when reset succeeds.',
                     'Current-generation reset failure → FAILED; calls may coalesce.',
                     'DRAINING ignores interrupt(); close() still cancels.'), 17, MUTED, 25)

    p.box(56, 1080, 1328, 124, INNER, LINE, 15)
    p.text(80, 1117, 'SERVER ADMISSION HAS A SEPARATE RELEASE GATE', 14, AMBER, 700, spacing=1.2)
    p.pill(790, 1097, 566, 'socketInactive && workerComplete', AMBER)
    p.text(80, 1151, 'Socket closure alone does not free the WebSocket session slot.', 21, TEXT, 700)
    p.text(80, 1181, 'Write completion releases reserved payload bytes; it does not prove client consumption.', 18, MUTED)
    p.text(56, 1253, 'Generation checks fence later handoffs. Already-entered external effects remain.', 21, MUTED)
    p.footer('05 / END is a frame payload; finish() is the drain request',
             'Cancellation and deadlines require plugin cooperation')
    p.save()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Check committed diagrams without rewriting them.')
    args = parser.parse_args()
    CHECK_ONLY = args.check
    try:
        overview()
        execution()
        bounded()
        locking()
        lifecycle()
    except (OSError, ValueError) as error:
        parser.exit(1, str(error) + '\n')
    print(('Checked' if CHECK_ONLY else 'Rendered') + ' five editable SVG architecture plates in docs/assets.')
