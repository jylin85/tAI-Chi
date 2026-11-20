"""
Coach window renderer. Uses Pillow (not cv2.putText) so expert-written text can contain
accents or Chinese move names. Layout: webcam on the left, target pose + guidance on the right.
"""
import os

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

W, H = 1000, 620
VID_X, VID_Y, VID_W, VID_H = 10, 60, 640, 480
PANEL_X, PANEL_W = 662, 328
PANEL_BOTTOM = H - 16
BG = (36, 32, 32)  # BGR

GREEN, ORANGE, RED = (80, 200, 100), (255, 170, 60), (255, 90, 90)
WHITE, GREY, YELLOW, CYAN = (240, 240, 240), (160, 160, 165), (250, 220, 90), (120, 210, 255)

_FONTS = {
    "regular": ["C:/Windows/Fonts/msyh.ttc", "C:/Windows/Fonts/segoeui.ttf", "C:/Windows/Fonts/arial.ttf",
                "/System/Library/Fonts/PingFang.ttc", "/System/Library/Fonts/Helvetica.ttc",
                "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
                "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"],
    "bold": ["C:/Windows/Fonts/msyhbd.ttc", "C:/Windows/Fonts/segoeuib.ttf", "C:/Windows/Fonts/arialbd.ttf",
             "/System/Library/Fonts/PingFang.ttc", "/System/Library/Fonts/Helvetica.ttc",
             "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc",
             "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"],
}


