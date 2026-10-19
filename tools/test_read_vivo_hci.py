"""Synthetic, non-device btsnoop H4 fixtures for read-vivo-hci.py.

Run with: python -m unittest discover -s tools -p test_read_vivo_hci.py -v
Fixtures stay in this workspace because the host's system Temp may be restricted.
"""
import datetime as dt
import importlib.util
import json
import shutil
import struct
import tempfile
import unittest
from pathlib import Path


WORKSPACE = Path(__file__).resolve().parents[1]
FIXTURE_ROOT = WORKSPACE / "diagnostics" / "temp"
SPEC = importlib.util.spec_from_file_location(
    "read_vivo_hci", WORKSPACE / "tools" / "read-vivo-hci.py"
)
HCI = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HCI)

CAPTURE_UTC = dt.datetime(2026, 10, 19, 0, 0, tzinfo=dt.timezone.utc)
CAPTURE_TS = HCI.EPOCH + int(CAPTURE_UTC.timestamp()) * 1_000_000
HEADER = b"btsnoop\0" + struct.pack(">II", 1, 1002)
SYNTHETIC_PEER = bytes.fromhex("0102030405c0")


def le16(value):
    return struct.pack("<H", value)


def command(opcode, parameters):
    return b"\x01" + le16(opcode) + bytes([len(parameters)]) + parameters


def event(code, parameters):
    return bytes([4, code, len(parameters)]) + parameters


def le_connection(handle, role, subevent=1, address=SYNTHETIC_PEER):
    # Local role is a property of the controller event, not the snoop RX bit.
    parameters = bytes([0]) + le16(handle) + bytes([role, 1]) + address
    if subevent in (0x0A, 0x29):
        parameters += b"\0" * 12  # Local RPA and peer RPA.
    parameters += le16(24) + le16(0) + le16(200) + b"\0"
    if subevent == 0x29:
        parameters += bytes([0, 0xFF])  # Advertising handle, synchronization handle.
        parameters += b"\xff"
    return event(0x3E, bytes([subevent]) + parameters)


def acl_fragment(handle, incoming, payload, boundary=2):
    frame = b"\x02" + le16(handle | boundary << 12) + le16(len(payload)) + payload
    return frame, 1 if incoming else 0


def att(handle, incoming, payload):
    return acl_fragment(handle, incoming, le16(len(payload)) + le16(4) + payload)


def sensor_dialog(handle, remote_client):
    """Discover a synthetic CPS server, subscribe, and emit the public 93 W test."""
    request_rx = remote_client
    return [
        att(handle, request_rx, b"\x10\x01\x00\xff\xff\x00\x28"),
        att(handle, not request_rx, b"\x11\x06\x01\x00\x04\x00\x18\x18"),
        att(handle, request_rx, b"\x08\x01\x00\x04\x00\x03\x28"),
        # Declaration handle 2, Notify property 0x10, value handle 3, UUID 2A63.
        att(handle, not request_rx, b"\x09\x07\x02\x00\x10\x03\x00\x63\x2a"),
        att(handle, request_rx, b"\x04\x04\x00\x04\x00"),
        att(handle, not request_rx, b"\x05\x01\x04\x00\x02\x29"),
        att(handle, request_rx, b"\x12\x04\x00\x01\x00"),
        att(handle, not request_rx, b"\x13"),
        att(handle, not request_rx, b"\x1b\x03\x00\x00\x00\x5d\x00"),
    ]


