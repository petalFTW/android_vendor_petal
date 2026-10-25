#!/usr/bin/env python3
"""Build the small looping Proton4A loading animation for the runtime APK."""
import sys

from PIL import Image, ImageDraw


frames = []
for index in range(12):
    frame = Image.new("RGBA", (112, 112), (0, 0, 0, 0))
    draw = ImageDraw.Draw(frame)
    for petal in range(12):
        angle = petal * 30 - 90
        brightness = max(60, 255 - ((petal - index) % 12) * 21)
        draw.arc((12, 12, 100, 100), angle, angle + 21,
                 fill=(brightness, min(255, brightness + 12), 255, 255), width=9)
    frames.append(frame)

frames[0].save(sys.argv[1], format="GIF", save_all=True,
               append_images=frames[1:], duration=85, loop=0, disposal=2)
