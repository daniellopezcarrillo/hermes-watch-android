"""Origin derivation: private HTTPS audio origin from the saved WSS hostname."""
import unittest

from server_tts.origin import AUDIO_HTTPS_PORT, audio_origin


class OriginTests(unittest.TestCase):
    def test_derives_https_origin_on_8445_from_wss_hostname(self):
        self.assertEqual(AUDIO_HTTPS_PORT, 8445)
        self.assertEqual(audio_origin('wss://watch-node.tailnet.ts.net:8444'),
                         'https://watch-node.tailnet.ts.net:8445')
        self.assertEqual(audio_origin('wss://10.0.0.7:8444'), 'https://10.0.0.7:8445')
        self.assertEqual(audio_origin('wss://Watch-Node.Tailnet.TS.NET'),
                         'https://watch-node.tailnet.ts.net:8445')
        self.assertEqual(audio_origin('wss://[fd7a:115c:a1e0::1]:8444'),
                         'https://[fd7a:115c:a1e0::1]:8445')

    def test_rejects_non_wss_and_unsafe_parts(self):
        for bad in ('https://host.ts.net', 'ws://host.ts.net', 'wss://u:p@host.ts.net',
                    'wss://host.ts.net/x?cap=1', 'wss://host.ts.net/x#frag', None, 123):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                audio_origin(bad)


if __name__ == '__main__':
    unittest.main()
