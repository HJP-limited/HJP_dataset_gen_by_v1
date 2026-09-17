import json
import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
E37_SHA = "7e028767bf95cefc7438885ac572cb3db4aba94f485b41393d7f0702f07f4961"


class ReleaseWiringTest(unittest.TestCase):
    def test_production_tool_catalog_is_the_six_declared_tools(self):
        source = (ROOT / "app/src/main/java/com/example/hjp/AppContainer.kt").read_text(encoding="utf-8")
        block = source.split("private val plugins = listOf(", 1)[1].split("private val registry", 1)[0]
        plugins = re.findall(
            r"\b(SearchContactsPlugin|GetContactPlugin|UpdateBusinessCardPlugin|"
            r"CreateCalendarEventPlugin|OpenComposePlugin|GetCurrentDateTimePlugin)\s*\(",
            block,
        )
        self.assertEqual(plugins, [
            "SearchContactsPlugin",
            "GetContactPlugin",
            "UpdateBusinessCardPlugin",
            "CreateCalendarEventPlugin",
            "OpenComposePlugin",
            "GetCurrentDateTimePlugin",
        ])

    def test_android_evaluation_packages_e37_with_matching_provenance(self):
        build = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
        runner = (ROOT / "app/src/androidTest/java/com/example/hjp/MultiturnToolCallDeviceEvalInstrumentedTest.kt").read_text(
            encoding="utf-8"
        )
        self.assertIn('"agent_eval/eval_set_v1_e37.json"', build)
        self.assertIn(E37_SHA, build)
        self.assertNotIn('"agent_eval/eval_set_v1_e35.json"', build)
        self.assertIn('const val EVAL_ASSET = "agent_eval/eval_set_v1_e37.json"', runner)
        self.assertIn(f'const val EVAL_SHA256 = "{E37_SHA}"', runner)
        self.assertIn('put("gold_contract", "E-3.7")', runner)

    def test_external_asset_manifest_is_secret_free_metadata(self):
        manifest = json.loads((ROOT / "external_assets/MANIFEST.json").read_text(encoding="utf-8"))
        self.assertEqual(manifest["schema"], "hjp_external_assets/v1")
        self.assertEqual(
            {item["logical_name"] for item in manifest["assets"]},
            {"gemma_4_e2b_litertlm", "embeddinggemma_300m", "sentencepiece"},
        )
        for item in manifest["assets"]:
            self.assertRegex(item["sha256"], r"^[0-9a-f]{64}$")
            self.assertFalse(Path(item["app_relative_path"]).is_absolute())
            self.assertNotIn("token", item)
            self.assertNotIn("credential", item)


if __name__ == "__main__":
    unittest.main()
