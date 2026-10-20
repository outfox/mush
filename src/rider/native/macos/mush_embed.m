// Viewport embedding for macOS: hosts the host process's Core Animation layer inside an AWT component.
//
// macOS cannot reparent another process's window, so the Win32 approach -- take the host's HWND and SetParent it
// into the Canvas -- has no counterpart here. The host instead attaches its Metal layer to a remote CAContext and
// reports that context's id; this file wraps the id in a CALayerHost and hands the layer to AWT through JAWT's
// JAWT_SurfaceLayers protocol, which overlays it on the component's rectangle.
//
// CALayerHost is private Core Animation API, so it is reached through NSClassFromString and key-value coding
// rather than a header. A future macOS that renames or removes it then fails one attach instead of refusing to
// load the whole library.
//
// libjawt is resolved with dlopen against the running JVM's own lib directory, so the built library carries no
// link-time dependency on whichever JDK compiled it.

#include <dlfcn.h>
#include <string.h>

#include <jni.h>
#include <jawt.h>
#include <jawt_md.h>

#import <Foundation/Foundation.h>
#import <QuartzCore/QuartzCore.h>

typedef jboolean (*MushGetAwtFn)(JNIEnv *, JAWT *);

static MushGetAwtFn mushGetAwt = NULL;

JNIEXPORT jboolean JNICALL
Java_dev_twodog_mush_embed_MushNative_initJawt(JNIEnv *env, jobject self, jstring libjawtPath) {
    (void)self;
    if (mushGetAwt != NULL)
        return JNI_TRUE;

    const char *path = (*env)->GetStringUTFChars(env, libjawtPath, NULL);
    if (path == NULL)
        return JNI_FALSE;
    // RTLD_GLOBAL so the AWT internals libjawt itself calls into resolve against the already-loaded JVM.
    void *handle = dlopen(path, RTLD_LAZY | RTLD_GLOBAL);
    (*env)->ReleaseStringUTFChars(env, libjawtPath, path);
    if (handle == NULL)
        return JNI_FALSE;

    mushGetAwt = (MushGetAwtFn)dlsym(handle, "JAWT_GetAWT");
    return mushGetAwt != NULL ? JNI_TRUE : JNI_FALSE;
}

// Runs body with the component's JAWT surface layers held. JAWT requires the lock and its matching unlock on one
// thread, so callers stay on the EDT; the JDK's own layer setter forwards the change to the AppKit thread.
static jboolean withSurfaceLayers(JNIEnv *env, jobject component, void (^body)(id<JAWT_SurfaceLayers>)) {
    if (mushGetAwt == NULL)
        return JNI_FALSE;

    JAWT awt;
    memset(&awt, 0, sizeof(awt));
    awt.version = JAWT_VERSION_9;
    if (!mushGetAwt(env, &awt))
        return JNI_FALSE;

    JAWT_DrawingSurface *surface = awt.GetDrawingSurface(env, component);
    if (surface == NULL)
        return JNI_FALSE;

    jboolean handled = JNI_FALSE;
    jint lock = surface->Lock(surface);
    if ((lock & JAWT_LOCK_ERROR) == 0) {
        JAWT_DrawingSurfaceInfo *info = surface->GetDrawingSurfaceInfo(surface);
        if (info != NULL) {
            if (info->platformInfo != NULL) {
                body((__bridge id<JAWT_SurfaceLayers>)info->platformInfo);
                handled = JNI_TRUE;
            }
            surface->FreeDrawingSurfaceInfo(info);
        }
        surface->Unlock(surface);
    }
    awt.FreeDrawingSurface(surface);
    return handled;
}

static CALayer *createLayerHost(uint32_t contextId) {
    Class layerHostClass = NSClassFromString(@"CALayerHost");
    if (layerHostClass == nil)
        return nil;

    CALayer *layer = [[layerHostClass alloc] init];
    @try {
        [layer setValue:@(contextId) forKey:@"contextId"];
    } @catch (NSException *unavailable) {
        return nil;
    }
    return layer;
}

// AWT owns the frame of whichever layer is handed to JAWT: it sets that layer to the component's rectangle in
// window coordinates on every reshape. Writing that same frame from here raced it, and computing it here instead
// is not possible -- the component's position within the window is not something JAWT exposes, and guessing it
// put the viewport over the whole window at its top-left corner. So the container's frame is AWT's, with one
// deliberate exception: hiding collapses it to zero, where AWT's value cannot matter because the next show
// reshapes the component and AWT pushes a fresh rectangle.
//
// The CALayerHost goes inside that container with an autoresizing mask, so AWT sizes the container whenever it
// likes and Core Animation resizes the host layer to match. The two writers never share a property.
static CGFloat contentsScaleOf(jdouble scale) {
    return scale > 0 ? (CGFloat)scale : 1;
}

