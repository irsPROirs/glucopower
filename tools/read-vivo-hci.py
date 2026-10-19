#!/usr/bin/env python3
"""Read btsnoop H4, including vivo .cfa files, without dumping secrets/ACL payloads.

Reference: Wireshark wiretap/btsnoop.c (magic, big endian records, H4=1002).
Only advertising metadata, connection events and ATT metadata/known CPS/HR
measurements are exported. Key/encryption-command parameters are never exported.
"""
import argparse
import collections
import datetime as dt
import hashlib
import json
import struct
from pathlib import Path

EPOCH = 0x00DCDDB30F2F8000
SERVICES = {0x1818: "CPS", 0x180D: "HR"}
ATT_NAMES = {1: "Error", 2: "ExchangeMtuRequest", 3: "ExchangeMtuResponse",
    4: "FindInformationRequest", 5: "FindInformationResponse", 6: "FindByTypeValueRequest",
    7: "FindByTypeValueResponse", 8: "ReadByTypeRequest", 9: "ReadByTypeResponse",
    10: "ReadRequest", 11: "ReadResponse", 12: "ReadBlobRequest", 13: "ReadBlobResponse",
    16: "ReadByGroupTypeRequest", 17: "ReadByGroupTypeResponse", 18: "WriteRequest",
    19: "WriteResponse", 22: "PrepareWriteRequest", 23: "PrepareWriteResponse",
    24: "ExecuteWriteRequest", 25: "ExecuteWriteResponse", 27: "Notification",
    29: "Indication", 30: "Confirmation", 0x52: "WriteCommand"}
ADV_COMMANDS = {0x2005: "LE_Set_Random_Address", 0x2006: "LE_Set_Advertising_Parameters", 0x2008: "LE_Set_Advertising_Data",
    0x2009: "LE_Set_Scan_Response_Data", 0x200A: "LE_Set_Advertising_Enable",
    0x2035: "LE_Set_Advertising_Set_Random_Address", 0x2036: "LE_Set_Extended_Advertising_Parameters", 0x2037: "LE_Set_Extended_Advertising_Data",
    0x2038: "LE_Set_Extended_Scan_Response_Data", 0x2039: "LE_Set_Extended_Advertising_Enable",
    0x203C: "LE_Remove_Advertising_Set", 0x203D: "LE_Clear_Advertising_Sets",
    0xFD54: "LE_Multi_Advt"}
MULTI_ADV_SUBCOMMANDS = {1: "Set_Parameters", 2: "Set_Advertising_Data",
    3: "Set_Scan_Response_Data", 4: "Set_Random_Address", 5: "Set_Enable"}

def u16(b): return int.from_bytes(b, "little")
def hx(n): return f"0x{n:04x}"
def uuid(b):
    if len(b) == 2: return hx(u16(b))
    if len(b) == 16:
        import uuid as uuidlib
        return str(uuidlib.UUID(bytes=b[::-1]))
    return "invalid-length"
def time_text(ts, timestamp_correction_hours=0, display_offset_hours=0):
    zone = dt.timezone(dt.timedelta(hours=display_offset_hours))
    unix_seconds = (ts-EPOCH)/1_000_000 + timestamp_correction_hours*3600
    return dt.datetime.fromtimestamp(unix_seconds, zone).isoformat(timespec="milliseconds")
def addr_id(b): return "peer-" + hashlib.sha256(b[::-1]).hexdigest()[:8]

def ad_metadata(data):
    result = {"length": len(data), "ad_types": [], "service_uuids": []}
    pos = 0
    while pos < len(data):
        n = data[pos]
        if n == 0: break
        if pos + n + 1 > len(data):
            result["malformed_ad"] = True
            break
        typ, value = data[pos+1], data[pos+2:pos+1+n]
        result["ad_types"].append(f"0x{typ:02x}")
        if typ == 1 and value: result["flags"] = f"0x{value[0]:02x}"
        if typ in (2, 3):
            result["service_uuids"].extend(hx(u16(value[i:i+2])) for i in range(0, len(value)-1, 2))
        if typ in (6, 7):
            result["service_uuids"].extend(uuid(value[i:i+16]) for i in range(0, len(value)-15, 16))
        if typ in (8, 9):
            result["name_bytes"] = len(value)  # Do not export names of unrelated devices/apps.
        if typ == 0x19 and len(value) == 2: result["appearance"] = hx(u16(value))
        pos += n + 1
    result["sensor_profiles"] = [name for n, name in SERVICES.items() if hx(n) in result["service_uuids"]]
    return result

