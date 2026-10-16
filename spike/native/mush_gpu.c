#define _GNU_SOURCE
#define GL_GLEXT_PROTOTYPES
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>
#include <vulkan/vulkan.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/stat.h>
#include <poll.h>
#include <unistd.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>

/* Experimental Linux consumer. No frame readback occurs except in mush_gpu_sample (probe only).
 * Each context and its GL resources are owned by one caller thread. A frame is acknowledged only after glFinish;
 * the producer must not reuse its shared allocation before that acknowledgement. */
static _Thread_local char last_error[512];
const char *mush_gpu_error(void) { return last_error; }
static int fail(const char *what) {
    snprintf(last_error, sizeof(last_error), "%s (errno=%d, EGL=0x%x)", what, errno, eglGetError());
    return -1;
}

int mush_channel_listen(const char *path) {
    struct sockaddr_un addr = {.sun_family = AF_UNIX};
    if (strlen(path) >= sizeof(addr.sun_path)) return fail("Unix socket path too long");
    strcpy(addr.sun_path, path);
    int fd = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
    if (fd < 0) return fail("socket");
    if (bind(fd, (struct sockaddr *)&addr, sizeof(addr)) || chmod(path, 0600) || listen(fd, 1)) {
        close(fd); return fail("bind/chmod/listen");
    }
    return fd;
}

int mush_channel_accept(int server, int timeout_ms) {
    struct pollfd p = {.fd = server, .events = POLLIN};
    if (poll(&p, 1, timeout_ms) <= 0) return fail("accept timeout");
    int fd = accept4(server, NULL, NULL, SOCK_CLOEXEC);
    if (fd < 0) return fail("accept");
    struct ucred cred;
    socklen_t len = sizeof(cred);
    if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &cred, &len) || cred.uid != geteuid()) {
        close(fd); return fail("GPU peer has a different user");
    }
    return fd;
}

int mush_channel_send(int channel, const void *data, int length, int resource_fd) {
    struct iovec iov = {.iov_base = (void *)data, .iov_len = (size_t)length};
    char control[CMSG_SPACE(sizeof(int))] = {0};
    struct msghdr msg = {.msg_iov = &iov, .msg_iovlen = 1};
    if (resource_fd >= 0) {
        msg.msg_control = control; msg.msg_controllen = sizeof(control);
        struct cmsghdr *c = CMSG_FIRSTHDR(&msg);
        c->cmsg_level = SOL_SOCKET; c->cmsg_type = SCM_RIGHTS; c->cmsg_len = CMSG_LEN(sizeof(int));
        memcpy(CMSG_DATA(c), &resource_fd, sizeof(int));
    }
    int count = (int)sendmsg(channel, &msg, MSG_NOSIGNAL | MSG_DONTWAIT);
    if (count < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) return 0;
    return count == length ? count : fail("sendmsg");
}

int mush_channel_receive(int channel, void *data, int capacity, int *resource_fd, int timeout_ms) {
    *resource_fd = -1;
    struct pollfd p = {.fd = channel, .events = POLLIN};
    int ready;
    do { ready = poll(&p, 1, timeout_ms); } while (ready < 0 && errno == EINTR);
    if (ready == 0) return 0;
    if (ready < 0) return fail("poll");
    struct iovec iov = {.iov_base = data, .iov_len = (size_t)capacity};
    char control[CMSG_SPACE(sizeof(int) * 4)] = {0};
    struct msghdr msg = {.msg_iov = &iov, .msg_iovlen = 1, .msg_control = control, .msg_controllen = sizeof(control)};
    int count = (int)recvmsg(channel, &msg, MSG_CMSG_CLOEXEC);
    int fd_count = 0;
    for (struct cmsghdr *c = CMSG_FIRSTHDR(&msg); c; c = CMSG_NXTHDR(&msg, c)) {
        if (c->cmsg_level != SOL_SOCKET || c->cmsg_type != SCM_RIGHTS) continue;
        size_t n = (c->cmsg_len - CMSG_LEN(0)) / sizeof(int);
        for (size_t i = 0; i < n; ++i) {
            int received; memcpy(&received, CMSG_DATA(c) + i * sizeof(int), sizeof(int));
            if (fd_count++ == 0) *resource_fd = received; else close(received);
        }
    }
    if (count <= 0 || (msg.msg_flags & (MSG_TRUNC | MSG_CTRUNC)) || fd_count > 1) {
        if (*resource_fd >= 0) close(*resource_fd);
        *resource_fd = -1;
        return fail("GPU channel closed or malformed packet");
    }
    return count;
}

