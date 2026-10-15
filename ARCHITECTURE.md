# mush: architecture draft

mush is a Rider plugin for Godot scene editing and C# script debugging, backed by 2dog (libgodot hosted by .NET).
This draft is based on research into 2dog at `b03e7d12` (2026-10-14), the ReSharper/Rider SDK and Godot's
game-in-editor embedding. The ownership and execution contract below records the decisions agreed on 2026-10-15.
It defines the implementation target; the current spike still starts an engine per editor and runs scene scripts.
Open decisions elsewhere are marked **[decide]**.

## Ownership and execution contract

**Decided, 2026-10-15.** One persistent edit host serves each Godot project. Each editing scratch has its own
viewport and worlds inside that engine. Frozen is the default: scene time stands still and user scripts do not run.

### Ownership

| Scope | Owns | Lifetime |
|---|---|---|
| Godot project | edit host process, one engine, engine owner thread, scratch registry and shared resource cache | first scratch until the Godot project closes |
| Scratch session | scene instance, viewport, fresh `World2D` and `World3D`, editor camera, selection, execution policy and temporary-load namespace | explicit scratch creation until close |
| Editor view | attachment of a scratch's viewport to an IDE surface | tab/split/tool-window presentation |
| Document | canonical `.tscn` text, modification version and Rider undo history | Rider document lifetime |

- Project identity follows the resolved Godot project root within a Rider project session. Two Godot projects
  never share an edit engine. Sharing hosts between separate Rider instances is outside the initial scope.
- Every scratch has an opaque ID independent of its scene path. Every host start has a new generation. Commands
  and replies carry these identities; document updates also carry a version.
- Two editor splits showing the same document have independent scratches, worlds and cameras. A document edit
  updates both scratches; panning or resetting one does not change the other.
- A scratch is attached to one presentation surface at a time. Moving it between an editor and a future tool
  window preserves its identity. Simultaneous independent views use separate scratches.
- Godot access and scratch lifecycle operations execute on the engine owner thread. Transport threads submit
  work without calling Godot themselves.

### Scratch lifetime

- Opening another scratch creates a viewport/world pair without booting another engine. A native Godot `Window`
  per scratch is the Windows embedding candidate; milestone 4 must prove multiple windows in one engine.
- Hiding, collapsing, moving or re-docking a view preserves its scratch and camera. Hidden scratches suspend
  rendering and preview updates; the host continues pumping its engine and native messages.
- Closing a scratch releases its scene, viewport, worlds, callbacks and temporary files. It leaves sibling
  scratches and the shared engine running. Removing a presentation component during a move is not a close.
- With no scratches, the host remains warm until the project closes. Idle eviction and warm standby hosts are
  later optimizations, outside the initial contract.
- Host exit invalidates every scratch's native handles and pending work. Recovery uses a new host generation
  and restores current documents, cameras and policies. Scratch IDs cannot make replies from an old generation valid.
- Play/debug runs in a separate host. Import work also remains separate from the edit engine.

### Frozen behavior

| Concern | Default frozen scratch |
|---|---|
| User scripts | no script instance constructors or field initializers, property accessors, lifecycle, process, physics, input or signal callbacks |
| Exported values | preserve serialized values and use metadata/defaults that can be obtained without executing project code; unresolved values are reported |
| Physics and navigation | no simulation advancement in the scratch's worlds |
| Built-in motion | particles, animations, timers, audio/video and other time-driven visuals remain still; no autoplay |
| Rendering | redraws after document, camera, size or selection changes remain available |
| Input | editor camera and editor controls receive input; scene nodes do not receive interaction |
| Preview | motion or editor-script execution requires an explicit action for that scratch |

- Frozen must be established before scene/resource script instantiation, including later script attachment and
  nested loads. Setting `ProcessMode.Disabled` after loading does not satisfy the contract.
- The edit host does not automatically run the game's main scene or autoload scripts. Any future project-level
  services need an explicit execution policy.
- `[Tool]` does not bypass Frozen. The proposed `twodog.editor.EditorUpdate` opt-in belongs to explicit editor
  preview; it does not enable ordinary game lifecycle callbacks. Its API and activation lifecycle are deferred.
- Entering Frozen after a preview rebuilds the scratch from the current document with fresh worlds. Merely
  pausing existing script instances cannot undo constructors or revoke asynchronous work they already started.
- Assembly/module initialization, exported-default discovery, custom resources and time-driven rendering must
  be audited before claiming this behavior. Unsupported paths must fail closed or expose unresolved data.
