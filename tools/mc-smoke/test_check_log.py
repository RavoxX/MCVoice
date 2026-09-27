import unittest

from check_log import check_log


INIT = "[27Sep2026 08:50:11.000] [Worker-Main-8/INFO] [MCVoice/]: [VOICE] MCVoice 0.1.4 initialised (Minecraft 1.16.5, forge, protocol 1.1)\n"
FRAME = "\tat dev.mcvoice.platform.mc.PlatformInfo.simpleVoiceChatModPresent(PlatformInfo.java:34)\n"


class LogChecks(unittest.TestCase):
    def check(self, text):
        return check_log(text, "1.16.5", "forge")

    def test_caught_optional_mod_probe_at_trace_is_not_a_runtime_error(self):
        diagnostic = "[27Sep2026 08:50:04.000] [modloading-worker-2/TRACE] [cpw.mods.modlauncher.TransformingClassLoader/CLASSLOADING]: Parent classloader error on de.maxhenkel.voicechat.Voicechat\njava.lang.ClassNotFoundException: null\n"
        self.assertEqual("", self.check(diagnostic + FRAME + INIT))

    def test_error_stack_remains_fatal_after_trace_record(self):
        log = INIT + "[08:51:00] [Render thread/TRACE]: class lookup\n"
        log += "[08:51:00] [Render thread/ERROR]: Unhandled exception\n" + FRAME
        self.assertIn("stack trace through MCVoice", self.check(log))

    def test_modular_forge_stack_frame_is_detected(self):
        log = INIT + "[08:51:00] [Render thread/WARN]: Render exception\n"
        log += "\tat TRANSFORMER/mcvoice@0.1.4/dev.mcvoice.platform.mc.McNameTags.render(McNameTags.java:12)\n"
        self.assertIn("stack trace through MCVoice", self.check(log))

    def test_error_without_stack_is_detected(self):
        self.assertIn("MCVoice error", self.check(INIT + "[08:51:00] [Render thread/ERROR] [MCVoice/]: failed\n"))

    def test_hud_failure_remains_fatal(self):
        self.assertEqual("HUD render failed", self.check(INIT + "[08:51:00] [Render thread/WARN]: HUD render failed: exception\n"))

    def test_initialisation_must_match_version_and_loader(self):
        self.assertTrue(self.check(""))
        self.assertTrue(self.check(INIT.replace("1.16.5", "26.1.2")))
        self.assertTrue(self.check(INIT.replace("forge", "fabric")))


if __name__ == "__main__":
    unittest.main()
