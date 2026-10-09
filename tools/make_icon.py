"""从用户提供的 phototidy.png 抠出图形层，生成 Android 自适应图标资源。"""
import math
from PIL import Image, ImageDraw, ImageFilter, ImageChops, ImageFont

SRC = r'D:\GithubWorkplace\PhotoTidy\design\icon-source.png'
OUT = r'D:\GithubWorkplace\PhotoTidy'

im = Image.open(SRC).convert('RGB')
W, H = im.size
print('src', W, H)

# ---------- 1. 定位圆角绿方块（非白区域包围盒）----------
r, g, b = im.split()
white = ImageChops.multiply(
    ImageChops.multiply(r.point(lambda v: 255 if v >= 246 else 0),
                        g.point(lambda v: 255 if v >= 246 else 0)),
    b.point(lambda v: 255 if v >= 246 else 0))
inv_white = ImageChops.invert(white)
box = inv_white.getbbox()
print('green tile bbox', box)
# 内缩 6px：切掉方块自身边缘 1~3px 的抗锯齿混合像素（艺术图离边界很远，绝对安全）
inset = 6
box = (box[0] + inset, box[1] + inset, box[2] - inset, box[3] - inset)
tile = im.crop(box)
TW, TH = tile.size
print('tile size', TW, TH)

# ---------- 2. 背景 = 与边框相连的「绿」+ 四角的白 ----------
tr, tg, tb = tile.split()
greenish = ImageChops.multiply(
    ImageChops.multiply(
        ImageChops.subtract(tg, tr).point(lambda v: 255 if v > 25 else 0),
        ImageChops.subtract(tg, tb).point(lambda v: 255 if v > 8 else 0)),
    tg.point(lambda v: 255 if 100 <= v <= 205 else 0))
tw = ImageChops.multiply(
    ImageChops.multiply(tr.point(lambda v: 255 if v >= 246 else 0),
                        tg.point(lambda v: 255 if v >= 246 else 0)),
    tb.point(lambda v: 255 if v >= 246 else 0))

# 从 4 个角把「白」填掉（圆角外的白边）
corner_seeds = [(0, 0), (TW - 1, 0), (0, TH - 1), (TW - 1, TH - 1)]
fill_w = tw.copy()
for s in corner_seeds:
    if fill_w.getpixel(s) == 255:
        ImageDraw.floodfill(fill_w, s, 128, thresh=0)

# 从 4 个已验证的绿色点把绿背景填掉
green_seeds = [(int(0.055 * TW), int(0.26 * TH)), (int(0.945 * TW), int(0.26 * TH)),
               (int(0.17 * TW), int(0.96 * TH)), (int(0.83 * TW), int(0.96 * TH))]
fill_g = greenish.copy()
for s in green_seeds:
    print('seed', s, tile.getpixel(s), 'maskval', fill_g.getpixel(s))
    if fill_g.getpixel(s) == 255:
        ImageDraw.floodfill(fill_g, s, 128, thresh=0)

bg = ImageChops.lighter(fill_g.point(lambda v: 255 if v == 128 else 0),
                        fill_w.point(lambda v: 255 if v == 128 else 0))
alpha = ImageChops.invert(bg)