- Fork execution hooks belong only to `.editor` builds. Debug/release runtime behavior stays unchanged. The
  availability of an opt-in C# contract in non-editor game builds must be resolved before that API is introduced.

### Isolation and responsiveness

Worlds isolate their scene rendering and physics spaces. The engine thread, `SceneTree`, resource cache,
project settings, managed statics and any explicitly enabled project services remain shared. A scratch is not
an arbitrary-code isolation boundary. Mutable shared resources need explicit copying or coordinated project
updates; resetting one scratch must not reset process-wide time, audio, RNG or sibling state.
Host generation/build/start and explicit editor preview respect Rider's project trust; Frozen does not replace it.

A blocked or debugger-suspended host must not block Rider. UI submissions never wait for host I/O or engine
progress. Pending document/resize updates are bounded and replace obsolete values per scratch; lifecycle
operations retain ordering. Shutdown has a deadline independent of the transport writer. A shared-host failure
may stop all its scratches, while Rider retains usable documents and recovery controls.

Milestone 1 fixes these behavioral boundaries. It does not establish a complete fork API, move process ownership
into the backend, replace the spike transport, or implement a Game View tool window.

## Processes

```
Rider frontend (Kotlin, JBR)      ReSharper backend (planned)       Edit host (.NET + 2dog, editor variant)
  .tscn text documents             project / script model           one engine and owner thread per project
  scratch sessions  <--- rd --->   shared host supervision <----->  scratch registry / document reloads
  viewport A (Canvas HWND) <------- native child embedding -------- Window A + worlds A + camera A
  viewport B (Canvas HWND) <------- native child embedding -------- Window B + worlds B + camera B
```

- **Host out of process.** mush uses one engine per edit host, shared by the project's scratches. It needs an
  owner thread, and a crash must not kill ReSharper. The host is also a plain `dotnet` executable, so Rider's own
  .NET debugger can launch or attach to it without the hostfxr tricks stock Godot needs. This is the biggest structural advantage of building on 2dog.
- **Host ships in the plugin (decided).** The plugin bundles the host code as a package in a local feed. For each
  Godot project it generates a hidden host project under `%LOCALAPPDATA%/mush/hosts/<project-hash>/` and builds it
  with `dotnet build -c Editor`. Nothing is added to the user's project.
  - The generated project ProjectReferences the game csproj and references `2dog.engine` at the game's
    `2dog.godotsharp` version.
  - It sets an absolute `GodotProjectDir`, `TwoDogVariant=editor` and `TwoDogRemoveDuplicateGodotAnalyzers=true`.
  - 2dog's targets then do the rest: restore matching natives/tools/GodotPlugins, import, run the TDG001-004
    version/hash gates and emit correct deps/runtimeconfig. Nothing in the targets assumes the nested layout.
  - Watch for:
    - The host doesn't inherit the game folder's `Directory.Build.props`, `global.json` or `nuget.config`. Copy them
      or point at them, because users may relocate `globalPackagesFolder`.
    - Never generate the project under the game's `obj/`: Godot.NET.Sdk moves obj to `.godot/mono/temp/obj`, so the
      game would compile the host's `.cs` files.
  - A prebuilt generic host is possible: `NativePath`, `GODOTSHARP_DIR`, `GODOT_TOOLS_DIR`, `ProjectAssemblyDir` and
    the `2dog.import` helper are all runtime-overridable. It would mean rebuilding version resolution (godotsharp →
    engine nuspec → `NativesVersion`, `.double`), loading `twodog.dll` into a private ALC, because its assembly
    version changes every release, and losing the build-time hash checks. Keep it as a later fast path at most.
- **Host protocol (later transport target).** Reuse twodog.repl's transport: a current-user named pipe carrying
  UTF-8 JSON lines, a first-line mode header (`\u001e2dog.mush/1`) and discovery files in `%LOCALAPPDATA%/2dog/...`. Embedding
  2dog.repl's exec mode as well gives Rider a live C# console into the host. P1 hardening and scratch identity
  are implemented against the existing process transport first; pipe/RD migration is a separate change.
- **Shared contracts.** One C# contracts library is used by the backend, the host and the import worker. It targets
  `net8.0`: Rider 2026.2.3 (build 262) runs its backend on .NET 8, set to roll forward to the newest major, on
  Windows too (`lib/ReSharperHost/Rider.Backend.*.runtimeconfig.json`).

## Scene views