static CALayer *hostedLayer(id<JAWT_SurfaceLayers> surfaceLayers) {
    return surfaceLayers.layer.sublayers.firstObject;
}

// Java2D's Metal pipeline re-evaluates what it composites only when a sublayer list changes. Setting a property
// -- `hidden`, or even clearing the surface layer outright -- changes nothing on screen until the tree moves, and
// a bare removal does not count either. Re-inserting the layer is the one operation it reliably notices, so every
// visual change ends with this. Call on the main thread: Core Animation expects it and the EDT is not it.
// Applies `mutate` and leaves the layer re-inserted, which is the only change Java2D's Metal pipeline notices:
// until the sublayer list moves, a newly attached layer never appears and a hidden one never goes. Core Animation
// animates property and tree changes by default -- that faded the viewport out over a quarter second instead of
// hiding it -- so actions stay disabled throughout. On the main queue, because Core Animation expects it and the
// EDT is not it.
static void applyOnMain(CALayer *container, void (^mutate)(void)) {
    dispatch_async(dispatch_get_main_queue(), ^{
        [CATransaction begin];
        [CATransaction setDisableActions:YES];
        if (mutate != nil)
            mutate();
        CALayer *parent = container.superlayer;
        if (parent != nil) {
            [container removeFromSuperlayer];
            [parent addSublayer:container];
        }
        [CATransaction commit];
    });
}

JNIEXPORT jboolean JNICALL
Java_dev_twodog_mush_embed_MushNative_attachLayerHost(JNIEnv *env, jobject self, jobject component,
                                                      jint contextId, jdouble scale) {
    (void)self;
    __block jboolean attached = JNI_FALSE;
    withSurfaceLayers(env, component, ^(id<JAWT_SurfaceLayers> surfaceLayers) {
        CALayer *host = createLayerHost((uint32_t)contextId);
        if (host == nil)
            return;
        [CATransaction begin];
        [CATransaction setDisableActions:YES];
        host.contentsScale = contentsScaleOf(scale);
        // The host repaints at its own pace, so a frame caught mid-resize scales instead of tearing.
        host.contentsGravity = kCAGravityResize;
        // Follow the container through every resize AWT applies to it, including the first one after attach.
        host.autoresizingMask = kCALayerWidthSizable | kCALayerHeightSizable;

        CALayer *container = [CALayer layer];
        container.masksToBounds = YES;
        [container addSublayer:host];
        surfaceLayers.layer = container;
        [CATransaction commit];

        applyOnMain(container, nil);
        attached = JNI_TRUE;
    });
    return attached;
}

JNIEXPORT jboolean JNICALL
Java_dev_twodog_mush_embed_MushNative_setLayerState(JNIEnv *env, jobject self, jobject component,
                                                    jboolean visible, jdouble scale) {
    (void)self;
    __block jboolean applied = JNI_FALSE;
    withSurfaceLayers(env, component, ^(id<JAWT_SurfaceLayers> surfaceLayers) {
        CALayer *container = surfaceLayers.layer;
        CALayer *host = hostedLayer(surfaceLayers);
        if (container == nil)
            return;
        applyOnMain(container, ^{
            // Hiding the AWT component leaves a hosted layer composited, so the viewport is hidden here by hand.
            BOOL hide = (visible == JNI_FALSE);
            container.hidden = hide;
            // Stepping out reparents the Canvas, and AWT pushes the layered pane's whole-window rectangle onto
            // this layer. That lands before the hide does, so a layer still at full size shows at the window's
            // top-left on the way out. Collapsed, there is nothing to see in that gap.
            if (hide)
                container.frame = CGRectZero;
            if (host != nil)
                host.contentsScale = contentsScaleOf(scale);
        });
        applied = JNI_TRUE;
    });
    return applied;
}

/** The container's size in points, packed width:height, or -1 when nothing is hosted. */
JNIEXPORT jlong JNICALL
Java_dev_twodog_mush_embed_MushNative_layerSize(JNIEnv *env, jobject self, jobject component) {
    (void)self;
    __block jlong packed = -1;
    withSurfaceLayers(env, component, ^(id<JAWT_SurfaceLayers> surfaceLayers) {
        CALayer *container = surfaceLayers.layer;
        if (container == nil)
            return;
        jlong width = (jlong)container.frame.size.width;
        jlong height = (jlong)container.frame.size.height;
        packed = (width << 32) | (height & 0xFFFFFFFFLL);
    });
    return packed;
}

/**
 * The layer tree's real geometry, for the editor's status line. The Windows backend reports the child window's
 * actual rectangle next to what it believes; without the same here, a viewport that renders nothing cannot be
 * told apart from one placed off-screen.
 */
