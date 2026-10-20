using System.Runtime.InteropServices;
using Godot;

namespace Mush.Spike;

/// <summary>
/// Publishes the engine window's Core Animation layer so the IDE can host it (macOS only).
///
/// macOS cannot reparent a window across processes, so the Windows path -- hand the IDE a window handle and let it
/// call SetParent -- has no counterpart. The window's layer is instead attached to a remote CAContext, whose id any
/// process can wrap in a CALayerHost. Only pixels cross that boundary, so input still has to arrive as commands.
///
/// CAContext is private Core Animation API, reached through the Objective-C runtime rather than a binding so that a
/// renamed class or selector degrades to "no context id" instead of breaking the build.
/// </summary>
internal static class MacLayerPublisher
{
    /// <summary>
    /// The published context id, or null on other platforms and when the private API did not cooperate.
    /// Call on the main thread, after the engine has created its window.
    /// </summary>
    public static uint? Publish(out string diagnostic)
    {
        diagnostic = "not macOS";
        if (!OperatingSystem.IsMacOS())
            return null;

        var view = (nint)DisplayServer.WindowGetNativeHandle(DisplayServer.HandleType.WindowView);
        if (view == 0)
        {
            diagnostic = "no window view";
            return null;
        }
        // What gets published has to be the layer the renderer actually draws into. If this is not a
        // CAMetalLayer, the context is valid and carries nothing, which looks identical to a broken embed.
        var layer = Send(view, "layer");
        if (layer == 0)
        {
            diagnostic = $"view {ClassNameOf(view)} is not layer-backed";
            return null;
        }
        diagnostic = $"view={ClassNameOf(view)} layer={ClassNameOf(layer)} sublayers=[{SublayerClasses(layer)}]";

        var contextClass = objc_getClass("CAContext");
        if (contextClass == 0)
        {
            diagnostic += " | no CAContext class";
            return null;
        }
        // Nil options take the context defaults, which is what Godot's own editor embedding uses.
        var context = Send(contextClass, "remoteContextWithOptions:", 0);
        if (context == 0)
        {
            diagnostic += " | remoteContextWithOptions: returned nil";
            return null;
        }
        // The factory hands back an autoreleased context; it has to outlive this scope.
        Send(context, "retain");

        Send(context, "setLayer:", layer);

        var contextId = SendUInt(context, "contextId");
        diagnostic += $" | contextId={contextId}";
        if (contextId == 0)
        {
            diagnostic += " (not published)";
            return null;
        }
        return contextId;
    }

    private static string ClassNameOf(nint obj)
    {
        if (obj == 0)
            return "nil";
        var name = class_getName(object_getClass(obj));
        return name == 0 ? "?" : Marshal.PtrToStringAnsi(name) ?? "?";
    }

    /// <summary>The layer's immediate children by class, to show where the renderer's own layer actually sits.</summary>
    private static string SublayerClasses(nint layer)
    {
        var sublayers = Send(layer, "sublayers");
        if (sublayers == 0)
            return "";
        var count = (int)Send(sublayers, "count");
        var names = new List<string>(count);
        for (var i = 0; i < count && i < 8; i++)
            names.Add(ClassNameOf(Send(sublayers, "objectAtIndex:", i)));
        return string.Join(", ", names);
    }

    /// <summary>
    /// Stops the process ever acquiring a Dock tile, which has to beat the engine to two separate promotions.
    ///
    /// <c>OS_MacOS_NSApp</c>'s constructor calls <c>[NSApp setActivationPolicy:Regular]</c> unconditionally
    /// ("in case we are unbundled"), and that happens inside the engine's own startup, so nothing this process
    /// does afterwards can prevent the tile appearing -- the call itself has to stop meaning what it says.
    /// Swapping the method's implementation before the engine starts is the only point of control.
    ///
    /// Call before constructing the engine. Returns the reason it could not, or null on success.
    /// </summary>
    public static unsafe string? PinToAccessory()
    {
        if (!OperatingSystem.IsMacOS())
            return "not macOS";
        // objc_getClass cannot see a class whose framework is not loaded yet, and at this point nothing has
        // pulled in AppKit: the engine is what normally does it.
        if (dlopen(AppKitPath, RtldLazy) == 0)
            return "AppKit did not load";
        var appClass = objc_getClass("NSApplication");
        if (appClass == 0)
            return "no NSApplication class";
        var selector = sel_registerName("setActivationPolicy:");
        var method = class_getInstanceMethod(appClass, selector);
        if (method == 0)
            return "no setActivationPolicy: method";
        originalSetActivationPolicy = method_getImplementation(method);
        if (originalSetActivationPolicy == 0)
            return "setActivationPolicy: has no implementation";
        method_setImplementation(method, (nint)(delegate* unmanaged<nint, nint, nint, byte>)&ForceAccessory);
        return null;
    }