### Text pane (decided: `.tscn` only, no other format)
- The left pane is the `.tscn` file itself in Rider's text editor, so undo/redo, VCS and local history work as is.
  - The scene editor is a `TextEditorWithPreview`, the same split editor Markdown uses. Its text side is a real
    `TextEditor` from `TextEditorProvider`, so line numbers, git blame/annotate, VCS gutter, find, folding and any
    `.tscn` language support (highlighting, later completion) come from the platform and other plugins.
  - The viewport is the preview side. The editor-only / split / preview-only toggle comes with it.
  - `FileEditorPolicy.HIDE_DEFAULT_EDITOR` replaces the plain text tab, but only for files inside a Godot project
    (an ancestor directory has `project.godot`).
- The host reloads the scene from the unsaved document text on a debounce. Fine-grained property ops come later.
- **Loading unsaved text.** Godot has no bound load-from-string (`resource_format_text.cpp:1440` always opens a
  file). Two routes:
  - **v1, no parser fork.** Write the buffer to
    `res://.godot/mush/<host-generation>/<scratch-id>/<hash>.tscn` and load it with `CACHE_MODE_IGNORE`: a
    fresh scene and sub-resources, while textures and meshes still come from the cache. Then call
    `set_path_cache(real_path)` on the `PackedScene` and its `SceneState` before `Instantiate()`, so that
    `scene_file_path` and inherited-state lookups point at the real file.
    - The duplicate header `uid` is harmless: only `EditorFileSystem` registers UIDs, and it skips dot-dirs.
    - Never use `take_over_path` (it evicts the real cached scene) or `REPLACE` (it mutates live cached
      sub-resources).
  - **Later, small fork addition.** `ResourceFormatLoaderText::load_from_string(text, real_path)` over
    `FileAccessMemory`, with the real path as `local_path`. It fixes the remaining gaps: relative ext paths (rare)
    and sub-resource paths of the form `tmp::id`.
  - Keep the previous instance alive until the new one is loaded, because the resource cache is weak.
  - Dispose the managed `PackedScene` wrapper right after `Instantiate()`. Otherwise its reference lives until
    finalization, which can come after engine shutdown.
  - Unsaved text crosses the host boundary in-band, base64 in the spike. A temp file per reload raced the editor
    and the host: a delete failed with "used by another process" and killed the plugin's reader thread.
  - **Godot bug, fork fix wanted.** When a node's parent path is missing, `SceneState::instantiate` warns
    ("Parent path … has vanished", `packed_scene.cpp:213`) but never frees the node it created. The node and its
    theme resources leak.
    - Invalid intermediate text is the normal case while typing, so a long-lived host leaks on every such
      keystroke.
    - Found while bisecting exit leaks (2026-10-14): `Node.PrintOrphanNodes()` showed the stray `Label`.
  - Gap: inherited scenes take their parent from the cache, so unsaved edits in the parent's open tab don't show.
- Viewport edits (later: gizmos, camera bookmarks) are written back as text edits to that document, never saved
  behind the editor's back.

### Viewport pane: zero-copy per platform

| Platform | Mechanism | Copies | Status |
|---|---|---|---|
| Windows | Host's Godot window kept over a heavyweight AWT `Canvas` (see below) | 0 | Prior art: Godot 4.4+ game embedding, 2dog WinForms `--wid` |
| macOS | Host's `CAContextID` → `CALayerHost` in a JAWT surface layer (private API) | 0 | Prior art: Godot 4.5 macOS embedding (`DisplayServerMacOSEmbedded`) |
| macOS alt | IOSurface `MTLTexture` → JBR `SharedTextures.wrapTexture` | 1 GPU blit | 2dog already allocates IOSurfaces |
| Linux X11 | `XReparentWindow` into Canvas | 0 | Prior art: Godot 4.4 |
| Wayland / remote dev | Shared-memory frames (2dog `CpuPresenter` readback) | CPU | Fallback only |

- With a native child window, Godot presents through its own swapchain and receives input natively. Nothing is
  forwarded and no texture is shared.
- 2dog's Avalonia path is less "zero-copy" than this: it does one GPU copy plus a CPU stall per frame. It is not worth
  porting into JBR, which has no D3D or Vulkan texture import.
### Windows embedding (decided: B, a true child window)
**A. Godot's own way: an owned popup.**
- The host runs Godot with `--wid <JBR frame HWND>` (main.cpp:2305). Godot then creates a borderless `WS_POPUP` owned
  by the frame:
  - no taskbar or Alt-Tab entry
  - `AttachThreadInput(…, FALSE)` to detach the input queues (`display_server_windows.cpp:7284`)
  - it closes itself if its owner disappears (`:6735`)
  - it refuses to move or resize itself, so size comes only from the embedder
