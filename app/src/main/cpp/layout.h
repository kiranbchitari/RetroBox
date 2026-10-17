// Where the game picture goes on the screen. Header-only and free of GL/Android so it can be unit-tested on the host
// (app/src/test/cpp/layout_test.cpp). All sizes are in pixels.
#pragma once
#include <algorithm>
#include <cmath>

namespace rb {

enum ScaleMode { kScaleOriginal = 0, kScale43 = 1, kScaleStretch = 2, kScaleInteger = 3, kScale169 = 4 };

struct Rect { int x = 0, y = 0, w = 0, h = 0; };

// surfaceW/H: the whole output; frameW/H: the core's frame; srcAspect: the picture's display aspect (<= 0 = frame shape);
// safePermille: margin kept free on every side, in 1/1000 of the surface (overscan / safe area).
inline Rect computeLayout(int mode, int surfaceW, int surfaceH, int frameW, int frameH, double srcAspect, int safePermille) {
    Rect r;
    if (surfaceW <= 0 || surfaceH <= 0 || frameW <= 0 || frameH <= 0) return r;
    safePermille = std::clamp(safePermille, 0, 200);
    const int availW = std::max(1, int(std::lround(surfaceW * (1.0 - 2.0 * safePermille / 1000.0))));
    const int availH = std::max(1, int(std::lround(surfaceH * (1.0 - 2.0 * safePermille / 1000.0))));
    if (srcAspect <= 0.0) srcAspect = double(frameW) / frameH;
    int dw, dh;
    if (mode == kScaleStretch) {
        dw = availW; dh = availH;
    } else if (mode == kScaleInteger) {  // whole-number scale of the frame height, width follows the picture aspect
        double s = std::floor(std::fmin(double(availH) / frameH, double(availW) / (frameH * srcAspect)));
        if (s < 1.0) s = 1.0;
        dh = int(frameH * s); dw = int(std::lround(frameH * s * srcAspect));
    } else {
        double aspect = mode == kScale43 ? 4.0 / 3.0 : mode == kScale169 ? 16.0 / 9.0 : srcAspect;
        if (double(availW) / availH > aspect) { dh = availH; dw = int(std::lround(availH * aspect)); }
        else { dw = availW; dh = int(std::lround(availW / aspect)); }
    }
    r.w = dw; r.h = dh;
    r.x = (surfaceW - dw) / 2; r.y = (surfaceH - dh) / 2;
    return r;
}

// Output pixels per source pixel along each axis (what a sharp-bilinear pre-scale uses).
inline double scaleFactor(int outPx, int srcPx) { return srcPx > 0 ? double(outPx) / srcPx : 1.0; }

}  // namespace rb
