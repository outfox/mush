# mush: architecture draft

mush is a Rider plugin for Godot scene editing and C# script debugging, backed by 2dog (libgodot hosted by .NET).
This draft is based on research into 2dog at `b03e7d12` (2026-10-14), the ReSharper/Rider SDK and Godot's
game-in-editor embedding. Open decisions are marked **[decide]**.

## Processes

```
Rider frontend (Kotlin, JBR)          ReSharper backend (.NET)              mush host (.NET 10 + 2dog, editor variant)
  .tscn FileEditor                      solution / csproj / script model      owns libgodot on its main thread
   ├─ .tscn text pane ◄── rd ──►        document → scene sync    ◄── pipe ──► scene load / reload / property ops
   └─ viewport pane (Canvas HWND)       host lifecycle                        editor camera (pan/zoom)
         ▲                                                                    reports its native window handle
         └──── host window tracked over the Canvas rect (owned popup or WS_CHILD) — DWM composites, 0 copies ────┘
```

- **Host out of process.** 2dog allows one engine per process. It needs an owner thread, and a crash must not kill
  ReSharper. The host is also a plain `dotnet` executable, so Rider's own .NET debugger can launch or attach to it
  without the hostfxr tricks stock Godot needs. This is the biggest structural advantage of building on 2dog.
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
- **Host protocol.** Reuse twodog.repl's transport: a current-user named pipe carrying UTF-8 JSON lines, a
  first-line mode header (`\u001e2dog.mush/1`) and discovery files in `%LOCALAPPDATA%/2dog/...`. Embedding
  2dog.repl's exec mode as well gives Rider a live C# console into the host almost for free.
- **Shared contracts.** One C# contracts library is used by the backend, the host and the import worker. It targets
  `net8.0`: Rider 2026.2.3 (build 262) runs its backend on .NET 8, set to roll forward to the newest major, on
  Windows too (`lib/ReSharperHost/Rider.Backend.*.runtimeconfig.json`).

## Scene views

### Text pane (decided: `.tscn` only, no other format)
- The left pane is the `.tscn` file itself in Rider's text editor, so undo/redo, VCS and local history work as is.
- The host reloads the scene from the unsaved document text on a debounce. Fine-grained property ops come later.
- **Loading unsaved text.** Godot has no bound load-from-string (`resource_format_text.cpp:1440` always opens a
  file). Two routes:
  - **v1, no fork.** Write the buffer to `res://.godot/mush/<hash>.tscn` and load it with `CACHE_MODE_IGNORE`: a
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
  - So in `removeNotify`, park the Godot window first: hide it, switch it to `WS_POPUP` and `SetParent(hwnd, 0)`.
    Re-embed it in `addNotify`.
  - On close, park, then stop the host, then dispose.
  - The harness's "Re-home" step checks this. Rendering resumes, with 0 host errors across re-home and close.
- Input-queue detaching is still open. Plain B worked manually and so did detached B. Decide after a real debugger
  breakpoint in Rider, because a breakpoint suspends more than the stall command does.
- Still manual (`spike/harness/run.ps1`):
  - done by the user: camera input in the child, live text edits, stalls with interaction. B worked; A rejected.
  - open: mixed DPI, then the same in a Rider FileEditor with Rider's debugger on the host

### Camera and selection
- **v1, pure C#.** The host pushes its own current `Camera2D`/`Camera3D` and restores the scene's camera
  afterwards. That is all Godot's `CameraOverride` does. Middle-drag pans and the wheel zooms, with input arriving
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

**Alternative.** Make the policy a view mode, not a script attribute.
- The host never saves. The `.tscn` text is canonical, so whatever scripts do at runtime cannot leak into the file.
  This makes running real code in the edit view safe, and the choice can be a toggle on the viewport instead of
  `[Tool]` in source. `[Tool]` is ignored.

| Mode | Script instances | Ticks | Purpose |
|---|---|---|---|
| Inert | placeholders for all scripts | built-ins only, autoplay gated | pure data view |
| Frozen | real; ctor/`_EnterTree`/`_Ready` run | no process/physics/input in the scene; audio muted | procedural content, as frame 0 |
| Live | real | everything | play-in-view; still discarded on reload |

- Switching modes reloads the scene from the document with a different policy.
- Fork seam: a per-object policy hook at the `object.cpp:984` branch, `(Object*, Ref<Script>) → real | placeholder`,
  exposed to the host. It covers scene loading, `set_script` and reloads in one place, and it is independent of the
  editor hint. That independence matters because many node classes key side effects off the editor hint, and C#
  caches it at static init (`ReflectionUtils.cs:32`).
- Frozen and Live are possible without fork changes: `PROCESS_MODE_DISABLED` on the scene root, physics off, audio
  bus muted.