- The frontend then mirrors `DisplayServerWindows::embed_process` (`:3598-3652`):
  - `SetWindowPos(…, SWP_NOZORDER|SWP_NOACTIVATE|SWP_ASYNCWINDOWPOS)` to the Canvas's screen rect on every
    move/resize of the component or its ancestors. The async flag avoids blocking on the host's message pump.
  - `ShowWindow(SW_SHOWNA/SW_HIDE)` as the tab is shown or hidden, and hide when the rect is ≤100 px.
  - focus-follows-mouse with modal/menu/text-field guards (`embedded_process.cpp:337-393`)
  - the 1×1 topmost-window trick to give focus back on close (`:3680-3711`)
- Downside: an owned popup sits above everything in the frame, so Rider's lightweight overlays (sliding tool
  windows, in-frame popups) land under it. Workaround: hide or clip the popup whenever an overlay intersects it.
- No fork change is needed. Two hosts can find each other's windows by PID (`:3434-3470`).

**B. A true child: `SetParent` into the Canvas, `WS_CHILD`.**
- Clipping and z-order are correct within Rider's hierarchy, and nothing needs to track position.
- Godot doesn't do this. Cross-process parent/child joins input queues, so a host stopped at a **debugger
  breakpoint** could freeze Rider's UI, and it defeats Godot's orphan check.
- It needs `AttachThreadInput(FALSE)` after parenting, plus style fixes in the host (P/Invoke on the HWND that 2dog
  reports via `DisplayServer.WindowGetNativeHandle`).

**Decisive spike tests:**
- breakpoint in the host with the viewport visible: Rider must stay responsive
- Rider popups, completion, sliding tool windows and dialogs over the viewport
- tab switch, split editors, floating editor windows
- move, resize and maximize jitter
- keyboard focus in and out
- mixed DPI: Godot calls `SetProcessDpiAwareness(SYSTEM_DPI_AWARE)` (`:7934`), but JBR is per-monitor. Try a
  PerMonitorV2 manifest on the host exe.

**Spike results so far** (2026-10-14; `spike/harness`, JBR 25.0.4 from Rider 2026.2.3, Vulkan, 2dog 4.7.2.119):

| | A owned popup | B `WS_CHILD` | B + detached input queues |
|---|---|---|---|
| renders in the Canvas, 2D and 3D | yes | yes (`--hidden-window`, shown after `SetParent`) | yes |
| lightweight Swing overlay over the viewport | **hidden under Godot** | **drawn on top** | drawn on top |
| lightweight `JPopupMenu` over the viewport | **hidden under Godot** | **drawn on top** | drawn on top |
| owned dialog over the viewport | on top | on top | on top |
| max EDT gap while the host is stalled 3 s and the frame is resized 16× | 18-24 ms | 19-35 ms | 21 ms |
| unsaved-text reload, round trip incl. frame wait | median 7 ms | median 6-7 ms | median 7 ms |

- First load is 38-56 ms parse plus 15-22 ms instantiate. Warm text reloads are about 1 ms parse plus 0.5 ms
  instantiate. The rest of the round trip is waiting for the host's next frame.
- Resizes use `SWP_ASYNCWINDOWPOS` in both modes. `--sync` exists to measure B without it.
- **Decision: B.** JBR composites lightweight Swing content above the child's Vulkan swapchain, which removes A's
  main drawback. Hands-on, A felt like the overlay trick it is, and B "just worked" (user, 2026-10-14).
- **Lifecycle rules for B**, found through a `VK_ERROR_SURFACE_LOST_KHR` at shutdown: a `WS_CHILD` dies with its
  parent HWND.
  - AWT destroys and recreates the Canvas HWND whenever the component leaves and re-enters a displayable hierarchy:
    tab moved to another window, re-docking, closing.
  - Before a Canvas peer is removed, park the Godot window under a never-shown AWT parking window, keeping
    `WS_CHILD`; making it a top-level popup can flash. Re-embed after the Canvas has a valid peer.
  - On scratch close, park before removing the Canvas, then release the scratch viewport asynchronously. Closing
    one view must not stop the shared host or wait for its message pump. Project close stops the host.
  - The harness's "Re-home" step checks this. Rendering resumes, with 0 host errors across re-home and close.
- Input-queue detaching is still open. Plain B worked manually and so did detached B. Decide after a real debugger
  breakpoint in Rider, because a breakpoint suspends more than the stall command does.
- Still manual (`spike/harness/run.ps1`):
  - done by the user: camera input in the child, live text edits, stalls with interaction. B worked; A rejected.
  - open: mixed DPI, then the same in a Rider FileEditor with Rider's debugger on the host

