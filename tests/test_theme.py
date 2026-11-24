from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

MAIN = Path(__file__).resolve().parents[1] / "app/src/main"


def luminance(color):
    values = [int(color[i:i + 2], 16) / 255 for i in (1, 3, 5)]
    linear = [v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4 for v in values]
    return sum(v * weight for v, weight in zip(linear, (0.2126, 0.7152, 0.0722)))


class MatrixThemeChecks(unittest.TestCase):
    def test_toolbar_has_only_accessible_power_icon(self):
        android = "{http://schemas.android.com/apk/res/android}"
        root = ET.parse(MAIN / "res/layout/activity_main.xml").getroot()
        buttons = root.find("LinearLayout").findall("ImageButton")
        self.assertEqual([b.get(android + "id") for b in buttons],
                         ["@+id/bTurnOff"])
        for button in buttons:
            self.assertTrue(button.get(android + "contentDescription"))
            self.assertTrue(button.get(android + "tooltipText"))

    def test_launcher_uses_surprised_face(self):
        android = "{http://schemas.android.com/apk/res/android}"
        app = ET.parse(MAIN / "AndroidManifest.xml").getroot().find("application")
        self.assertEqual(app.get(android + "icon"), "@mipmap/ic_mobile_launcher")
        self.assertEqual(app.get(android + "roundIcon"), "@mipmap/ic_mobile_launcher")
        icon = ET.parse(MAIN / "res/mipmap-anydpi-v26/ic_mobile_launcher.xml").getroot()
        self.assertEqual(icon.find("foreground").get(android + "drawable"), "@drawable/ic_surprised_face")

    def test_app_name_and_blink_target(self):
        android = "{http://schemas.android.com/apk/res/android}"
        strings = {s.attrib["name"]: s.text for s in ET.parse(MAIN / "res/values/strings.xml").getroot()}
        self.assertEqual(strings["app_name"], "OpenCode Mobile")
        vector = ET.parse(MAIN / "res/drawable/ic_launcher_foreground.xml")
        self.assertTrue(any(g.get(android + "name") == "eyes" for g in vector.iter("group")))
        animation = ET.parse(MAIN / "res/drawable/smiley_blink.xml").getroot()
        self.assertEqual(animation.find("target").get(android + "name"), "eyes")
        animator = ET.parse(MAIN / "res/animator/smiley_blink.xml").getroot()
        self.assertEqual(animator.get(android + "repeatCount"), "infinite")
        frames = list(animator.iter("keyframe"))
        self.assertEqual(frames[0].get(android + "value"), frames[-1].get(android + "value"))

    def test_enabled_text_has_readable_contrast(self):
        colors = {c.attrib["name"]: c.text for c in ET.parse(MAIN / "res/values/colors.xml").getroot()}
        for foreground, background in (
            ("matrix_text", "matrix_surface"),
            ("matrix_muted", "matrix_surface"),
            ("matrix_green", "matrix_surface"),
            ("matrix_black", "matrix_green"),
        ):
            a, b = sorted((luminance(colors[foreground]), luminance(colors[background])))
            self.assertGreaterEqual((b + 0.05) / (a + 0.05), 4.5, foreground)

    def test_chat_keeps_semantic_error_and_diff_colors(self):
        css = (MAIN / "assets/matrix.css").read_text()
        self.assertIn("--v2-background-bg-deep: #050a07 !important", css)
        self.assertIn("--v2-text-text-accent: #52ff95 !important", css)
        self.assertNotIn("--surface-critical", css)
        self.assertNotIn("--surface-diff-delete", css)
        self.assertNotIn("animation:", css)


if __name__ == "__main__":
    unittest.main()