- Fallback if the alternative fails: bind `set_scripting_enabled` and `set_editor_hint` and behave exactly like
  Godot.

### Lifecycle gates (proposal)
Modes are presets over independent gates: `Construct`, `EnterTree`, `Ready`, `Process`, `Physics`, `Input`.
Inert = none, Frozen = Construct + EnterTree + Ready, Live = all.

| Gate | Seam | Toggle in place? |
|---|---|---|
| Construct | per-object policy hook at `object.cpp:984` (fork, native) | off→on: upgrade placeholders, like `reload_assemblies` does; on→off: reload |
| EnterTree / Ready | managed: `CSharpInstanceBridge.Call` (`CSharpInstanceBridge.cs:10`), the single native→C# entry for `_EnterTree`, `_Ready` and `_Notification`. GodotSharp fork only, no native rebuild. C# only for now (decided); a native `GDVIRTUAL_CALL` gate would cover GDScript | off→on: catch up with `RequestReady()` on the subtree, then remove/re-add the root (`ready_first`, `node.cpp:3559`) |
| Process / Physics / Input | scene root `ProcessMode = Disabled`, physics server inactive, input not routed to the scene | yes, instant |

- Caveats:
  - Gating at the bridge does not stop C# delegate-based signal connections (DelegateUtils) or `Task.Delay`
    continuations.
  - `_Ready` gating happens after `READY` already enabled processing by `has_method` (`node.cpp:268-279`), so the
    Process gate must be independent.
- **Callers:** viewport toolbar, Rider actions, protocol commands (`setGates`, `rollback`), and so the REPL, tests and
  agents.

### Rollback tiers (proposal)
The document is canonical, so rollback always rebuilds from it. Each tier is a superset of the one before.

| Tier | Action | Cost | Resets |
|---|---|---|---|
| 0 Pause | flip Process/Physics/Input gates | instant | nothing, just stops time |
| 1 Rollback | free the scene subtree, re-instantiate the cached `PackedScene`, fresh `World2D/3D` (cf. `ReplWorld`), free nodes scripts added outside the subtree, reset `TimeScale`/mouse mode/audio, fixed RNG seed | ms | scene state, physics, worlds |
| 2 Reload | re-parse document text (temp `.tscn` under `.godot/mush/`, no load-from-string API) with `CACHE_MODE_REPLACE_DEEP` | ms-100s ms | + sub/ext resources changed on disk |
| 3 Restart | swap to a warm standby host process (pre-booted, already embedded hidden); boot a new standby | ~instant if warm | + C# statics, autoloads, native state, new game assembly |

- View state lives outside the scene: the editor camera, the gates and selection. Tier 3 restores it over the
  protocol.
- Tier 3 is also what a game-assembly rebuild needs when hot reload can't apply the edit.
- Changed source assets: see Asset import.

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
| importable source (png, glb, wav, ttf …) or its `.import` | `reimport_files` | `ReloadFromFile()` on cached resources, like `EditorNode::_resources_reimported` (`editor_node.cpp:1461`). Imported scenes → Tier 1 |
| new / moved / deleted file | `update_file` | Tier 2 if referenced |
| `.tres`, other `.tscn`, `.gdshader` | none | `ReloadFromFile()`, or `CACHE_MODE_REPLACE`, which `copy_from`s into the cached instance (`resource_loader.cpp:560-590`), so references stay valid |
| edited `.tscn` | none | document path, not the watcher |
| `.cs` | none | build → hot reload or Tier 3 |
| `project.godot` | `scan_changes` | Tier 3 |

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
- **Edit host.** Attach the debugger on demand to debug scripts running in Frozen/Live view modes.
- **Verify:** Rider debugging and hot reload of an executable from a project outside the solution, i.e. the hidden
  host. If hot reload needs the project in the solution, add the generated project as a solution folder item.
- **Hot reload.**
  - Method-body edits already work through .NET Hot Reload with no 2dog changes; measured 2026-10-13 under
    `dotnet watch`. Verify that it also works through Rider's debugger-driven EnC, given that GodotPlugins loads the
    game via `LoadFromStream`.
  - New methods and `[Export]` members need the pending 2dog fix: non-readonly generated `StringName` fields, a
    native per-script class-info/export refresh and a `[MetadataUpdateHandler]`.
  - Rude edits and rebuilds: restart the edit host (~340 ms headless boot) and restore scene and camera state. This
    is simpler than collectible-ALC reload, which 2dog compiles only into editor builds and has never exercised.

## Coexistence
JetBrains' open-source Godot Support plugin already registers `.tscn` (tree-only preview), run configs and debugging.
mush should add its editor as an extra tab and not replace theirs; an optional dependency can come later.