# ---------- 3. 去毛刺：中值滤波杀掉 1-2px 抗锯齿残环 ----------
alpha_clean = alpha.filter(ImageFilter.MedianFilter(7))
alpha_clean.paste(0, (0, 0, TW, 4))                      # 再明确清掉最外圈
alpha_clean.paste(0, (0, TH - 4, TW, TH))
alpha_clean.paste(0, (0, 0, 4, TH))
alpha_clean.paste(0, (TW - 4, 0, TW, TH))
# ---------- 3a. 连通域过滤：只保留实体图形（照片堆 + 星芒），丢掉 JPEG 噪点/边缘残环 ----------
sc = 4
small = alpha_clean.resize((TW // sc, TH // sc), Image.NEAREST).point(lambda v: 255 if v > 128 else 0)
work = small.copy()
kept = []
lbl = 0
while True:
    bb = work.point(lambda v: 255 if v == 255 else 0).getbbox()
    if not bb:
        break
    seed = None
    for yy in range(bb[1], bb[3]):
        for xx in range(bb[0], bb[2]):
            if work.getpixel((xx, yy)) == 255:
                seed = (xx, yy)
                break
        if seed:
            break
    lbl += 1
    ImageDraw.floodfill(work, seed, 100 + lbl)
    sub = work.point(lambda v: 255 if v == 100 + lbl else 0)
    area = sum(1 for p in sub.get_flattened_data() if p) * sc * sc
    box_l = sub.getbbox()
    print('  comp #%d area=%d bbox=%s' % (lbl, area, box_l))
    if area >= 1200:
        kept.append(box_l)

# 并集包围盒 → 之外一律清零
kx0 = min(b[0] for b in kept) * sc - sc
ky0 = min(b[1] for b in kept) * sc - sc
kx1 = min(TW, max(b[2] for b in kept) * sc + sc)
ky1 = min(TH, max(b[3] for b in kept) * sc + sc)
alpha_clean.paste(0, (0, 0, TW, ky0))
alpha_clean.paste(0, (0, ky1, TW, TH))
alpha_clean.paste(0, (0, 0, kx0, TH))
alpha_clean.paste(0, (kx1, 0, TW, TH))
print('  kept boxes:', kept, '-> keep region', kx0, ky0, kx1, ky1)

bbox = alpha_clean.getbbox()
print('art bbox in tile', bbox)
pad = 2
bbox = (max(0, bbox[0] - pad), max(0, bbox[1] - pad),
        min(TW, bbox[2] + pad), min(TH, bbox[3] + pad))
art_rgb = tile.crop(bbox)
art_a = alpha_clean.crop(bbox)
print('art size', art_rgb.size, 'coverage %.3f' % (sum(1 for v in art_a.getdata() if v > 128) / (art_a.size[0] * art_a.size[1])))

# 背景主色（取四边中部的中位数）
samples = []
for x in range(int(0.06 * TW), int(0.94 * TW), 17):
    for y in (int(0.02 * TH), int(0.98 * TH)):
        samples.append(tile.getpixel((x, y)))
for y in range(int(0.06 * TH), int(0.94 * TH), 17):
    for x in (int(0.02 * TW), int(0.98 * TW)):
        samples.append(tile.getpixel((x, y)))
samples = [c for c in samples if c[1] - c[0] > 20 and c[1] - c[2] > 5]
samples.sort(key=lambda c: c[1])
bgc = samples[len(samples) // 2]
print('bg color', bgc, '#%02X%02X%02X' % bgc, 'n=%d' % len(samples))


def fit_art(rgb, a, target_max, canvas, density_scale):
    """把艺术图等比缩放到 max 边长 target_max 像素，居中贴到 canvas 画布上。"""
    w, h = rgb.size
    k = target_max / max(w, h)
    nw, nh = max(1, round(w * k)), max(1, round(h * k))
    # 预乘 alpha 缩放，避免透明区（绿色）渗色到边缘
    ar = a.convert('L').resize((nw, nh), Image.LANCZOS)
    pm = Image.merge('RGB', [ImageChops.multiply(c, a.convert('L'))
                             for c in rgb.split()]).resize((nw, nh), Image.LANCZOS)
    inv = ar.point([(255 * 255 // v) if v > 8 else 0 for v in range(256)])
    rgb_s = Image.merge('RGB', [ImageChops.multiply(c, inv) for c in pm.split()])
    layer = Image.merge('RGBA', (*rgb_s.split(), ar))
    out = Image.new('RGBA', (canvas, canvas), (0, 0, 0, 0))
    out.paste(layer, ((canvas - nw) // 2, (canvas - nh) // 2), layer)
    return out


# ---------- 4. 前台层：108dp 画布 @4x，艺术图占 56dp ----------
fg = fit_art(art_rgb, art_a, round(56 * 4), 108 * 4, 4)
fg.save(OUT + r'\app\src\main\res\drawable-xxxhdpi\ic_launcher_foreground.png', optimize=True)

# ---------- 5. 单色层（主题图标）：取剪影，纯白 ----------
mono = Image.new('RGBA', fg.size, (0, 0, 0, 0))
white_src = Image.new('RGB', fg.size, (255, 255, 255))
mono.paste(white_src, (0, 0), fg.split()[3])
mono.save(OUT + r'\app\src\main\res\drawable-xxxhdpi\ic_launcher_monochrome.png', optimize=True)

# ---------- 6. 启动页图标：288dp 画布 @4x，艺术图外接圆 ≤192dp ----------
aw, ah = art_rgb.size
k = (192 * 4) / math.sqrt(aw * aw + ah * ah)
splash = fit_art(art_rgb, art_a, round(max(aw, ah) * k), 288 * 4, 4)
splash.save(OUT + r'\app\src\main\res\drawable-xxxhdpi\ic_splash_logo.png', optimize=True)
print('splash art px', round(aw * k), round(ah * k))

# ---------- 7. 预览图（带中文标签），人工核对 ----------
S = 240
GAP = 10
LABELS = [
    '① 抠图核对：洋红底',
    '② 主题图标（系统着色）',
    '③ 自适应图标·方圆遮罩',
    '④ 自适应图标·圆形遮罩',
    '⑤ 启动页（192dp 圆内）',
]


def load_font(size):
    for path in (r'C:\Windows\Fonts\msyh.ttc', r'C:\Windows\Fonts\simhei.ttf'):
        try:
            return ImageFont.truetype(path, size)
        except OSError:
            continue
    return ImageFont.load_default()


def fit_font(texts, max_w, size=15):
    while size > 8:
        f = load_font(size)
        if max(f.getlength(t) for t in texts) <= max_w:
            return f
        size -= 1
    return load_font(size)


panels = []

# ① 抠图质量：洋红底唯一的作用是把残留的绿边/暗边/脏点照出来（图形本身没有洋红）。
#    应用里不存在这个颜色，这张图只是核对物。
mg = Image.new('RGB', (S, S), (255, 0, 255))
art_s = Image.merge('RGBA', (*art_rgb.split(), art_a)).resize(
    (S - 90, round((S - 90) * ah / aw)), Image.LANCZOS)
mg.paste(art_s, ((S - art_s.size[0]) // 2, (S - art_s.size[1]) // 2), art_s)
panels.append(mg)

# ② 主题图标着色效果（把单色层当蒙版，填系统给的强调色）
tint = Image.new('RGB', (S, S), (26, 32, 44))
tint.paste(Image.new('RGB', (S, S), (168, 199, 250)), (0, 0),
           mono.resize((S, S), Image.LANCZOS).split()[3])
panels.append(tint)

# ③④ 自适应图标：按 Android 规范渲染（108dp 层，可见区=中央 72dp）
icon = Image.new('RGB', (108 * 2, 108 * 2), bgc)
fg_s = fg.resize((108 * 2, 108 * 2), Image.LANCZOS)
icon.paste(fg_s, (0, 0), fg_s)


def render_adaptive(shape):
    page = Image.new('RGB', (S, S), (226, 228, 232))
    layer = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    px_per_dp = (S - 24) / 108.0
    ic = icon.resize((S - 24, S - 24), Image.LANCZOS)
    layer.paste(ic, (12, 12))
    mk = Image.new('L', (S, S), 0)
    dm = ImageDraw.Draw(mk)
    # 可见区 = 108dp 画布中央 72dp 的方/圆（每边留 18dp 给遮罩/视差）
    L = 12 + round(18 * px_per_dp)
    R = 12 + round(90 * px_per_dp) - 1
    if shape == 'circle':
        dm.ellipse((L, L, R, R), fill=255)
    else:
        dm.rounded_rectangle((L, L, R, R), radius=round(20 * px_per_dp), fill=255)
    layer.putalpha(ImageChops.multiply(layer.split()[3], mk))
    out = page.convert('RGBA')
    out.alpha_composite(layer)
    return out.convert('RGB')


panels.append(render_adaptive('squircle'))
panels.append(render_adaptive('circle'))

# ⑤ 启动页：288dp 画布，中央 192dp 圆内可见
sp = Image.new('RGB', (S, S), (14, 21, 19))
sp.paste(splash.resize((S, S), Image.LANCZOS), (0, 0), splash.resize((S, S), Image.LANCZOS))
cd = round(192 / 288 * S)
circle = Image.new('L', (S, S), 0)
ImageDraw.Draw(circle).ellipse(((S - cd) // 2, (S - cd) // 2,
                                (S + cd) // 2 - 1, (S + cd) // 2 - 1), fill=255)
sp = sp.convert('RGBA')
sp.putalpha(circle)
base = Image.new('RGB', (S, S), (14, 21, 19)).convert('RGBA')
base.alpha_composite(sp)
panels.append(base.convert('RGB'))

# 拼版 + 居中写标签
TOP, LABEL_H, BOTTOM = 18, 26, 14
font = fit_font(LABELS, S - 6)
preview = Image.new('RGB', (GAP + len(panels) * (S + GAP), TOP + S + LABEL_H + BOTTOM),
                    (245, 245, 247))
pd = ImageDraw.Draw(preview)
for i, (p, label) in enumerate(zip(panels, LABELS)):
    x = GAP + i * (S + GAP)
    preview.paste(p, (x, TOP))
    pd.rectangle((x, TOP, x + S - 1, TOP + S - 1), outline=(214, 216, 220))
    pd.text((x + S // 2, TOP + S + LABEL_H // 2), label, font=font,
            fill=(58, 58, 64), anchor='mm')
preview.save(OUT + r'\design\icon-preview.png')
print('done')