### Camera and selection
- **v1, pure C#.** Each scratch has its own current editor `Camera2D`/`Camera3D`, outside its replaceable scene
  subtree. That is all Godot's `CameraOverride` does. Middle-drag pans and the wheel zooms, with input arriving
  natively in the Godot window.
- **Later: Godot's built-in runtime select.** It offers 2D pan/zoom, 3D freelook and click/box select with outline
  drawing (`runtime_node_select.cpp`).
  - It needs an active debugger peer (`scene_debugger.cpp:95`), and the override APIs are unbound and debug-only.
  - Unpatched route: start the host with `--remote-debug tcp://127.0.0.1:N` and have mush, the host itself or the
    backend, serve Godot's debugger protocol (`scene:override_cameras`, `scene:runtime_node_select_*`).
  - That would also bring Godot's remote scene tree and inspector data.
  - It has no gizmos.

### Edit-mode script semantics
**How stock Godot does it** (fork at 2dog `b03e7d12`):
- Two global flags and one branch decide everything.
  - `--editor` sets `Engine::set_editor_hint(true)` (`main.cpp:2481`).
  - The `EditorNode` constructor calls `ScriptServer::set_scripting_enabled(false)` and also stops physics and
    navigation (`editor_node.cpp:8384-8391`).
- `Object::set_script` (`object.cpp:984-991`) gives a real instance if `script->can_instantiate()`. Otherwise it
  gives a `PlaceHolderScriptInstance` when the editor hint is on, and nothing at all when it is off.
- C#'s `can_instantiate` is `valid && (is_tool || scripting_enabled)` (`csharp_script.cpp:2350`). `[Tool]` is detected
  in `ScriptManagerBridge.cs:759`.
- Placeholders hold exported property values and defaults, which come from the source generator through
  `_update_exports`. They drop notifications and calls, but still report `has_method`, so the scene stays
  serializable.
- Built-in nodes keep processing normally. Sprites draw and particles preview. Individual gates on editor hint stop
  AnimationPlayer/Audio/Video autoplay and Timer autostart.
- None of these switches is bound to C#. `Engine.set_editor_hint` and `ScriptServer` are C++-only. libgodot hosts can
  reach `placeholder_script_instance_create`, `placeholder_script_instance_update` and
  `object_set_script_instance` through the GDExtension interface.

**Decided policy.** Frozen has the strict meaning in the ownership and execution contract: no user-script
execution and no scene-time advancement. The previous draft's Frozen mode ran constructors, `_EnterTree` and
`_Ready`; that definition is superseded. Built-in visual motion also requires explicit preview.

The document remains canonical and the host never saves it automatically. Scripts that are explicitly allowed
later can still write files or change shared state, so editor preview requires project trust and is not a sandbox.

| Execution | Script policy | Scene time | Scope |
|---|---|---|---|
| Frozen (default) | placeholders/data only; no project-code evaluation for defaults | stopped | one scratch |
| Explicit motion preview (later) | ordinary scripts remain disabled | selected built-ins may advance | one scratch |
| Explicit editor preview (later) | only explicitly enabled opt-in editor callbacks | defined by that preview's policy | one scratch |
| Play/debug | ordinary game semantics | running | separate host |

The default is decided; exact preview controls and the `EditorUpdate` API remain later design work.

### Execution policy mechanisms to investigate

- Establish a scratch's policy during scene/resource construction, before nodes are attached to a viewport.
  Nested loads and later `set_script` calls must retain the correct scratch context.
- Investigate the per-object real/placeholder branch in `Object::set_script` and C# export/default discovery.
  The historical editor-hint and scripting-enabled globals cannot independently control multiple scratches.
- Freezing time includes physics spaces, internal node processing and time-driven rendering. A global physics,
  render-loop, audio or time-scale switch would also change other scratches and does not satisfy the contract.
- C# notification gating alone misses constructors, property access, delegate signals and asynchronous
  continuations. Switching back to Frozen rebuilds scene/world state; a host restart is needed when process-level
  work cannot be stopped cooperatively.
- Keep execution hooks in `.editor` natives/bindings. Use the stock Godot implementation as research, without
  making the scratch contract depend on starting `EditorNode` or running the host with `--editor`.
- The initial implementation needs Frozen and isolated scratch state. Arbitrary independent lifecycle toggles,
  placeholder upgrades in place and replaying skipped callbacks are deferred.

### Reset and recovery scopes

The document is canonical. Scratch resets rebuild from its current text and preserve editor view state.

