"""Transport-free policy: challenge, MAC verification, one-use bounded nonces."""
import base64
import secrets
import unittest

from server_tts import core
from server_tts.core import TTSDelivery
from server_tts.registry import FixtureRegistry
from tests.server_tts.fixtures import MP3_FIXTURE, device_id, mac


class DeliveryTests(unittest.TestCase):
    def setUp(self):
        self.now = 1000.0
        self.key = secrets.token_bytes(32)
        self.device = device_id(self.key)
        self.registry = FixtureRegistry({self.device: {'key': self.key, 'approved': True}})
        self.delivery = TTSDelivery(self.registry.lookup, audio=MP3_FIXTURE, clock=lambda: self.now)

    def challenge(self, device=None):
        return self.delivery.challenge(self.device if device is None else device)

    def test_mac_message_is_the_frozen_domain_separated_string(self):
        self.assertEqual(core.mac_message(self.device, 'NONCE'),
                         ('hermes-watch/tts-test/v1|%s|NONCE' % self.device).encode('utf-8'))

    def test_challenge_returns_base64_nonce_and_ttl_30(self):
        result = self.challenge()
        self.assertEqual(result.status, 200)
        nonce = result.body['nonce']
        self.assertEqual(result.body['ttl'], 30)
        self.assertEqual(len(base64.b64decode(nonce, validate=True)), 16)

    def test_authenticated_audio_matches_fixed_bytes_and_verifies_every_request(self):
        ch = self.challenge().body
        result = self.delivery.audio(self.device, ch['nonce'], mac(self.key, self.device, ch['nonce']))
        self.assertEqual(result.status, 200)
        self.assertEqual(result.audio, MP3_FIXTURE)
        # A second, independent challenge/fetch succeeds again (two frames).
        ch2 = self.challenge().body
        second = self.delivery.audio(self.device, ch2['nonce'], mac(self.key, self.device, ch2['nonce']))
        self.assertEqual(second.status, 200)
        self.assertEqual(second.audio, result.audio)

    def test_wrong_mac_consumes_nonce_no_retry_no_replay(self):
        ch = self.challenge().body
        bad = base64.b64encode(hmac_bytes(self.key, 'wrong')).decode()
        self.assertEqual(self.delivery.audio(self.device, ch['nonce'], bad).status, 401)
        # Correct MAC after a failed attempt must NOT work: the nonce is spent.
        good = mac(self.key, self.device, ch['nonce'])
        self.assertEqual(self.delivery.audio(self.device, ch['nonce'], good).status, 401)

    def test_correct_mac_cannot_be_replayed(self):
        ch = self.challenge().body
        good = mac(self.key, self.device, ch['nonce'])
        self.assertEqual(self.delivery.audio(self.device, ch['nonce'], good).status, 200)
        self.assertEqual(self.delivery.audio(self.device, ch['nonce'], good).status, 401)

    def test_expired_nonce_is_unauthorized(self):
        ch = self.challenge().body
        good = mac(self.key, self.device, ch['nonce'])
        self.now += 31
        self.assertEqual(self.delivery.audio(self.device, ch['nonce'], good).status, 401)

    def test_unauthorized_and_pending_devices_denied(self):
        self.assertEqual(self.challenge('hw-unknown').status, 401)
        self.registry._records[self.device]['approved'] = False
        self.assertEqual(self.challenge().status, 401)

    def test_per_device_and_total_nonce_caps_bound_memory(self):
        self.assertEqual(self.challenge().status, 200)
        self.assertEqual(self.challenge().status, 200)
        self.assertEqual(self.challenge().status, 429)  # max 2 per device
        keys = [secrets.token_bytes(32) for _ in range(40)]
        for key in keys:
            self.registry._records[device_id(key)] = {'key': key, 'approved': True}
        statuses = [self.delivery.challenge(device_id(key)).status for key in keys]
        self.assertEqual(len(self.delivery.nonces), core.MAX_NONCES)
        self.assertIn(429, statuses)

    def test_expired_nonces_are_pruned_before_the_cap(self):
        self.assertEqual(self.challenge().status, 200)
        self.assertEqual(self.challenge().status, 200)
        self.now += 31
        self.assertEqual(self.challenge().status, 200)

    def test_malformed_inputs_fail_closed(self):
        self.assertEqual(self.delivery.audio(self.device, 123, 'x').status, 401)
        self.assertEqual(self.challenge('').status, 401)
        self.assertEqual(self.challenge('x' * 65).status, 401)

    def test_registry_exception_is_denied_without_leaking(self):
        def explode(_):
            raise RuntimeError('SECRET_KEY_MATERIAL')
        delivery = TTSDelivery(explode, audio=MP3_FIXTURE, clock=lambda: self.now)
        result = delivery.challenge(self.device)
        self.assertEqual(result.status, 401)
        self.assertNotIn('SECRET_KEY_MATERIAL', repr(result))


def hmac_bytes(key, text):
    import hmac
    return hmac.digest(key, text.encode(), 'sha256')


if __name__ == '__main__':
    unittest.main()
