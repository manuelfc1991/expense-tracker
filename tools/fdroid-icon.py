"""Flattens the adaptive launcher icon into the single PNG an F-Droid client needs.

The app ships `mipmap-anydpi-v26/ic_launcher.xml` and nothing else, so there is no
bitmap in the APK for `fdroid update` to extract — it found none and drew a placeholder.
An adaptive icon is two layers on a 108dp canvas of which a launcher shows the middle
72dp; this reproduces that by hand: foreground over the background colour, then the
centre 72/108 cropped out. Skip the crop and the mark comes out two-thirds the size
everything else on the F-Droid screen is drawn at.
"""

from PIL import Image

SRC = "app/src/main/res/drawable-xxxhdpi/ic_launcher_foreground.png"
# ic_launcher_background.xml is a single white path. Read it rather than assumed —
# the file's own comment records that it has been blue and near-black before now.
BACKGROUND = (255, 255, 255, 255)
OUT = 512

fg = Image.open(SRC).convert("RGBA")
canvas = Image.new("RGBA", fg.size, BACKGROUND)
canvas.alpha_composite(fg)

inset = round(fg.width * (108 - 72) / 2 / 108)
cropped = canvas.crop((inset, inset, fg.width - inset, fg.height - inset))
icon = cropped.resize((OUT, OUT), Image.LANCZOS)

# Two destinations, two different things. The first is the app's icon in the client's
# app list; the second is the repo's own icon, shown where the repository is listed.
# They are the same picture here only because this repository serves one app.
#
# `fdroid/icon.png`, not `fdroid/repo/icons/icon.png`, for the second one. The latter is
# where it ends up, but `fdroid update` empties that directory and copies the icon in
# from the path `repo_icon` names — relative to fdroid/, so the bare filename. Writing
# straight to the destination looks like it works and is silently undone on the next run.
for path in (
    "fdroid/metadata/com.manuel.ours/en-US/icon.png",
    "fdroid/icon.png",
):
    icon.save(path)
    print("wrote", path)
