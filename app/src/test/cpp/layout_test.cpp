// Host-side test of the picture-placement maths used by the renderer (app/src/main/cpp/layout.h).
// Run: app/src/test/cpp/run.sh
#include <cstdio>
#include <cstdlib>

#include "layout.h"

static int failures = 0;
#define CHECK(cond) do { if (!(cond)) { std::printf("FAIL line %d: %s\n", __LINE__, #cond); ++failures; } } while (0)
#define CHECK_RECT(r, X, Y, W, H) do { auto _r = (r); if (_r.x != (X) || _r.y != (Y) || _r.w != (W) || _r.h != (H)) { \
    std::printf("FAIL line %d: got %d,%d %dx%d want %d,%d %dx%d\n", __LINE__, _r.x, _r.y, _r.w, _r.h, X, Y, W, H); ++failures; } } while (0)

using namespace rb;

int main() {
    // NES 256x224 shown at 8:7 pixel shape on 1920x1080: height-limited, centred horizontally.
    CHECK_RECT(computeLayout(kScaleOriginal, 1920, 1080, 256, 224, 0.0, 0), 0 + (1920 - 1234) / 2, 0, 1234, 1080);
    // Core-reported aspect wins over the frame shape (SNES 4:3 on a 256x224 frame).
    CHECK_RECT(computeLayout(kScaleOriginal, 1920, 1080, 256, 224, 4.0 / 3.0, 0), 240, 0, 1440, 1080);
    CHECK_RECT(computeLayout(kScale43, 1920, 1080, 160, 144, 0.0, 0), 240, 0, 1440, 1080);
    CHECK_RECT(computeLayout(kScale169, 1920, 1080, 240, 160, 0.0, 0), 0, 0, 1920, 1080);
    CHECK_RECT(computeLayout(kScaleStretch, 1366, 768, 240, 160, 0.0, 0), 0, 0, 1366, 768);

    // A tall/narrow output is width-limited and centred vertically.
    CHECK_RECT(computeLayout(kScale43, 800, 1280, 256, 224, 0.0, 0), 0, (1280 - 600) / 2, 800, 600);

    // Integer scale: 224 high on 1080 -> x4 (896), width follows the aspect; never fractional.
    {
        Rect r = computeLayout(kScaleInteger, 1920, 1080, 256, 224, 4.0 / 3.0, 0);
        CHECK(r.h == 896);
        CHECK(r.w == int(std::lround(896 * 4.0 / 3.0)));
        CHECK(r.y == (1080 - 896) / 2);
    }
    // Integer scale on the 1366x768 panel: 224 -> x3 = 672.
    CHECK(computeLayout(kScaleInteger, 1366, 768, 256, 224, 4.0 / 3.0, 0).h == 672);
    // Integer scale never goes below x1, even when the frame does not fit.
    CHECK(computeLayout(kScaleInteger, 300, 200, 512, 448, 4.0 / 3.0, 0).h == 448);

    // Safe area: 5% each side leaves 90% of the screen.
    CHECK_RECT(computeLayout(kScaleStretch, 1000, 500, 100, 100, 0.0, 50), 50, 25, 900, 450);
    {
        Rect r = computeLayout(kScaleOriginal, 1920, 1080, 256, 240, 1.0, 100);   // 10%: 864 high, square
        CHECK(r.h == 864 && r.w == 864 && r.y == 108);
    }
    // The margin is clamped (20% per side at most) so the picture can never vanish.
    CHECK(computeLayout(kScaleStretch, 1000, 1000, 10, 10, 0.0, 900).w == 600);
    CHECK(computeLayout(kScaleStretch, 1000, 1000, 10, 10, 0.0, -5).w == 1000);

    // Degenerate input yields an empty rectangle instead of dividing by zero.
    CHECK_RECT(computeLayout(kScaleOriginal, 0, 1080, 256, 224, 0.0, 0), 0, 0, 0, 0);
    CHECK_RECT(computeLayout(kScaleOriginal, 1920, 1080, 0, 224, 0.0, 0), 0, 0, 0, 0);

    CHECK(scaleFactor(896, 224) == 4.0);
    CHECK(scaleFactor(100, 0) == 1.0);

    if (failures) { std::printf("%d failure(s)\n", failures); return 1; }
    std::printf("layout_test: all checks passed\n");
    return 0;
}