void mush_close_fd(int fd) { if (fd >= 0) close(fd); }

/* Driver/device UUIDs are compatibility requirements for opaque external memory, not the pipeline-cache UUID. */
int mush_vulkan_uuid(uint64_t physical_device, unsigned char *device_uuid, unsigned char *driver_uuid) {
    VkPhysicalDeviceIDProperties ids = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ID_PROPERTIES};
    VkPhysicalDeviceProperties2 props = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2, .pNext = &ids};
    vkGetPhysicalDeviceProperties2((VkPhysicalDevice)(uintptr_t)physical_device, &props);
    memcpy(device_uuid, ids.deviceUUID, VK_UUID_SIZE);
    memcpy(driver_uuid, ids.driverUUID, VK_UUID_SIZE);
    return 0;
}

typedef struct {
    EGLDisplay display;
    EGLContext context;
    EGLSurface surface;
    GLuint memory, texture, program;
    int width, height;
    void *wayland_egl, *window;
    char info[512];
    PFNGLCREATEMEMORYOBJECTSEXTPROC create_memory;
    PFNGLDELETEMEMORYOBJECTSEXTPROC delete_memory;
    PFNGLIMPORTMEMORYFDEXTPROC import_fd;
    PFNGLTEXSTORAGEMEM2DEXTPROC texture_storage;
    PFNGLGETUNSIGNEDBYTEVEXTPROC get_bytes;
    PFNGLGETUNSIGNEDBYTEI_VEXTPROC get_indexed_bytes;
} MushGpu;

static GLuint compile_shader(GLenum type, const char *source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, NULL); glCompileShader(shader);
    GLint ok; glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (!ok) { glGetShaderInfoLog(shader, sizeof(last_error), NULL, last_error); glDeleteShader(shader); return 0; }
    return shader;
}

void mush_gpu_destroy(MushGpu *gpu);

