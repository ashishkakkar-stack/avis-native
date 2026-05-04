from PIL import Image
import os

img_path = r"C:\Users\NC23608_Ashish\.gemini\antigravity\brain\275aeca5-bc5c-48a8-b51d-f851efa0c14b\avis_feature_graphic_highres_1775080066065.png"
if os.path.exists(img_path):
    with Image.open(img_path) as img:
        print(f"Dimensions: {img.width}x{img.height}")
else:
    print("Image not found")