class Reader:
    def __init__(self, timestamp_correction_hours=0, display_offset_hours=0):
        self.timestamp_correction_hours=timestamp_correction_hours
        self.display_offset_hours=display_offset_hours
        self.counts = collections.Counter()
        self.connections = []
        self.advertising = []
        self.att_events = []
        self.att_counts = collections.defaultdict(collections.Counter)
        self.current = {}
        self.db = collections.defaultdict(lambda: {"services": [], "chars": {}, "descriptors": {}})
        self.pending = {}
        self.fragments = {}
        self.ad_fragments = {}
        self.errors = []
        self.event_limit = 1500

    def connection(self, handle):
        return self.current.get(handle, {"peer": "existing-or-unknown", "role": "unknown"})

    def forget_connection(self, handle):
        self.current.pop(handle,None)
        self.db.pop(f"remote/{handle}",None)
        for table in (self.pending,self.fragments):
            for key in list(table):
                if key[0] == handle: table.pop(key)

    def command(self, p, base):
        if len(p) < 3: return
        op = u16(p[:2]); b = p[3:3+p[2]]
        self.counts["command_" + hx(op)] += 1
        if op == 0x0C03:  # Controller reset invalidates connection and attribute caches.
            self.current.clear()
            self.db.clear()
            self.pending.clear()
            self.fragments.clear()
            self.ad_fragments.clear()
        if op in ADV_COMMANDS:
            e = dict(base, command=ADV_COMMANDS[op], opcode=hx(op))
            if op == 0xFD54 and b:
                # Android vendor multi-advertiser specification, not an extended
                # advertising PDU. Parameters include Own_Address before Peer_Address.
                # https://source.android.com/docs/core/connect/bluetooth/hci_requirements#multi-advertiser-support
                e.update(subcommand=b[0], subcommand_name=MULTI_ADV_SUBCOMMANDS.get(b[0], "Unknown"))
                if b[0] == 1 and len(b) == 24:
                    e.update(interval_min_ms=u16(b[1:3])*.625,
                        interval_max_ms=u16(b[3:5])*.625, adv_type=b[5],
                        legacy_pdu=True, connectable=b[5] in (0,1,4),
                        scannable=b[5] in (0,2), directed=b[5] in (1,4),
                        own_address_type=b[6], address_id=addr_id(b[7:13]),
                        directed_peer_present=any(b[14:20]), channel_map=b[20],
                        filter_policy=b[21], advertising_handle=b[22],
                        requested_tx_dbm=struct.unpack("b",b[23:24])[0], primary_phy=1)
                elif b[0] in (2,3) and len(b) == 34 and b[1] <= 31:
                    e.update(advertising_handle=b[33], data=ad_metadata(b[2:2+b[1]]))
                elif b[0] == 4 and len(b) == 8:
                    e.update(advertising_handle=b[7], address_id=addr_id(b[1:7]),
                        random_address_kind={0:"NRPA",1:"RPA",3:"static"}.get(b[6] >> 6,"reserved"))
                elif b[0] == 5 and len(b) == 3:
                    e.update(advertising_handle=b[2], enabled=bool(b[1]))
                else:
                    e["unsupported_parameter_length"] = len(b)
            elif op in (0x2005,0x2035) and len(b) >= (6 if op == 0x2005 else 7):
                address=b[:6] if op == 0x2005 else b[1:7]
                e["address_id"]=addr_id(address)
                e["random_address_kind"]={0:"NRPA",1:"RPA",3:"static"}.get(address[5] >> 6,"reserved")
                if op == 0x2035: e["advertising_handle"]=b[0]
            elif op == 0x2006 and len(b) >= 15:
                e.update(interval_min_ms=u16(b[:2])*.625, interval_max_ms=u16(b[2:4])*.625,
                    adv_type=b[4], own_address_type=b[5], directed_peer_present=any(b[7:13]),
                    channel_map=b[13], filter_policy=b[14])
            elif op in (0x2008, 0x2009) and b:
                e["data"] = ad_metadata(b[1:1+b[0]])
            elif op == 0x200A and b: e["enabled"] = bool(b[0])
            elif op == 0x2036 and len(b) >= 25:
                props = u16(b[1:3])
                e.update(advertising_handle=b[0], event_properties=hx(props), legacy_pdu=bool(props & 16),
                    connectable=bool(props & 1), scannable=bool(props & 2), directed=bool(props & 4),
                    interval_min_ms=int.from_bytes(b[3:6], "little")*.625,
                    interval_max_ms=int.from_bytes(b[6:9], "little")*.625,
                    channel_map=b[9], own_address_type=b[10], filter_policy=b[18],
                    requested_tx_dbm=struct.unpack("b", b[19:20])[0], primary_phy=b[20], secondary_phy=b[22])
            elif op in (0x2037, 0x2038) and len(b) >= 4:
                key=(op,b[0]); operation=b[1]; value=b[4:4+b[3]]
                e.update(advertising_handle=b[0], operation=operation)
                if operation == 1: self.ad_fragments[key] = value
                elif operation in (0,2): self.ad_fragments[key] = self.ad_fragments.get(key,b"") + value
                if operation == 3: e["data"] = ad_metadata(value)
                elif operation == 2: e["data"] = ad_metadata(self.ad_fragments.pop(key,b""))
            elif op == 0x2039 and len(b) >= 2:
                e.update(enabled=bool(b[0]), sets=[{"handle":b[i],"duration_10ms":u16(b[i+1:i+3]),
                    "max_events":b[i+3]} for i in range(2,min(len(b)-3,2+b[1]*4),4)])
            self.advertising.append(e)
        elif op in (0x200D, 0x2043):
            offset = 6 if op == 0x200D else 3
            if len(b) >= offset+6:
                self.connections.append(dict(base,event="outgoing_create_connection",peer=addr_id(b[offset:offset+6]),opcode=hx(op)))
        elif op == 0x0406 and len(b) >= 3:
            self.connections.append(dict(base,event="disconnect_command",handle=hx(u16(b[:2])),reason=b[2],**self.connection(u16(b[:2]))))

    def event(self, p, base):
        if len(p) < 2: return
        event = p[0]; b = p[2:2+p[1]]
        self.counts[f"event_0x{event:02x}"] += 1
        if event == 0x0E and len(b) >= 4:
            op = u16(b[1:3])
            if op in ADV_COMMANDS:
                e=dict(base,event="CommandComplete",opcode=hx(op),status=b[3])
                if op == 0x2036 and len(b) >= 5: e["selected_tx_dbm"]=struct.unpack("b",b[4:5])[0]
                if op == 0xFD54 and len(b) >= 5: e["subcommand"]=b[4]
                self.advertising.append(e)
        elif event == 0x0F and len(b) >= 4:
            op = u16(b[2:4])
            if op in ADV_COMMANDS or op in (0x200D,0x2043):
                self.connections.append(dict(base,event="CommandStatus",opcode=hx(op),status=b[0]))
        elif event == 0x3E and b:
            sub = b[0]; v=b[1:]
            self.counts[f"le_event_0x{sub:02x}"] += 1
            if sub in (1,10,0x29) and len(v) >= 11:
                status,handle,role=v[0],u16(v[1:3]),v[3]
                c={"peer":addr_id(v[5:11]),"role":"central" if role == 0 else "peripheral" if role == 1 else str(role)}
                e=dict(base,event="LE_Connection_Complete",subevent=f"0x{sub:02x}",status=status,handle=hx(handle),peer_address_type=v[4],**c)
                start=11 if sub == 1 else 23
                if len(v) >= start+6:
                    e.update(interval_ms=u16(v[start:start+2])*1.25,latency=u16(v[start+2:start+4]),timeout_ms=u16(v[start+4:start+6])*10)
                self.connections.append(e)
                if status == 0:
                    self.forget_connection(handle)
                    self.current[handle]=c
            elif sub == 0x12 and len(v) >= 4:
                self.advertising.append(dict(base,event="AdvertisingSetTerminated",status=v[0],advertising_handle=v[1],connection_handle=hx(u16(v[2:4]))))
        elif event == 0x05 and len(b) >= 4:
            handle=u16(b[1:3])
            self.connections.append(dict(base,event="DisconnectionComplete",status=b[0],handle=hx(handle),reason=b[3],**self.connection(handle)))
            self.forget_connection(handle)
        elif event in (0x08,0x59) and len(b) >= 4:
            handle=u16(b[1:3])
            e=dict(base,event="EncryptionChange",status=b[0],handle=hx(handle),enabled=b[3],**self.connection(handle))
            if event == 0x59 and len(b) >= 5: e["key_size_bytes"]=b[4]
            self.connections.append(e)

    def attribute(self, handle, incoming, p, base):
        if not p: return
        op=p[0]; name=ATT_NAMES.get(op,f"0x{op:02x}")
        c=self.connection(handle)
        counter_key=f"{hx(handle)}/{c['peer']}/{c['role']}/" + ("rx" if incoming else "tx")
        self.att_counts[counter_key][name] += 1
        # A request RX or response TX addresses the phone's own attribute server.
        requests={2,4,6,8,10,12,16,18,22,24,0x52}
        # Confirmation is sent by the indication recipient (the GATT client).
        server_local = incoming if op in requests or op == 30 else not incoming
        db_key="local" if server_local else f"remote/{handle}"
        db=self.db[db_key]
        e=dict(base,handle=hx(handle),direction="remote_to_phone" if incoming else "phone_to_remote",op=name,server="phone" if server_local else "remote",**c)
        if op in (2,3) and len(p) >= 3:
            e["mtu"] = u16(p[1:3])
        elif op in (4,6,8,16) and len(p) >= 5:
            e.update(start_handle=hx(u16(p[1:3])),end_handle=hx(u16(p[3:5])))
            if op in (6,8,16) and len(p) >= 7: e["attribute_type"]=uuid(p[5:]) if op != 6 else uuid(p[5:7])
            self.pending[(handle,incoming)]=(op,p)
        elif op == 17 and len(p) >= 2:
            n=p[1]; entries=[]
            if n >= 6:
                for pos in range(2,len(p)-n+1,n):
                    value=p[pos:pos+n]; item={"start":u16(value[:2]),"end":u16(value[2:4]),"uuid":uuid(value[4:])}
                    db["services"].append(item)
                    if item["uuid"] in ("0x1818","0x180d"): entries.append(item)
            e["sensor_services"]=entries
        elif op == 9 and len(p) >= 2:
            req=self.pending.get((handle,not incoming)); n=p[1]; entries=[]
            if req and req[0] == 8 and req[1][5:] == b"\x03\x28" and n >= 7:
                for pos in range(2,len(p)-n+1,n):
                    value=p[pos:pos+n]; h=u16(value[3:5]); char=uuid(value[5:]); db["chars"][h]=char
                    if char in ("0x2a63","0x2a65","0x2a5d","0x2a37","0x2a66"):
                        entries.append({"value_handle":hx(h),"uuid":char,"properties":value[2]})
            e["sensor_characteristics"]=entries
        elif op == 5 and len(p) >= 2:
            n=4 if p[1] == 1 else 18
            cccds=[]
            for pos in range(2,len(p)-n+1,n):
                value=p[pos:pos+n]; db["descriptors"][u16(value[:2])]=uuid(value[2:])
                if uuid(value[2:]) == "0x2902": cccds.append(hx(u16(value[:2])))
            if cccds: e["cccd_handles"]=cccds
        elif op in (10,12,18,0x52,27,29) and len(p) >= 3:
            h=u16(p[1:3]); char=db["chars"].get(h); desc=db["descriptors"].get(h)
            e["attribute_handle"]=hx(h)
            if char: e["characteristic"]=char
            if desc: e["descriptor"]=desc
            e["value_length"]=max(0,len(p)-3)
            if op == 12 and len(p) >= 5: e["offset"]=u16(p[3:5])
            if op in (18,0x52) and desc == "0x2902" and len(p) == 5:
                e["cccd"]=hx(u16(p[3:5]))
            if op in (27,29) and char == "0x2a63" and len(p) >= 7:
                e.update(measurement_flags=hx(u16(p[3:5])),power_watts=int.from_bytes(p[5:7],"little",signed=True))
            if op in (27,29) and char == "0x2a37" and len(p) >= 5:
                e["heart_rate"]=u16(p[4:6]) if p[3] & 1 and len(p) >= 6 else p[4]
            if op == 29 and char == "0x2a05" and len(p) == 7:
                e.update(changed_start_handle=hx(u16(p[3:5])), changed_end_handle=hx(u16(p[5:7])))
            if op in (10,12): self.pending[(handle,incoming)]=(op,p)
        elif op == 1 and len(p) >= 5:
            e.update(request_opcode=f"0x{p[1]:02x}",attribute_handle=hx(u16(p[2:4])),att_error=f"0x{p[4]:02x}")
        # No values from unrelated devices. Keep local discovery / local attribute metadata;
        # remote traffic is summarized only, unless it is a known CPS/HR attribute.
        relevant = server_local or e.get("characteristic") in ("0x2a63","0x2a37","0x2a65","0x2a5d","0x2a66") or bool(e.get("sensor_services")) or bool(e.get("sensor_characteristics"))
        if relevant:
            self.counts["relevant_att_events"] += 1
            if len(self.att_events) < self.event_limit: self.att_events.append(e)

    def acl(self, p, incoming, base):
        if len(p)<4: return
        word=u16(p[:2]); handle=word & 0xfff; pb=(word >> 12) & 3
        key=(handle,incoming); value=p[4:4+u16(p[2:4])]
        if pb == 1:
            if key not in self.fragments: return
            self.fragments[key] += value
        else: self.fragments[key]=value
        data=self.fragments[key]
        if len(data) < 4: return
        n=u16(data[:2]); cid=u16(data[2:4])
        if len(data) < 4+n: return
        self.fragments.pop(key,None)
        if cid == 4: self.attribute(handle,incoming,data[4:4+n],base)

    def read(self,path):
        total=0; truncated=0; drops=0; start=None; end=None
        with open(path,"rb") as f:
            header=f.read(16)
            if len(header)!=16 or header[:8]!=b"btsnoop\0": raise ValueError("Not a btsnoop file")
            version,datalink=struct.unpack(">II",header[8:])
            if (version,datalink)!=(1,1002): raise ValueError(f"Unsupported btsnoop version/datalink: {version}/{datalink}")
            while True:
                rh=f.read(24)
                if not rh: break
                if len(rh)!=24: self.errors.append("Incomplete final record header"); break
                orig,n,flags,lost,ts=struct.unpack(">IIIIQ",rh)
                if n > 1_000_000: raise ValueError("Invalid record length")
                p=f.read(n)
                if len(p)!=n: self.errors.append("Incomplete final record payload"); break
                total+=1; drops=max(drops,lost); truncated+=n<orig
                t=time_text(ts,self.timestamp_correction_hours,self.display_offset_hours); start=start or t; end=t
                base={"record":total,"time":t}
                if not p: continue
                self.counts[f"h4_type_{p[0]}"]+=1
                if p[0]==1: self.command(p[1:],base)
                elif p[0]==4: self.event(p[1:],base)
                elif p[0]==2: self.acl(p[1:],bool(flags&1),base)
        return {"format":"btsnoop H4", "version":version,"datalink":datalink,"records":total,
            "start":start,"end":end,"timestamp_model":{
                "source_epoch":"standard btsnoop epoch offset to Unix UTC",
                "timestamp_correction_hours":self.timestamp_correction_hours,
                "display_offset_hours":self.display_offset_hours,
                "note":"Corrections are explicit user-supplied adjustments; defaults do not assume any vendor clock behavior."},
            "truncated_records":truncated,"cumulative_drops":drops,
            "counts":dict(self.counts),"advertising":self.advertising,"connections":self.connections,
            "att_counts":{k:dict(v) for k,v in self.att_counts.items()},"att_events":self.att_events,
            "att_event_limit":self.event_limit,"errors":self.errors,
            "limitations":["No arbitrary ACL payloads or encryption keys exported", "ATT on fixed L2CAP CID 0x0004 only; enhanced ATT channels are not decoded", "Unrelated remote ATT values omitted", "Host HCI is not an over-the-air capture"]}

def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument("input",type=Path)
    ap.add_argument("--output",type=Path)
    ap.add_argument("--timestamp-correction-hours",type=float,default=0,
        help="Adjust decoded Unix timestamp (default 0); use only after correlating capture with system logs")
    ap.add_argument("--display-offset-hours",type=float,default=0,
        help="Display timezone offset (default 0=UTC), independent of timestamp correction")
    args=ap.parse_args()
    result=Reader(args.timestamp_correction_hours,args.display_offset_hours).read(args.input)
    text=json.dumps(result,ensure_ascii=False,indent=2)
    if args.output: args.output.write_text(text+"\n",encoding="utf-8")
    else: print(text)

if __name__ == "__main__": main()
