"""Registry adapters: the real devices.json reader plus the fixture registry.

Approval is the *independent* Hermes PairingStore fact
(``is_approved("gadget", device_id)``), not the device record's ``pending_until``
marker; these tests pin that separation and the fail-closed handling of
malformed markers.
"""
import base64
import json
import secrets
import tempfile
import time
import unittest
from pathlib import Path

from server_tts.registry import (
    DeviceStoreRegistry,
    FixtureRegistry,
    GatewayStoreRegistry,
    PairingApprovalReader,
    default_pairing_dir,
)
from tests.server_tts.fixtures import device_id


class RegistryTestCase(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.pairing = tempfile.TemporaryDirectory()
        self.addCleanup(self.pairing.cleanup)
        self.path = Path(self.dir.name) / 'devices.json'
        self.approved = Path(self.pairing.name) / 'gadget-approved.json'
        self.key = secrets.token_bytes(32)
        self.device = device_id(self.key)

    def registry(self):
        return DeviceStoreRegistry(self.dir.name, approvals=PairingApprovalReader(self.pairing.name))

    def write(self, devices):
        self.path.write_text(json.dumps({'devices': devices, 'pairing': {}}), encoding='utf-8')

    def approve(self, device=None):
        self.approved.write_text(json.dumps({device or self.device: {'user_name': '', 'approved_at': time.time()}}),
                                 encoding='utf-8')

    def encoded(self):
        return base64.b64encode(self.key).decode()


class DeviceStoreRegistryTests(RegistryTestCase):
    def test_approved_device_returns_key_and_approval(self):
        self.write({self.device: {'key': self.encoded(), 'name': 'watch'}})
        self.approve()
        record = self.registry().lookup(self.device)
        self.assertIsNotNone(record)
        self.assertEqual(record.key, self.key)
        self.assertTrue(record.approved)

    def test_enrolled_and_confirmed_but_unapproved_is_not_approved(self):
        # Confirmed enrollment (no pending_until) is NOT approval: the pairing
        # store has no grant, so the device must not be approved.
        self.write({self.device: {'key': self.encoded()}})
        record = self.registry().lookup(self.device)
        self.assertIsNotNone(record)
        self.assertFalse(record.approved)

    def test_revoked_approval_with_enrollment_still_confirmed_denies(self):
        # Enrollment stays confirmed on disk, but the operator revokes the
        # pairing grant: the very next read must deny.
        self.write({self.device: {'key': self.encoded()}})
        self.approve()
        registry = self.registry()
        self.assertTrue(registry.lookup(self.device).approved)
        self.approved.write_text('{}', encoding='utf-8')  # hermes pairing revoke gadget <id>
        self.assertFalse(registry.lookup(self.device).approved)

    def test_pending_device_is_carried_but_not_approved(self):
        self.write({self.device: {'key': self.encoded(), 'pending_until': time.time() + 3600}})
        record = self.registry().lookup(self.device)
        self.assertIsNotNone(record)
        self.assertFalse(record.approved)

    def test_pending_marker_does_not_override_a_real_pairing_grant(self):
        # `hermes gadget pair` writes the pairing store, not DeviceStore.confirm,
        # so a still-pending record can legitimately be approved.
        self.write({self.device: {'key': self.encoded(), 'pending_until': time.time() + 3600}})
        self.approve()
        self.assertTrue(self.registry().lookup(self.device).approved)

    def test_expired_pending_and_forgotten_devices_are_absent(self):
        self.write({self.device: {'key': self.encoded(), 'pending_until': time.time() - 1}})
        self.assertIsNone(self.registry().lookup(self.device))
        self.write({})
        self.assertIsNone(self.registry().lookup(self.device))

    def test_malformed_pending_marker_denies_fail_closed(self):
        # A numeric-bool / string marker must never be coerced into "approved".
        for marker in (True, False, 0, 1, 'yes', 'soon'):
            with self.subTest(marker=marker):
                self.write({self.device: {'key': self.encoded(), 'pending_until': marker}})
                self.assertIsNone(self.registry().lookup(self.device))

    def test_corrupt_or_invalid_records_are_rejected(self):
        self.path.write_text('{not json', encoding='utf-8')
        self.assertIsNone(self.registry().lookup(self.device))
        self.write({self.device: {'key': 'not-base64!'}})
        self.assertIsNone(self.registry().lookup(self.device))
        self.write({self.device: {'key': base64.b64encode(b'short').decode()}})
        self.assertIsNone(self.registry().lookup(self.device))

    def test_revocation_takes_effect_without_restart(self):
        self.write({self.device: {'key': self.encoded()}})
        self.approve()
        registry = self.registry()
        self.assertTrue(registry.lookup(self.device).approved)
        self.write({})  # operator forgot/revoked the device enrollment
        self.assertIsNone(registry.lookup(self.device))

    def test_an_approval_reader_is_required(self):
        for approvals in (None, object()):
            with self.subTest(approvals=approvals):
                with self.assertRaises(ValueError):
                    DeviceStoreRegistry(self.dir.name, approvals=approvals)


class PairingApprovalReaderTests(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.path = Path(self.dir.name) / 'gadget-approved.json'
        self.reader = PairingApprovalReader(self.dir.name)

    def test_missing_malformed_or_non_object_deny(self):
        self.assertFalse(self.reader.is_approved('hg-x'))
        self.path.write_text('{not json', encoding='utf-8')
        self.assertFalse(self.reader.is_approved('hg-x'))
        self.path.write_text('[]', encoding='utf-8')
        self.assertFalse(self.reader.is_approved('hg-x'))
        for bad in (None, '', 123):
            self.assertFalse(self.reader.is_approved(bad))

    def test_only_a_present_owned_entry_is_approved(self):
        self.path.write_text(json.dumps({'hg-x': {'approved_at': 1}}), encoding='utf-8')
        self.assertTrue(self.reader.is_approved('hg-x'))
        self.assertFalse(self.reader.is_approved('hg-other'))


class GatewayStoreRegistryTests(RegistryTestCase):
    class StubStore:
        def __init__(self, devices, keys):
            self._devices, self._keys = devices, keys

        def devices(self):
            return self._devices

        def key_for(self, device_id):
            return self._keys.get(device_id)

    def build(self, pending):
        store = GatewayStoreRegistryTests.StubStore(
            {self.device: {'pending': pending}}, {self.device: self.key})
        return GatewayStoreRegistry(store, approvals=PairingApprovalReader(self.pairing.name))

    def test_pending_flag_parity_denies_malformed_numeric_bool(self):
        self.approve()
        self.assertTrue(self.build(False).lookup(self.device).approved)
        for bad in (True, 0, 1, 'false', 'pending'):
            with self.subTest(bad=bad):
                self.assertIsNone(self.build(bad).lookup(self.device))

    def test_requires_an_approval_reader(self):
        store = GatewayStoreRegistryTests.StubStore({}, {})
        with self.assertRaises(ValueError):
            GatewayStoreRegistry(store, approvals=None)


class DefaultPairingDirTests(unittest.TestCase):
    def test_prefers_consolidated_dir_but_keeps_populated_legacy(self):
        with tempfile.TemporaryDirectory() as root:
            (Path(root) / 'pairing').mkdir()  # empty legacy stub must not win
            (Path(root) / 'platforms' / 'pairing').mkdir(parents=True)
            self.assertEqual(default_pairing_dir(root), Path(root) / 'platforms' / 'pairing')
            (Path(root) / 'pairing' / 'gadget-approved.json').write_text('{}', encoding='utf-8')
            self.assertEqual(default_pairing_dir(root), Path(root) / 'pairing')


class FixtureRegistryTests(unittest.TestCase):
    def test_only_exact_32_byte_keys_with_explicit_approval(self):
        key = secrets.token_bytes(32)
        device = device_id(key)
        registry = FixtureRegistry({device: {'key': key, 'approved': True}})
        self.assertTrue(registry.lookup(device).approved)
        self.assertIsNone(registry.lookup(device_id(secrets.token_bytes(32))))
        registry._records[device] = {'key': b'short', 'approved': True}
        self.assertIsNone(registry.lookup(device))
        registry._records[device] = {'key': key, 'approved': False}
        self.assertFalse(registry.lookup(device).approved)
        registry._records[device] = {'key': key, 'approved': 1}  # non-bool denies
        self.assertFalse(registry.lookup(device).approved)


if __name__ == '__main__':
    unittest.main()