    private static nint originalSetActivationPolicy;

    /// <summary>Answers every activation-policy request with Accessory, whatever was asked for.</summary>
    [UnmanagedCallersOnly]
    private static unsafe byte ForceAccessory(nint self, nint selector, nint policy)
    {
        var original = (delegate* unmanaged<nint, nint, nint, byte>)originalSetActivationPolicy;
        return original(self, selector, NSApplicationActivationPolicyAccessory);
    }

    /// <summary>
    /// Undoes the engine's "unbundled window activation hack", which registers the process as a foreground
    /// application through <c>TransformProcessType</c> -- a Carbon call that goes around NSApplication, so
    /// <see cref="PinToAccessory"/> cannot intercept it. It is scheduled on the run loop a few tens of
    /// milliseconds after the window is activated, so this has to be re-asserted while the engine starts up
    /// rather than called once.
    ///
    /// Cheap and idempotent: asking for a policy the process already has changes nothing.
    /// </summary>
    public static void KeepOutOfDock()
    {
        if (!OperatingSystem.IsMacOS())
            return;
        if (sharedApp == 0)
        {
            var appClass = objc_getClass("NSApplication");
            if (appClass == 0)
                return;
            sharedApp = Send(appClass, "sharedApplication");
            if (sharedApp == 0)
                return;
        }
        Send(sharedApp, "setActivationPolicy:", NSApplicationActivationPolicyAccessory);
    }

    private static nint sharedApp;

    private static nint Send(nint target, string selector) =>
        objc_msgSend_ptr(target, sel_registerName(selector));

    private static nint Send(nint target, string selector, nint argument) =>
        objc_msgSend_ptr_ptr(target, sel_registerName(selector), argument);

    private static uint SendUInt(nint target, string selector) =>
        objc_msgSend_uint(target, sel_registerName(selector));

    // No Dock tile and no menu bar, but the process can still become active, so nothing about presenting the
    // engine's layer depends on it. Prohibited -- what a non-bundled executable launches as -- would hide it
    // equally well but also forbids activation, which is a larger bet than the Dock tile is worth.
    private const nint NSApplicationActivationPolicyAccessory = 1;

    private const string ObjCRuntime = "/usr/lib/libobjc.A.dylib";

    [DllImport(ObjCRuntime)]
    private static extern nint objc_getClass(string name);

    [DllImport(ObjCRuntime)]
    private static extern nint object_getClass(nint obj);

    [DllImport(ObjCRuntime)]
    private static extern nint class_getName(nint cls);

    [DllImport(ObjCRuntime)]
    private static extern nint sel_registerName(string name);

    // objc_msgSend is not variadic on arm64: each return and argument shape needs its own declaration.
    [DllImport(ObjCRuntime, EntryPoint = "objc_msgSend")]
    private static extern nint objc_msgSend_ptr(nint self, nint op);

    [DllImport(ObjCRuntime, EntryPoint = "objc_msgSend")]
    private static extern nint objc_msgSend_ptr_ptr(nint self, nint op, nint argument);

    [DllImport(ObjCRuntime, EntryPoint = "objc_msgSend")]
    private static extern uint objc_msgSend_uint(nint self, nint op);

    [DllImport(ObjCRuntime)]
    private static extern nint class_getInstanceMethod(nint cls, nint selector);

    [DllImport(ObjCRuntime)]
    private static extern nint method_getImplementation(nint method);

    [DllImport(ObjCRuntime)]
    private static extern nint method_setImplementation(nint method, nint imp);

    private const string AppKitPath = "/System/Library/Frameworks/AppKit.framework/AppKit";
    private const int RtldLazy = 1;

    [DllImport("/usr/lib/libSystem.B.dylib")]
    private static extern nint dlopen(string path, int mode);
}