## Host topology and lifecycle (proposal)
- **One build, three roles.** The hidden host project produces one exe, and `--mush-role edit|play|import` selects
  the role. All roles need the same editor-variant natives and the game assembly; C# import plugins need the game
  assembly too.
- **Edit hosts: one per visible scene editor.**
  - `--wid` embeds only Godot's main window, and a separate process per tab gives isolation.
  - A tab that is hidden keeps its host but hides the window and stops rendering
    (`RenderingServer.RenderLoopEnabled = false`). The host keeps pumping messages so Windows doesn't flag it as
    hung.
  - Hosts beyond a small limit are evicted, least recently used first.
- **Optional warm spare** per project for Tier 3 rollback.
- **Import worker:** one per project, exits when idle.
- **Play host:** launched by a Rider run configuration under the .NET debugger. Its window embeds into a "Game" tab
  or floats.
- **Owner.** The backend builds, launches and supervises the hosts: `Process.Start`, a Windows Job Object so they
  die with Rider, the pipe and restarts. The frontend only receives the host HWND and status over rd, and positions
  the window itself, because tracking it is latency-critical.
- **Triggers.**
  - The host is built when the first scene editor opens, with progress shown as a background task.
  - A game build attempts hot reload; if that fails, Tier 3.

## Protocols (sketch)
**Host pipe.** JSON lines after the mode header. Requests carry `id`, responses carry `id` + `ok`/`error`, and events
have no `id`.

| Message | Direction | Payload |
|---|---|---|
| `hello` | → host | → `{pid, role, godot, twodog, renderer, hwnd}` |
| `scene.load` | → host | `{path, text?, version}` → `{version, ms, errors[]}` |
| `gates.set` / `mode.set` | → host | gate bits / preset |
| `rollback` | → host | `{tier}` |
| `camera.get` / `camera.set` / `camera.frame` | → host | 2D offset+zoom, 3D transform+fov / node path |
| `resources.reload` | → host | `{paths[]}`, routed from the import worker |
| `log`, `script.exception`, `scene.state`, `exit` | host → | events |
| `import.update` / `import.reimport` / `import.scan` | → worker | paths → `import.done {reimported[], errors[]}` |
| `godot.editor.detected` | worker → | `{pid, cmdline}`; imports refused |

- A REPL console is a second connection to the same pipe using 2dog.repl's exec header.

**rd model** (frontend ↔ backend), one `SceneSession` per scene editor:
- `scenePath`
- `text`, versioned and debounced on the frontend
- `status`: building, starting, running or error, plus a message
- `hostHwnd`
- `mode` and gates
- calls: `rollback(tier)`, `frameNode(path)`
- `log` as a signal

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
  - scene load from text, gates, rollback tiers
  - import routing, Godot-editor detection
- **CI:** Windows runner, headless. 2dog notes that lavapipe present fails on Windows CI, so rendering and embedding
  are tested locally through `harness/` and the Rider sandbox (`gradlew :runIde`).
- **Frontend:** IntelliJ platform tests for editor provider and session state. The Win32 embedding logic sits behind
  an interface so the harness and Rider share it.

## Milestones
1. **Spike (Windows first, decided).**
   1. `samples/MushSample` via `dnx 2dog new`.
   2. Minimal host exe from the 2dog template: `--wid`, scene from the CLI, prints its HWND, reloads the scene from a
      temp file on stdin command. No protocol yet.
   3. `harness/`: a JBR Swing app (JFrame, text pane and Canvas) that launches the host and implements embedding A
      and B behind a switch.
   4. Run the decisive tests in the harness, then repeat the Rider-specific ones in a template plugin FileEditor:
      tool windows, completion, and a breakpoint in the host with Rider's debugger attached.
   5. Exit: A or B chosen, workarounds listed, text-to-pixels reload latency measured on the sample scenes.
2. **Host.** Plugin-bundled host package, hidden host project generation, pipe protocol: open/reload scene from
   document text, resize, editor camera, report window handle. View modes: Frozen/Live first (no fork change), then
   the fork's script-instance policy hook for Inert.
3. **Plugin.** `dotnet new resharper-rider-plugin` scaffold, rd model, split FileEditor and debounced live reload on
   document change. Fine-grained property ops come later.
4. **Import worker.** Headless `--editor` worker in a 2dog host driving `EditorFileSystem`, watcher and routing,
   Godot-editor detection, fork patch to skip `.recovery_mode_lock` for hosted headless editor boots.
5. **Debug.** Generated run configuration for the play host, attach to the edit host, hot reload verification and
   the 2dog metadata-update fix.
6. **Later.** macOS CALayerHost, X11, shared-memory fallback, selection and gizmos, overlay UI.