| Scope | Action | Preserves |
|---|---|---|
| Scratch | replace its scene and fresh `World2D`/`World3D` under the existing viewport, applying Frozen before loading | engine, sibling scratches, own editor camera/selection/policy |
| Project resources | explicitly refresh shared cached dependencies and rebuild affected scratches as needed | host generation and unaffected document/view state |
| Host restart | stop the shared edit host, boot a new generation and recreate its scratches | latest documents and restorable camera/selection/policy state |

A scratch reset cannot undo managed statics, process-level tasks or project services. Recovery from those effects,
a host crash or an incompatible assembly rebuild affects the whole edit host. Warm standby hosts are deferred.

### Asset import (proposal)
Importers and `EditorFileSystem` exist only when Godot runs with `--editor` (`editor_node.cpp:8515`). The scene host
must never run that way, because the editor hint changes node and script behaviour. Imports therefore run in a third
process.

- **Import worker.** A persistent hidden process (editor libgodot, `--editor --headless`) that only does imports. It
  takes over the project's `.godot/2dog.import.lock`.
  - Commands, over the same pipe protocol: `update_file(path)` for new, moved or deleted files, so `.import` and uid
    files are kept; `reimport_files(paths)`; `scan_changes()` to catch up.
  - It reports back from `EditorFileSystem.resources_reimported`.
  - It idles out after a while and restarts on demand.
  - Fallback if a long-lived headless editor misbehaves in a 2dog host: run one-shot `libgodot_import_project`
    (`2dog.import`) per batch. That works today but rescans the whole project each time.
- **Watcher.** A .NET `FileSystemWatcher` inside the worker.
  - Self-contained, so CLI, REPL and tests get the same behaviour without Rider.
  - Debounce about 200 ms and wait until writers release the file.
  - On buffer overflow (e.g. a git checkout), fall back to `scan_changes()`.
  - Rider can also send explicit nudges, e.g. `scan_changes()` when the IDE regains focus. That is the stock
    editor's only trigger.
- **Routing per change.**

| Change | Worker | Scene host |
|---|---|---|
| importable source (png, glb, wav, ttf …) or its `.import` | `reimport_files` | coordinate shared resource refresh; rebuild affected imported-scene scratches |
| new / moved / deleted file | `update_file` | refresh dependencies and rebuild affected scratches if referenced |
| `.tres`, other `.tscn`, `.gdshader` | none | coordinate project-wide refresh of shared cached resources; preserve references where appropriate |
| edited `.tscn` | none | document path, not the watcher |
| `.cs` | none | build → hot reload or shared-host restart |
| `project.godot` | `scan_changes` | shared-host restart |

- Ignore `.godot/`, trees containing `.gdignore`, `bin/`, `obj/`, and `*.uid` files written by the worker itself.
### Locks and the stock Godot editor (decided: no new locks)
- **Godot has no project-open lock and no "open elsewhere" warning.** Its only marker is
  `<user data dir>/.recovery_mode_lock`, a startup-crash detector:
  - created when the editor starts (`main.cpp:2485`)
  - removed 1 s after the editor finishes loading (`editor_node.cpp:1542`)
  - read only by the project manager (`project_list.cpp:898`)
  - So stock binaries cannot be deterred. Mush can only detect them.
- **Only the locks we actively need.** The existing `.godot/2dog.import.lock` (`twodog.import/Program.cs:259`) keeps
  our importers from overlapping: the worker against 2dog builds. The worker takes it per import batch, never for its
  lifetime, because the build's import step gives up after 120 s.
- **Detection, error for now.** Before every import batch, and on a slow poll, look for Godot editor processes on this
  project by command line: `--path <dir>`, `<dir>/project.godot`, `-e`/`--editor`. If one is found, the worker
  refuses to import, Rider shows why, and the viewport keeps the last imported assets.
  - Later option: skip imports while Godot runs instead of erroring.
  - Gap: bare `godot -e` run from the project dir is visible only through the process cwd. Deferred.
- **Residual risk: concurrency with stock binaries is unprotected.** A Godot editor started mid-batch still races us.
  What it races on:
  - `.godot/imported/`, `uid_cache.bin` and the filesystem cache. These are regenerable; repair by reimport.
  - `.import`/`.uid` sidecars. Both sides normally write identical content.
  - A `.tscn` saved by Godot shows up in Rider as an external change, not silent corruption.
- **Fork patch (planned).** Skip creating `.recovery_mode_lock` for libgodot-hosted `--headless` editor boots, so a
  worker crash during startup doesn't put the user's project into recovery mode in the project manager.

