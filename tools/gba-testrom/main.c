/* Original (CC0) GBA test program for RetroBox. Mode 3 (240x160, 15-bit colour).
 *
 *   checkerboard background, a 16x16 square that the D-pad moves,
 *   background colours follow the LAST pressed button (sticky):  A red, B green, L cyan, R magenta, Select yellow, initially blue
 *   (A also plays a tone on sound channel 1)
 *   Start: counter++ (0..20, wraps), stored in cartridge SRAM at 0x0E000001 (magic 0xA5 at 0x0E000000),
 *          drawn as a bar of filled 8x8 cells along the bottom.
 * No globals: the startup code does not initialise .data/.bss.
 */
typedef unsigned short u16;
typedef unsigned char u8;
typedef unsigned int u32;

#define R16(a) (*(volatile u16 *)(a))
#define VRAM ((volatile u16 *)0x06000000)
#define KEYINPUT R16(0x04000130)
#define VCOUNT R16(0x04000006)
#define SRAM ((volatile u8 *)0x0E000000)

#define KEY_A 0x001
#define KEY_B 0x002
#define KEY_SELECT 0x004
#define KEY_START 0x008
#define KEY_RIGHT 0x010
#define KEY_LEFT 0x020
#define KEY_UP 0x040
#define KEY_DOWN 0x080
#define KEY_R 0x100
#define KEY_L 0x200

#define RGB(r, g, b) ((u16)((r) | ((g) << 5) | ((b) << 10)))

/* Flash/SRAM save-type marker that emulators look for in the ROM image. */
__attribute__((used)) static const char save_tag[] = "SRAM_V113";

static inline u16 bgpix(int x, int y, u16 ca, u16 cb) { return (((x >> 3) ^ (y >> 3)) & 1) ? ca : cb; }

static void paint_bg(int x0, int y0, int w, int h, u16 ca, u16 cb) {
    for (int y = y0; y < y0 + h; y++)
        for (int x = x0; x < x0 + w; x++) VRAM[y * 240 + x] = bgpix(x, y, ca, cb);
}

static void fill_rect(int x0, int y0, int w, int h, u16 c) {
    for (int y = y0; y < y0 + h; y++)
        for (int x = x0; x < x0 + w; x++) VRAM[y * 240 + x] = c;
}

static void draw_bar(int cnt, u16 ca, u16 cb) {
    for (int i = 0; i < 20; i++) {
        if (i < cnt) fill_rect(i * 8, 144, 8, 8, 0x7FFF);
        else paint_bg(i * 8, 144, 8, 8, ca, cb);
    }
}

int main(void) {
    R16(0x04000000) = 0x0403;   /* mode 3, BG2 on */
    R16(0x04000084) = 0x0080;   /* sound master enable */
    R16(0x04000080) = 0xFF77;   /* PSG volume, all channels to both sides */
    R16(0x04000082) = 0x0002;   /* PSG 100% */

    int cnt = 0;
    if (SRAM[0] == 0xA5) cnt = SRAM[1];
    else { SRAM[0] = 0xA5; SRAM[1] = 0; }
    if (cnt > 20) cnt = 0;

    int sx = 112, sy = 60, state = -1, prev_start = 0;
    u16 ca = 0, cb = 0;
    for (;;) {
        while (VCOUNT >= 160) {}
        while (VCOUNT < 160) {}
        u32 k = ~KEYINPUT & 0x3FF;

        int ns = (k & KEY_A) ? 1 : (k & KEY_B) ? 2 : (k & KEY_L) ? 3 : (k & KEY_R) ? 4 : (k & KEY_SELECT) ? 5 : (state < 0 ? 0 : state);
        if (ns != state) {
            static const u16 pal[6][2] = {
                {RGB(2, 2, 12), RGB(6, 6, 18)},  {RGB(24, 2, 2), RGB(30, 8, 8)},   {RGB(2, 22, 2), RGB(8, 28, 8)},
                {RGB(2, 22, 22), RGB(8, 28, 28)}, {RGB(24, 2, 24), RGB(30, 8, 30)}, {RGB(24, 24, 2), RGB(30, 30, 8)}};
            ca = pal[ns][0]; cb = pal[ns][1];
            for (int y = 0; y < 160; y++)
                for (int x = 0; x < 240; x++) VRAM[y * 240 + x] = bgpix(x, y, ca, cb);
            draw_bar(cnt, ca, cb);
            fill_rect(sx, sy, 16, 16, 0x7FFF);
            if (ns == 1) { R16(0x04000060) = 0; R16(0x04000062) = 0xF080; R16(0x04000064) = 0x8000 | 0x700; }
            else if (state == 1) R16(0x04000062) = 0;
            state = ns;
        } else {
            int nx = sx, ny = sy;
            if (k & KEY_RIGHT) nx += 2;
            if (k & KEY_LEFT) nx -= 2;
            if (k & KEY_UP) ny -= 2;
            if (k & KEY_DOWN) ny += 2;
            if (nx < 0) nx = 0;
            if (nx > 224) nx = 224;
            if (ny < 0) ny = 0;
            if (ny > 127) ny = 127;
            if (nx != sx || ny != sy) {
                paint_bg(sx, sy, 16, 16, ca, cb);
                sx = nx; sy = ny;
                fill_rect(sx, sy, 16, 16, 0x7FFF);
            }
        }
        int st = (k & KEY_START) != 0;
        if (st && !prev_start) {
            cnt = cnt >= 20 ? 0 : cnt + 1;
            SRAM[1] = (u8)cnt;
            draw_bar(cnt, ca, cb);
        }
        prev_start = st;
    }
}