JNIEXPORT jstring JNICALL
Java_dev_twodog_mush_embed_MushNative_layerState(JNIEnv *env, jobject self, jobject component) {
    (void)self;
    __block NSString *state = @"not hosted";
    withSurfaceLayers(env, component, ^(id<JAWT_SurfaceLayers> surfaceLayers) {
        CALayer *container = surfaceLayers.layer;
        CALayer *host = hostedLayer(surfaceLayers);
        if (container == nil) {
            state = @"no layer";
            return;
        }
        state = [NSString stringWithFormat:@"container=(%.0f,%.0f %.0fx%.0f)%@ host=(%.0fx%.0f)@%.1fx",
            container.frame.origin.x, container.frame.origin.y,
            container.frame.size.width, container.frame.size.height,
            container.hidden ? @" hidden" : @"",
            host ? host.frame.size.width : -1, host ? host.frame.size.height : -1,
            host ? host.contentsScale : 0];
    });
    return (*env)->NewStringUTF(env, [state UTF8String]);
}

/**
 * Clips the hosted layer so the given rectangles show whatever is behind it.
 *
 * On Windows AWT does this itself: it cuts the native child's window region wherever a lightweight overlaps, which
 * is what `isCutAway` observes through `GetWindowRgn`. A hosted layer gets no such treatment -- a sublayer always
 * composites above its parent's content, and everything Swing draws *is* that content -- so every notification,
 * popup and tooltip over the viewport would otherwise be invisible. Masking the layer reproduces the Windows
 * behaviour by hand.
 *
 * `rects` is a flat x, y, width, height sequence in the component's own coordinates, top-left origin.
 */
JNIEXPORT jboolean JNICALL
Java_dev_twodog_mush_embed_MushNative_setLayerCutouts(JNIEnv *env, jobject self, jobject component,
                                                      jintArray rects) {
    (void)self;
    jsize count = rects == NULL ? 0 : (*env)->GetArrayLength(env, rects);
    jint *values = NULL;
    if (count >= 4) {
        values = (*env)->GetIntArrayElements(env, rects, NULL);
        if (values == NULL)
            return JNI_FALSE;
    }

    // Copy out of the JNI array: the mask is built on the main queue, long after this frame has returned.
    NSMutableArray<NSValue *> *holes = [NSMutableArray array];
    for (jsize i = 0; i + 3 < count; i += 4)
        [holes addObject:[NSValue valueWithRect:NSMakeRect(values[i], values[i + 1], values[i + 2], values[i + 3])]];
    if (values != NULL)
        (*env)->ReleaseIntArrayElements(env, rects, values, JNI_ABORT);

    __block jboolean applied = JNI_FALSE;
    withSurfaceLayers(env, component, ^(id<JAWT_SurfaceLayers> surfaceLayers) {
        CALayer *container = surfaceLayers.layer;
        if (container == nil)
            return;
        applyOnMain(container, ^{
            if (holes.count == 0) {
                container.mask = nil;
                return;
            }
            // Even-odd winding turns the inner rectangles into holes in the full-bounds rectangle.
            CGRect bounds = container.bounds;
            CGMutablePathRef path = CGPathCreateMutable();
            CGPathAddRect(path, NULL, bounds);
            for (NSValue *hole in holes) {
                NSRect r = [hole rectValue];
                // AWT measures from the top, Core Animation from the bottom of the layer.
                CGPathAddRect(path, NULL, CGRectMake(r.origin.x,
                                                     bounds.size.height - r.origin.y - r.size.height,
                                                     r.size.width, r.size.height));
            }
            CAShapeLayer *mask = [CAShapeLayer layer];
            mask.frame = bounds;
            mask.fillRule = kCAFillRuleEvenOdd;
            mask.fillColor = CGColorGetConstantColor(kCGColorBlack);
            mask.path = path;
            CGPathRelease(path);
            container.mask = mask;
        });
        applied = JNI_TRUE;
    });
    return applied;
}

JNIEXPORT jboolean JNICALL
Java_dev_twodog_mush_embed_MushNative_detachLayer(JNIEnv *env, jobject self, jobject component) {
    (void)self;
    return withSurfaceLayers(env, component, ^(id<JAWT_SurfaceLayers> surfaceLayers) {
        CALayer *container = surfaceLayers.layer;
        surfaceLayers.layer = nil;
        // The one mutation that does not re-insert: the removal is itself the change the pipeline notices.
        if (container != nil)
            dispatch_async(dispatch_get_main_queue(), ^{
                [CATransaction begin];
                [CATransaction setDisableActions:YES];
                [container removeFromSuperlayer];
                [CATransaction commit];
            });
    });
}