class ReadVivoHciTests(unittest.TestCase):
    def setUp(self):
        FIXTURE_ROOT.mkdir(parents=True, exist_ok=True)
        self.fixture_dir = Path(tempfile.mkdtemp(prefix="hci-unittest-", dir=FIXTURE_ROOT))
        self.capture = self.fixture_dir / "synthetic.cfa"

    def tearDown(self):
        # Validate the resolved recursive-delete target before touching any files.
        resolved = self.fixture_dir.resolve()
        resolved.relative_to(WORKSPACE.resolve())
        self.assertEqual(resolved.parent, FIXTURE_ROOT.resolve())
        self.assertTrue(resolved.name.startswith("hci-unittest-"))
        shutil.rmtree(resolved)

    def write_capture(self, frames, trailing=b"", timestamp=CAPTURE_TS):
        data = bytearray(HEADER)
        for index, frame_record in enumerate(frames):
            frame, flags = frame_record[:2]
            original_length = frame_record[2] if len(frame_record) > 2 else len(frame)
            data += struct.pack(">IIIIQ", original_length, len(frame), flags, 0, timestamp + index * 1_000)
            data += frame
        self.capture.write_bytes(data + trailing)
        return self.capture

    def read(self, frames, **options):
        return HCI.Reader(**options).read(self.write_capture(frames))

    def test_extended_commands_decode_legacy_advertising_and_fragmented_cps_ad(self):
        parameters = bytearray(25)
        parameters[0] = 2
        parameters[1:3] = le16(0x0013)  # Legacy, connectable, scannable.
        parameters[3:6] = (160).to_bytes(3, "little")
        parameters[6:9] = (160).to_bytes(3, "little")
        parameters[9] = 7
        parameters[10] = 1
        parameters[19] = 0xF6  # -10 dBm, signed.
        parameters[20] = 1  # Primary PHY LE 1M.
        parameters[22] = 1
        advertising_data = bytes.fromhex("02010203031818")
        first, last = advertising_data[:3], advertising_data[3:]
        result = self.read([
            (command(0x2036, bytes(parameters)), 0),
            (command(0x2037, bytes([2, 1, 0, len(first)]) + first), 0),
            (command(0x2037, bytes([2, 2, 0, len(last)]) + last), 0),
            (command(0x2039, bytes([1, 1, 2, 0, 0, 0])), 0),
            (event(0x0E, b"\x01\x36\x20\x00\xf6"), 3),
        ])
        settings = result["advertising"][0]
        self.assertEqual(settings["event_properties"], "0x0013")
        self.assertTrue(settings["legacy_pdu"])
        self.assertTrue(settings["connectable"])
        self.assertTrue(settings["scannable"])
        self.assertFalse(settings["directed"])
        self.assertEqual((settings["interval_min_ms"], settings["interval_max_ms"]), (100, 100))
        self.assertEqual(settings["requested_tx_dbm"], -10)
        self.assertEqual(settings["primary_phy"], 1)
        metadata = result["advertising"][2]["data"]
        self.assertEqual(metadata["flags"], "0x02")
        self.assertEqual(metadata["service_uuids"], ["0x1818"])
        self.assertEqual(metadata["sensor_profiles"], ["CPS"])
        self.assertEqual(result["advertising"][3]["sets"], [
            {"handle": 2, "duration_10ms": 0, "max_events": 0}
        ])
        self.assertEqual(result["advertising"][4]["status"], 0)
        self.assertEqual(result["advertising"][4]["selected_tx_dbm"], -10)

    def test_local_le_roles_and_enhanced_event_offsets(self):
        result = self.read([
            (le_connection(0x40, 1), 3),
            (le_connection(0x41, 0, subevent=0x0A), 3),
            (le_connection(0x42, 1, subevent=0x29), 3),
        ])
        connections = result["connections"]
        self.assertEqual([item["role"] for item in connections], ["peripheral", "central", "peripheral"])
        self.assertEqual([item["handle"] for item in connections], ["0x0040", "0x0041", "0x0042"])
        for item in connections:
            self.assertEqual((item["interval_ms"], item["latency"], item["timeout_ms"]), (30, 0, 2000))
            self.assertEqual(item["peer"], HCI.addr_id(SYNTHETIC_PEER))

    def test_vendor_multi_advertiser_decodes_payloads_without_exporting_addresses(self):
        # Android Multi_Advt 0xFD54: 24-byte parameter subcommand includes own address.
        own = bytes.fromhex("102030405060")
        parameters = (b"\x01" + le16(160) + le16(210) + bytes([0, 1]) + own
                      + b"\x00" + SYNTHETIC_PEER + bytes([7, 0, 1, 0xF6]))
        data = bytes.fromhex("02010203031818")
        scan = bytes.fromhex("040941424303031818")
        result = self.read([
            (command(0xFD54, parameters), 0),
            (command(0xFD54, bytes([2, len(data)]) + data.ljust(31, b"\0") + b"\x01"), 0),
            (command(0xFD54, bytes([3, len(scan)]) + scan.ljust(31, b"\0") + b"\x01"), 0),
            (command(0xFD54, b"\x04" + own + b"\x01"), 0),
            (command(0xFD54, b"\x05\x01\x01"), 0),
            (event(0x0E, b"\x01\x54\xfd\x00\x05"), 3),
        ])
        settings, data_event, scan_event, address, enabled, complete = result["advertising"]
        self.assertEqual((settings["interval_min_ms"], settings["interval_max_ms"]), (100, 131.25))
        self.assertEqual((settings["adv_type"], settings["own_address_type"], settings["primary_phy"]), (0, 1, 1))
        self.assertTrue(settings["legacy_pdu"] and settings["connectable"] and settings["scannable"])
        self.assertFalse(settings["directed"])
        self.assertEqual((settings["channel_map"], settings["filter_policy"], settings["requested_tx_dbm"]), (7, 0, -10))
        self.assertEqual(data_event["data"]["ad_types"], ["0x01", "0x03"])
        self.assertEqual(data_event["data"]["flags"], "0x02")
        self.assertEqual(data_event["data"]["service_uuids"], ["0x1818"])
        self.assertEqual(scan_event["data"]["ad_types"], ["0x09", "0x03"])
        self.assertEqual(scan_event["data"]["name_bytes"], 3)
        self.assertEqual((address["random_address_kind"], address["address_id"]), ("RPA", HCI.addr_id(own)))
        self.assertTrue(enabled["enabled"])
        self.assertEqual((complete["status"], complete["subcommand"]), (0, 5))
        self.assertTrue(all(item["advertising_handle"] == 1 for item in result["advertising"][:-1]))
        self.assertNotIn(own.hex(), json.dumps(result))
        self.assertNotIn(SYNTHETIC_PEER.hex(), json.dumps(result))
        invalid = self.read([(command(0xFD54, b"\x01\x00"), 0)])
        self.assertEqual(invalid["advertising"][0]["unsupported_parameter_length"], 2)

    def test_service_changed_confirmation_belongs_to_local_server_and_mtu_is_decoded(self):
        frames = [
            (le_connection(0x40, 1), 3),
            att(0x40, True, b"\x08\x01\x00\x09\x00\x03\x28"),
            att(0x40, False, b"\x09\x07\x02\x00\x20\x03\x00\x05\x2a"),
            att(0x40, True, b"\x02\xf4\x01"),
            att(0x40, False, b"\x03\x05\x02"),
            att(0x40, False, b"\x1d\x03\x00\x93\x00\x9f\x00"),
            att(0x40, True, b"\x1e"),
        ]
        result = self.read(frames)
        indication = next(item for item in result["att_events"] if item["op"] == "Indication")
        self.assertEqual((indication["characteristic"], indication["changed_start_handle"], indication["changed_end_handle"]),
                         ("0x2a05", "0x0093", "0x009f"))
        confirmation = next(item for item in result["att_events"] if item["op"] == "Confirmation")
        self.assertEqual((confirmation["server"], confirmation["direction"]), ("phone", "remote_to_phone"))
        self.assertEqual([item["mtu"] for item in result["att_events"] if "mtu" in item], [500, 517])

    def test_incoming_client_discovers_phone_cccd_and_receives_test_notification(self):
        result = self.read([(le_connection(0x40, 1), 3)] + sensor_dialog(0x40, remote_client=True))
        events = result["att_events"]
        service = next(item for item in events if item["op"] == "ReadByGroupTypeResponse")
        self.assertEqual(service["sensor_services"], [{"start": 1, "end": 4, "uuid": "0x1818"}])
        write = next(item for item in events if item["op"] == "WriteRequest")
        self.assertEqual((write["server"], write["direction"], write["descriptor"], write["cccd"]),
                         ("phone", "remote_to_phone", "0x2902", "0x0001"))
        notify = next(item for item in events if item["op"] == "Notification")
        self.assertEqual((notify["server"], notify["direction"], notify["characteristic"]),
                         ("phone", "phone_to_remote", "0x2a63"))
        self.assertEqual((notify["measurement_flags"], notify["power_watts"]), ("0x0000", 93))
        self.assertEqual(notify["role"], "peripheral")

    def test_outgoing_client_does_not_become_phone_server_and_private_values_are_omitted(self):
        frames = [(le_connection(0x41, 0), 3)] + sensor_dialog(0x41, remote_client=False)
        frames += [att(0x41, False, b"\x0a\x77\x00"),
                   att(0x41, True, b"\x0bSYNTHETIC_PRIVATE_VALUE"),
                   (command(0x2019, b"SYNTHETIC_SECRET_KEY"), 0)]
        result = self.read(frames)
        notify = next(item for item in result["att_events"] if item["op"] == "Notification")
        self.assertEqual((notify["server"], notify["direction"], notify["role"]),
                         ("remote", "remote_to_phone", "central"))
        self.assertEqual(notify["power_watts"], 93)
        self.assertFalse(any(item["server"] == "phone" for item in result["att_events"]))
        serialized = json.dumps(result)
        self.assertNotIn("SYNTHETIC_PRIVATE_VALUE", serialized)
        self.assertNotIn("SYNTHETIC_SECRET_KEY", serialized)
        remote_counter = next(value for key, value in result["att_counts"].items() if key.endswith("/rx"))
        self.assertEqual(remote_counter["ReadResponse"], 1)

    def test_acl_reassembly_is_separate_for_each_direction_and_handle(self):
        rx_write = le16(5) + le16(4) + bytes.fromhex("1240000100")
        tx_read = le16(3) + le16(4) + bytes.fromhex("0a4100")
        other = le16(3) + le16(4) + bytes.fromhex("0a4200")
        frames = [
            acl_fragment(0x40, True, rx_write[:5]),
            acl_fragment(0x40, False, tx_read[:6]),
            acl_fragment(0x41, True, other[:6]),
            acl_fragment(0x40, True, rx_write[5:], boundary=1),
            acl_fragment(0x41, True, other[6:], boundary=1),
            acl_fragment(0x40, False, tx_read[6:], boundary=1),
            acl_fragment(0x42, True, b"\x12\x43\x00\x01\x00", boundary=1),
        ]
        result = self.read(frames)
        events = result["att_events"]
        # RX requests address the local server; unrelated TX requests are only counted.
        self.assertEqual([(item["handle"], item["op"], item["attribute_handle"]) for item in events],
                         [("0x0040", "WriteRequest", "0x0040"), ("0x0041", "ReadRequest", "0x0042")])
        self.assertEqual(sum(sum(counter.values()) for counter in result["att_counts"].values()), 3)
        self.assertFalse(any("0x0042/" in key for key in result["att_counts"]))

    def test_timestamp_epoch_timezone_and_explicit_vendor_correction_are_independent(self):
        frames = [(command(0x200A, b"\x01"), 0)]
        ordinary = self.read(frames)
        self.assertEqual(ordinary["start"], "2026-10-19T00:00:00.000+00:00")
        displayed = self.read(frames, display_offset_hours=3)
        self.assertEqual(displayed["start"], "2026-10-19T03:00:00.000+03:00")
        corrected = self.read(frames, timestamp_correction_hours=-3, display_offset_hours=3)
        self.assertEqual(corrected["start"], "2026-10-19T00:00:00.000+03:00")
        self.assertEqual(corrected["timestamp_model"]["timestamp_correction_hours"], -3)
        self.assertEqual(corrected["timestamp_model"]["display_offset_hours"], 3)

    def test_truncated_records_preserve_complete_records_and_report_incomplete_tail(self):
        frame = command(0x200A, b"\x01")
        snaplen = HCI.Reader().read(self.write_capture([(frame, 0, len(frame) + 5)]))
        self.assertEqual((snaplen["records"], snaplen["truncated_records"]), (1, 1))
        self.assertTrue(snaplen["advertising"][0]["enabled"])
        for tail, message in [
            (b"\0" * 12, "Incomplete final record header"),
            (struct.pack(">IIIIQ", 10, 10, 0, 0, CAPTURE_TS) + b"\x01\x02",
             "Incomplete final record payload"),
        ]:
            with self.subTest(message=message):
                result = HCI.Reader().read(self.write_capture([(frame, 0)], trailing=tail))
                self.assertEqual(result["records"], 1)
                self.assertEqual(result["errors"], [message])
        with self.subTest(message="invalid file format"):
            self.capture.write_bytes(b"not a btsnoop file")
            with self.assertRaisesRegex(ValueError, "Not a btsnoop file"):
                HCI.Reader().read(self.capture)
        with self.subTest(message="unsupported datalink"):
            self.capture.write_bytes(b"btsnoop\0" + struct.pack(">II", 1, 1001))
            with self.assertRaisesRegex(ValueError, "Unsupported btsnoop version/datalink"):
                HCI.Reader().read(self.capture)


if __name__ == "__main__":
    unittest.main()