## C# scripts: edit, debug, reload
- **Editing.** Rider already handles this; the game csproj is in the solution. mush adds navigation from `.tscn`
  `script = ExtResource(...)` / `res://` paths to the C# class, and back.
- **Play.** The run configuration launches the generated host in play mode under Rider's .NET debugger. Breakpoints,
  stepping and watches work because it is an ordinary .NET process. Its window can embed into the same viewport pane
  or float.
- **Edit host.** Attach the debugger on demand to debug explicitly enabled editor-preview callbacks. Suspending
  the shared host pauses all its scratches; Rider must remain responsive.
- **Verify:** Rider debugging and hot reload of an executable from a project outside the solution, i.e. the hidden
  host. If hot reload needs the project in the solution, add the generated project as a solution folder item.
- **Hot reload.**
  - Method-body edits already work through .NET Hot Reload with no 2dog changes; measured 2026-10-13 under
    `dotnet watch`. Verify that it also works through Rider's debugger-driven EnC, given that GodotPlugins loads the
    game via `LoadFromStream`.
  - New methods and `[Export]` members need the pending 2dog fix: non-readonly generated `StringName` fields, a
    native per-script class-info/export refresh and a `[MetadataUpdateHandler]`.
  - Rude edits and rebuilds: restart the shared edit host (~340 ms headless boot in the earlier measurement) and
    restore all scratch documents and camera state. Collectible-ALC reload remains a later investigation.

## Coexistence
JetBrains' open-source Godot Support plugin already registers `.tscn` (tree-only preview), run configs and debugging.
mush should add its editor as an extra tab and not replace theirs; an optional dependency can come later.

## Host topology and lifecycle

- **Edit host (decided):** one persistent process and engine per Godot project, with a registry of independent
  scratch viewports/worlds. The first scratch starts it; later scratches reuse it; project close stops it.
- **Window embedding:** prove a native Godot `Window` per scratch in milestone 4. The existing `ReplWorld` is a
  precedent for separate worlds in one engine, not evidence that every Rider embedding/debugger case already works.
- **Visibility:** suspend only the hidden scratch's rendering/preview work. Continue pumping the host. Do not use
  `RenderingServer.RenderLoopEnabled = false` to hide one scratch while another remains visible.
- **Play host:** a separate process launched by a Rider run configuration under the .NET debugger. It can embed
  into a future Game tool window or float. Ordinary play semantics do not change the edit host's execution policy.
- **Import worker (later):** one per project, separate from editing, with idle shutdown.
- **Build roles (proposal):** the hidden host project may produce one executable with edit/play/import roles.
  Packaging must preserve ordinary play semantics and `.editor`-only execution hooks; exact variant selection is
  later work.
- **Supervision:** a project-level owner starts/stops the host and handles exit/recovery independently of editor
  components. The backend is the eventual owner of generation/build and supervision; the frontend owns native
  positioning. The P1 fix stays in the existing launcher before any backend/RD migration.
- **Recovery:** a new process gets a new host generation. All scratches reconnect from current document and view
  state. A failed scratch close cannot require successful communication with a stopped engine to release Rider UI.

## Protocols (sketch)
**Host messages.** The later pipe carries JSON lines after the mode header. Requests carry `id`, responses carry
`id` + `ok`/`error`, and events have no request ID. Scratch messages carry `hostGeneration` and `scratchId`;
document loads also carry `version`. Acknowledgement of a write is distinct from successful scene application.

| Message | Direction | Payload |
|---|---|---|
| `hello` | → host | → `{pid, hostGeneration, role, godot, twodog, renderer}` |
| `scratch.open` / `scratch.close` | → host | scratch identity and scene path → per-scratch HWND/state or close acknowledgement |
| `scene.load` | → host | `{path, text?, version}` → `{version, ms, errors[]}` within the identified scratch |
| `preview.set` (later) | → host | explicit per-scratch preview policy |
| `scratch.reset` | → host | rebuild the identified scratch from the current document |
| `host.restart` | → supervisor | recreate the project edit host and all its scratches |
| `camera.get` / `camera.set` / `camera.frame` | → host | 2D offset+zoom, 3D transform+fov / node path |
| `resources.reload` | → host | `{paths[]}`, routed from the import worker |
| `log`, `script.exception`, `scene.state`, `exit` | host → | events |
| `import.update` / `import.reimport` / `import.scan` | → worker | paths → `import.done {reimported[], errors[]}` |
| `godot.editor.detected` | worker → | `{pid, cmdline}`; imports refused |