class Renderer:
    def __init__(self, font_path=None):
        self.paths = {k: ([font_path] if font_path else []) + v for k, v in _FONTS.items()}
        self.cache = {}
        probe = self.font(16)
        if not isinstance(probe, ImageFont.FreeTypeFont):
            print("Warning: no TrueType font found; non-ASCII text may not display. Use --font PATH.")

    def font(self, size, bold=False):
        key = (size, bold)
        if key not in self.cache:
            f = None
            for p in self.paths["bold" if bold else "regular"]:
                if os.path.exists(p):
                    try:
                        f = ImageFont.truetype(p, size)
                        break
                    except OSError:
                        pass
            if f is None:
                try:
                    f = ImageFont.load_default(size)
                except TypeError:
                    f = ImageFont.load_default()
            self.cache[key] = f
        return self.cache[key]

    @staticmethod
    def wrap(d, text, font, max_w):
        """Word-wrap (and character-wrap for long/CJK runs). Honors explicit newlines."""
        lines = []
        for para in str(text).split("\n"):
            cur = ""
            for ch in para:
                if d.textlength(cur + ch, font=font) <= max_w:
                    cur += ch
                    continue
                cut = cur.rfind(" ")
                if cut > 0 and ch != " ":
                    lines.append(cur[:cut])
                    cur = cur[cut + 1:] + ch
                else:
                    lines.append(cur)
                    cur = ch.lstrip()
            lines.append(cur)
        return lines

    def block(self, d, x, y, text, font, color, max_w, y_limit, line_gap=4):
        """Draw wrapped text; truncate with an ellipsis if it would pass y_limit. Returns new y."""
        lh = font.size + line_gap if hasattr(font, "size") else 18
        lines = self.wrap(d, text, font, max_w)
        for i, line in enumerate(lines):
            if y + lh > y_limit:
                d.text((x, y), "...", font=font, fill=color)
                return y_limit
            d.text((x, y), line, font=font, fill=color)
            y += lh
        return y

    def render(self, video_bgr, s):
        """
        s keys: title, step_text, thumb (BGR or None), pose_name, instruction, key_points, mistakes,
                status, status_color, matched_frac (0-1 or None), cues [(text, is_expert)],
                banner (str or None), progress [state strings: done/skipped/current/pending], footer
        """
        canvas = np.full((H, W, 3), BG, np.uint8)
        # webcam, letterboxed into its box
        h, w = video_bgr.shape[:2]
        sc = min(VID_W / w, VID_H / h)
        nw, nh = int(w * sc), int(h * sc)
        ox, oy = VID_X + (VID_W - nw) // 2, VID_Y + (VID_H - nh) // 2
        canvas[oy:oy + nh, ox:ox + nw] = cv2.resize(video_bgr, (nw, nh))

        # reference thumbnail (top of right panel)
        panel_y = VID_Y
        th = s.get("thumb")
        if th is not None:
            tw = PANEL_W
            full_h = int(th.shape[0] * tw / th.shape[1])
            small = cv2.resize(th, (tw, full_h))[:230]
            canvas[VID_Y:VID_Y + small.shape[0], PANEL_X:PANEL_X + tw] = small
            panel_y = VID_Y + small.shape[0] + 8

        img = Image.fromarray(cv2.cvtColor(canvas, cv2.COLOR_BGR2RGB)).convert("RGBA")
        ov = Image.new("RGBA", img.size, (0, 0, 0, 0))
        d = ImageDraw.Draw(ov)
        f = self.font

        # title bar
        d.text((10, 14), s["title"], font=f(24, True), fill=WHITE + (255,))
        step = s["step_text"]
        d.text((W - 10 - d.textlength(step, font=f(20)), 18), step, font=f(20), fill=GREY + (255,))

        # right panel text
        y = panel_y
        d.text((PANEL_X, y), "TARGET POSE", font=f(12, True), fill=GREY + (255,))
        y = self.block(d, PANEL_X, y + 16, s["pose_name"], f(20, True), YELLOW + (255,), PANEL_W, PANEL_BOTTOM)
        for head, key, color in (("How to get here", "instruction", WHITE), ("Key points", "key_points", CYAN),
                                 ("Watch for", "mistakes", ORANGE)):
            if s.get(key):
                y += 6
                d.text((PANEL_X, y), head.upper(), font=f(12, True), fill=GREY + (255,))
                y = self.block(d, PANEL_X, y + 16, s[key], f(16), color + (255,), PANEL_W, PANEL_BOTTOM)

        # banner (top of video)
        if s.get("banner"):
            lines = self.wrap(d, s["banner"], f(22, True), VID_W - 24)[:2]
            bh = 14 + 28 * len(lines)
            d.rectangle([VID_X, VID_Y, VID_X + VID_W, VID_Y + bh], fill=(20, 110, 50, 215))
            for i, line in enumerate(lines):
                d.text((VID_X + 12, VID_Y + 8 + 28 * i), line, font=f(22, True), fill=WHITE + (255,))

        # status + cues (bottom of video)
        items = [(s["status"], f(22, True), s["status_color"])]
        for text, expert in s.get("cues", []):
            items.append((("Coach: " if expert else "") + text, f(20), (255, 255, 255) if expert else (255, 215, 150)))
        rows = []
        for text, font, color in items:
            for line in self.wrap(d, text, font, VID_W - 24)[:2]:
                rows.append((line, font, color))
        strip_h = 16 + sum(r[1].size + 6 for r in rows) + (12 if s.get("matched_frac") is not None else 0)
        top = VID_Y + VID_H - strip_h
        d.rectangle([VID_X, top, VID_X + VID_W, VID_Y + VID_H], fill=(0, 0, 0, 175))
        yy = top + 8
        for line, font, color in rows:
            d.text((VID_X + 12, yy), line, font=font, fill=tuple(color) + (255,))
            yy += font.size + 6
        if s.get("matched_frac") is not None:
            d.rectangle([VID_X + 12, yy + 2, VID_X + 12 + int((VID_W - 24) * s["matched_frac"]), yy + 8],
                        fill=GREEN + (255,))

        # progress bar + footer
        prog = s["progress"]
        seg = VID_W / max(1, len(prog))
        colors = {"done": GREEN, "skipped": ORANGE, "current": YELLOW, "pending": (80, 80, 85)}
        for i, st in enumerate(prog):
            x0 = VID_X + i * seg
            d.rectangle([x0 + 1, VID_Y + VID_H + 12, x0 + seg - 1, VID_Y + VID_H + 24], fill=colors[st] + (255,))
        d.text((VID_X, VID_Y + VID_H + 32), s["footer"], font=f(14), fill=GREY + (255,))

        out = Image.alpha_composite(img, ov).convert("RGB")
        return cv2.cvtColor(np.array(out), cv2.COLOR_RGB2BGR)