MushGpu *mush_gpu_create(void *wayland_display, void *wayland_surface, int width, int height) {
    MushGpu *gpu = calloc(1, sizeof(*gpu));
    if (!gpu) { fail("allocation"); return NULL; }
    PFNEGLGETPLATFORMDISPLAYEXTPROC platform_display = (void *)eglGetProcAddress("eglGetPlatformDisplayEXT");
    if (!platform_display) { fail("EGL platform display unavailable"); goto failed; }
    gpu->display = platform_display(wayland_display ? EGL_PLATFORM_WAYLAND_EXT : EGL_PLATFORM_SURFACELESS_MESA,
                                    wayland_display, NULL);
    EGLint major, minor;
    if (!gpu->display || !eglInitialize(gpu->display, &major, &minor) || !eglBindAPI(EGL_OPENGL_ES_API)) {
        fail("EGL initialization"); goto failed;
    }
    EGLint attributes[] = {EGL_SURFACE_TYPE, wayland_surface ? EGL_WINDOW_BIT : EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8, EGL_NONE};
    EGLConfig config; EGLint count;
    if (!eglChooseConfig(gpu->display, attributes, &config, 1, &count) || !count) { fail("EGL config"); goto failed; }
    EGLint context_attributes[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    gpu->context = eglCreateContext(gpu->display, config, EGL_NO_CONTEXT, context_attributes);
    if (wayland_surface) {
        gpu->wayland_egl = dlopen("libwayland-egl.so.1", RTLD_NOW | RTLD_LOCAL);
        void *(*create_window)(void *, int, int) = gpu->wayland_egl ? dlsym(gpu->wayland_egl, "wl_egl_window_create") : NULL;
        if (!create_window || !(gpu->window = create_window(wayland_surface, width, height))) { fail("Wayland EGL window"); goto failed; }
        gpu->surface = eglCreateWindowSurface(gpu->display, config, (EGLNativeWindowType)gpu->window, NULL);
    } else {
        EGLint size[] = {EGL_WIDTH, width, EGL_HEIGHT, height, EGL_NONE};
        gpu->surface = eglCreatePbufferSurface(gpu->display, config, size);
    }
    if (!gpu->context || !gpu->surface || !eglMakeCurrent(gpu->display, gpu->surface, gpu->surface, gpu->context)) {
        fail("EGL context/surface"); goto failed;
    }
    eglSwapInterval(gpu->display, 0);
    const char *extensions = (const char *)glGetString(GL_EXTENSIONS);
    snprintf(gpu->info, sizeof(gpu->info), "EGL %d.%d; %s; %s; memory_object_fd=%s", major, minor,
        glGetString(GL_VENDOR), glGetString(GL_RENDERER),
        extensions && strstr(extensions, "GL_EXT_memory_object_fd") ? "yes" : "no");
    if (!extensions || !strstr(extensions, "GL_EXT_memory_object_fd")) {
        snprintf(last_error, sizeof(last_error), "No GL_EXT_memory_object_fd: %.400s", gpu->info); goto failed;
    }
#define LOAD(field, name) gpu->field = (void *)eglGetProcAddress(name); if (!gpu->field) { fail(name); goto failed; }
    LOAD(create_memory, "glCreateMemoryObjectsEXT")
    LOAD(delete_memory, "glDeleteMemoryObjectsEXT")
    LOAD(import_fd, "glImportMemoryFdEXT")
    LOAD(texture_storage, "glTexStorageMem2DEXT")
    LOAD(get_bytes, "glGetUnsignedBytevEXT")
    LOAD(get_indexed_bytes, "glGetUnsignedBytei_vEXT")
#undef LOAD
    const char *vertex = "#version 300 es\nout vec2 uv;void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);uv=vec2(p.x,1.0-p.y);gl_Position=vec4(p*2.0-1.0,0,1);}";
    const char *fragment = "#version 300 es\nprecision highp float;in vec2 uv;uniform sampler2D image;out vec4 color;void main(){color=texture(image,uv);}";
    GLuint vs = compile_shader(GL_VERTEX_SHADER, vertex), fs = compile_shader(GL_FRAGMENT_SHADER, fragment);
    if (!vs || !fs) { if (vs) glDeleteShader(vs); if (fs) glDeleteShader(fs); goto failed; }
    gpu->program = glCreateProgram(); glAttachShader(gpu->program, vs); glAttachShader(gpu->program, fs);
    glLinkProgram(gpu->program); glDeleteShader(vs); glDeleteShader(fs);
    GLint linked; glGetProgramiv(gpu->program, GL_LINK_STATUS, &linked);
    if (!linked) { glGetProgramInfoLog(gpu->program, sizeof(last_error), NULL, last_error); goto failed; }
    gpu->width = width; gpu->height = height;
    return gpu;
failed:
    mush_gpu_destroy(gpu); return NULL;
}

const char *mush_gpu_info(MushGpu *gpu) { return gpu->info; }

int mush_gpu_import(MushGpu *gpu, int fd, uint64_t memory_size, int width, int height,
                    const unsigned char *device_uuid, const unsigned char *driver_uuid) {
    unsigned char device[16], driver[16];
    gpu->get_indexed_bytes(GL_DEVICE_UUID_EXT, 0, device); gpu->get_bytes(GL_DRIVER_UUID_EXT, driver);
    if (memcmp(device, device_uuid, 16) || memcmp(driver, driver_uuid, 16)) {
        close(fd); return fail("Producer/consumer device or driver UUID differs");
    }
    if (gpu->texture) glDeleteTextures(1, &gpu->texture);
    if (gpu->memory) gpu->delete_memory(1, &gpu->memory);
    gpu->texture = gpu->memory = 0;
    gpu->create_memory(1, &gpu->memory);
    gpu->import_fd(gpu->memory, memory_size, GL_HANDLE_TYPE_OPAQUE_FD_EXT, fd);
    GLenum error = glGetError();
    if (error) { close(fd); snprintf(last_error, sizeof(last_error), "glImportMemoryFdEXT: 0x%x", error); return -1; }
    // The GL implementation owns the received fd from here on; the producer's fd is a different descriptor.
    glGenTextures(1, &gpu->texture); glBindTexture(GL_TEXTURE_2D, gpu->texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_TILING_EXT, GL_OPTIMAL_TILING_EXT);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    gpu->texture_storage(GL_TEXTURE_2D, 1, GL_RGBA8, width, height, gpu->memory, 0);
    error = glGetError();
    if (error) { snprintf(last_error, sizeof(last_error), "glTexStorageMem2DEXT: 0x%x", error); return -1; }
    return 0;
}

int mush_gpu_draw(MushGpu *gpu) {
    glViewport(0, 0, gpu->width, gpu->height);
    glUseProgram(gpu->program); glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, gpu->texture);
    glDrawArrays(GL_TRIANGLES, 0, 3);
    glFinish(); // First spike: explicit consumer completion before releasing the producer's single frame lease.
    GLenum error = glGetError();
    if (error) { snprintf(last_error, sizeof(last_error), "GPU draw: 0x%x", error); return -1; }
    return 0;
}