- A REPL console is a second connection to the same pipe using 2dog.repl's exec header.

**rd model (later frontend ↔ backend target).** A project host owns multiple scratch sessions. Editors attach
views to sessions rather than owning processes.

- Project host: generation, build/process state, failure and restart notification.
- Scratch: ID, scene path, document version/text, status, its HWND and editor view state.
- Frozen is the initial policy; explicit preview controls are added later.
- Calls: create/close/reset scratch, frame node, restart host.
- Logs/events identify their project generation and scratch when applicable.

## Repo layout (proposal)
```
mush/
  protocol/                    rd model (Kotlin DSL), from the resharper-rider-plugin template
  src/rider/                   Kotlin frontend: FileEditorProvider, viewport component, Win32 embedding (JNA)
  src/dotnet/
    ReSharperPlugin.Mush/      backend: project detection, hidden host generation and build, host supervision
    Mush.Contracts/            net8.0 messages shared by backend, host and worker
  src/host/
    Mush.Host/                 net10.0 library (edit/play/import roles), packed into the plugin's local feed
    template/                  hidden host project template (csproj, Program.cs, nuget.config to the feed)
  samples/MushSample/          `dnx 2dog new` project: 2D + 3D scenes, a texture, a C# script building content in _Ready
  harness/                     plain Swing/JBR app that embeds a host outside Rider (spike and manual regression)
  tests/                       contracts unit tests, host and worker integration tests, IntelliJ platform tests
```
- **Plugin zip contents:** the backend DLLs, `host-feed/Mush.Host.<ver>.nupkg` and the template. The generated
  `nuget.config` adds the feed path inside the plugin install.

## Testing (proposal)
- **Host and worker:** integration tests spawn the real exe against a temp copy of `samples/MushSample` and check:
  - versioned scene loads from text, independent scratch worlds, Frozen behavior and reset/recovery scopes
  - import routing, Godot-editor detection
- **CI:** Windows runner, headless. 2dog notes that lavapipe present fails on Windows CI, so rendering and embedding
  are tested locally through `harness/` and the Rider sandbox (`gradlew :runIde`).
- **Frontend:** IntelliJ platform tests for editor provider and session state. The Win32 embedding logic sits behind
  an interface so the harness and Rider share it.

## Milestones

The Windows spike established the initial child-window approach. The following sequence implements the shared
host and strict Frozen contract; each step has a separate validation gate.

1. **Ownership and execution contract (decided, 2026-10-15).** One edit engine per project; independent scratches
   and worlds; shared documents across splits; Frozen means no scripts and no motion by default. Complete when
   superseded topology/execution assumptions are removed from this draft.
2. **P1 responsiveness.** Harden the existing launcher with a dedicated writer, bounded/coalesced pending state,
   process exit handling and shutdown independent of pipe progress. Gate: a non-reading child, stalled engine,
   killed host, startup/close races and real debugger suspension cannot block Rider or grow pending work indefinitely.
3. **Scratch sessions.** Separate session ownership from editor presentation; add generation/scratch/request IDs
   and document versions while keeping the existing process transport. Gate: stale replies, close/reopen races,
   superseded updates and paths containing spaces behave correctly.
4. **Shared-engine experiment.** Script-free scenes in two native viewports with separate worlds/cameras; share
   embedding code between the harness and plugin. Gate: one engine startup/PID, distinct worlds/handles, independent
   state, and safe hide/resize/re-home/close. Measure cold-engine and additional-scratch startup separately.
5. **Editor-only Frozen policy.** Investigate construction/resource/default and time-control seams; implement the
   smallest `.editor`-only hooks satisfying Frozen. Gate: no forbidden script callbacks or time advancement, policy
   independent across scratches, and unchanged debug/release behavior.
6. **Rider shared-host integration.** A project owner serves multiple editor scratches, each with its own load
   namespace; recover current documents/cameras after host exit. Gate: editor splits and moves reuse one engine,
   closing one scratch leaves siblings alive, and crash recovery respects host generations.
7. **Explicit editor-script opt-in.** Define `EditorUpdate`, construction/update/disposal, exceptions and the API's
   build availability. Gate: only opted-in callbacks run during explicit preview, reload removes old registrations,
   errors identify the scratch, and a hung callback leaves Rider usable.
8. **Later.** Host packaging and backend/RD/pipe migration, import worker, play run configurations and hot reload,
   Game tool window, macOS/X11/fallback rendering, selection/gizmos and warm standby hosts. Plan these as separate
   changes after the shared-host and Frozen gates pass.
