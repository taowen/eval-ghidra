// Chapter 04 — virtual calls and callback registration.
//
// Two implementations of one interface, plus a registered callback. Ghidra does
// not see the edge through the vtable, so get_function_callers is empty for both
// render() targets.

#include <cstdint>

struct Frame {
    int id;
    float time;
};

struct IRenderer {
    virtual void initialize(int mode) = 0;
    virtual void render(const Frame* f) = 0;
    virtual ~IRenderer() = default;
};

struct GlesRenderer : IRenderer {
    int mode_;
    void initialize(int mode) override { mode_ = mode; }
    void render(const Frame* f) override {
        // GLES path: encode the frame id.
        mode_ += f->id;
    }
};

struct NullRenderer : IRenderer {
    int count_;
    void initialize(int mode) override { count_ = mode; }
    void render(const Frame* f) override {
        // Null path: only count.
        count_ += 1;
        (void)f;
    }
};

// Callback type and a registration sink.
typedef void (*FrameCallback)(int frame_id);

static FrameCallback g_callback = nullptr;

extern "C" {

__attribute__((visibility("default"), noinline))
void drive(IRenderer* r, const Frame* f) {
    r->render(f);            // indirect call through vtable slot +8
}

__attribute__((visibility("default"), noinline))
void register_cb(FrameCallback cb) {
    g_callback = cb;
}

__attribute__((visibility("default"), noinline))
void on_frame(int frame_id) {
    if (g_callback != nullptr) {
        g_callback(frame_id);
    }
}

__attribute__((visibility("default"), noinline))
IRenderer* make_gles() {
    static GlesRenderer r;
    return &r;
}

__attribute__((visibility("default"), noinline))
IRenderer* make_null() {
    static NullRenderer r;
    return &r;
}

} // extern "C"
