import datetime as dt
import importlib.util
from pathlib import Path
import unittest

spec=importlib.util.spec_from_file_location('contract',Path(__file__).resolve().parents[1]/'android-release.py')
c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)

class ReleaseTests(unittest.TestCase):
    def release(self):
        return dict(schemaVersion=1,applicationId=c.APP_ID,versionName='0.1.1',versionCode=2,downloadUrl=c.ORIGIN+'/android/releases/LumaWork-Android-0.1.1-2.apk',sha256='a'*64,signingCertificateSHA256=c.TRUSTED_CERT,size=1234,publishedAt='2026-10-16T12:00:00Z',changelog=['Исправления'],minSdk=26)
    def test_valid(self): c.validate(self.release())
    def test_reject_bad_fields(self):
        for patch in [dict(size=0),dict(versionCode=True),dict(versionCode='2'),dict(downloadUrl='https://evil.test/file.apk'),dict(applicationId='other'),dict(sha256='bad'),dict(changelog=[]),dict(publishedAt='2099-01-01T00:00:00Z')]:
            with self.subTest(patch=patch), self.assertRaises((AssertionError, ValueError)):
                c.validate(dict(self.release(),**patch))
    def test_no_downgrade_or_equal_code(self):
        for code in [2,3]:
            old=dict(self.release(),versionCode=code,downloadUrl=f'{c.ORIGIN}/android/releases/LumaWork-Android-0.1.1-{code}.apk')
            with self.assertRaises(ValueError): c.check_upgrade(self.release(),old)
    def test_unknown_signing_key(self):
        with self.assertRaises(ValueError): c.check_upgrade(dict(self.release(),signingCertificateSHA256='b'*64),None)
    def test_monotonic_upgrade(self):
        old=dict(self.release(),versionCode=1,downloadUrl=c.ORIGIN+'/android/releases/LumaWork-Android-0.1.1-1.apk')
        c.check_upgrade(self.release(),old)

if __name__=='__main__': unittest.main()

pubspec=importlib.util.spec_from_file_location('publisher',Path(__file__).resolve().parents[1]/'publish-android.py')
pub=importlib.util.module_from_spec(pubspec);pubspec.loader.exec_module(pub)
class BadgingTests(unittest.TestCase):
    def test_build_tools_sdk_labels(self):
        for label in ['sdkVersion','minSdkVersion']:
            self.assertEqual(pub.parse_badging("package: name='ru.lumastack.lumawork' versionCode='1' versionName='0.1.0'\n"+label+":'26'\n"),('ru.lumastack.lumawork',1,'0.1.0',26))
    def test_debug_apk_rejected(self):
        with self.assertRaises(ValueError):
            pub.parse_badging("package: name='ru.lumastack.lumawork' versionCode='1' versionName='0.1.0'\nminSdkVersion:'26'\napplication-debuggable\n")

class VersionSelectionTests(unittest.TestCase):
    def test_local_installed_release_sets_next_version(self):
        self.assertEqual(pub.next_version('0.1.56', '0.1.57'), '0.1.58')
    def test_newer_public_release_sets_next_version(self):
        self.assertEqual(pub.next_version('0.1.60', '0.1.57'), '0.1.61')
    def test_next_release_can_start_new_minor_version(self):
        self.assertEqual(pub.next_version('0.1.60', '0.1.60', minimum='0.2.0'), '0.2.0')
    def test_published_target_continues_with_next_patch(self):
        self.assertEqual(pub.next_version('0.2.0', minimum='0.2.0'), '0.2.1')
    def test_next_release_can_start_new_major_version(self):
        self.assertEqual(pub.next_version('0.2.5', minimum='1.0.0'), '1.0.0')
    def test_first_release_uses_configured_minimum(self):
        self.assertEqual(pub.next_version(minimum='0.2.0'), '0.2.0')

class SourceGuardTests(unittest.TestCase):
    def test_publisher_rejects_checkout_without_leaderboard_before_network(self):
        import tempfile
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as directory, patch.object(pub, 'PROJECT', Path(directory)):
            with patch.object(pub, 'get_json', side_effect=AssertionError('network contacted')):
                with self.assertRaisesRegex(ValueError, 'Исходники рейтинга'):
                    pub.main()