int mush_gpu_present(MushGpu *gpu) {
    return eglSwapBuffers(gpu->display, gpu->surface) ? 0 : fail("eglSwapBuffers");
}

/* JBR's subsurface helper creates shadow surfaces below the parent. The viewport goes above it, with input still
 * passing through to Swing. wl_subsurface.place_above is core protocol opcode 2; set_desync is opcode 5. */
int mush_wayland_subsurface_above(void *subsurface, void *parent) {
    void *library = dlopen("libwayland-client.so.0", RTLD_NOW | RTLD_LOCAL);
    if (!library) return fail("Wayland client library");
    void *(*marshal)(void *, unsigned int, const void *, unsigned int, unsigned int, ...) = dlsym(library, "wl_proxy_marshal_flags");
    if (!marshal) { dlclose(library); return fail("Wayland proxy marshal"); }
    marshal(subsurface, 2, NULL, 1, 0, parent);
    marshal(subsurface, 5, NULL, 1, 0);
    dlclose(library);
    return 0;
}

int mush_gpu_sample(MushGpu *gpu, int x, int y, unsigned char *rgba) {
    (void)gpu;
    glReadPixels(x, y, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
    return glGetError() == GL_NO_ERROR ? 0 : fail("probe pixel readback");
}

void mush_gpu_destroy(MushGpu *gpu) {
    if (!gpu) return;
    if (gpu->context && gpu->surface) {
        eglMakeCurrent(gpu->display, gpu->surface, gpu->surface, gpu->context);
        if (gpu->texture) glDeleteTextures(1, &gpu->texture);
        if (gpu->memory && gpu->delete_memory) gpu->delete_memory(1, &gpu->memory);
        if (gpu->program) glDeleteProgram(gpu->program);
        eglMakeCurrent(gpu->display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    }
    if (gpu->surface) eglDestroySurface(gpu->display, gpu->surface);
    if (gpu->context) eglDestroyContext(gpu->display, gpu->context);
    if (gpu->window) {
        void (*destroy_window)(void *) = dlsym(gpu->wayland_egl, "wl_egl_window_destroy");
        destroy_window(gpu->window);
    }
    // Never eglTerminate a display shared with JBR. The process-local EGL display is retained until exit.
    if (gpu->wayland_egl) dlclose(gpu->wayland_egl);
    free(gpu);
}
