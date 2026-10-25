#!/usr/bin/env python3
"""Fill the old man's skinned inner-mouth mesh across its missing centre.

The imported scan has black inner-mouth faces at the sides, but only upper-lip
vertices in the middle. When the jaw opens, the underlying face shows through
as a strip. This adds a thin black centre panel: its top follows the upper lip
and its bottom follows the lower lip, so it stretches with the opening.
"""

import json
import os
import struct
import sys
import tempfile


def patch(path):
    data = open(path, "rb").read()
    if data[:4] != b"glTF":
        raise ValueError("expected a binary glTF")
    json_size = struct.unpack_from("<I", data, 12)[0]
    gltf = json.loads(data[20:20 + json_size])
    primitive = gltf["meshes"][0]["primitives"][1]
    if primitive.get("extras", {}).get("petalMouthCenterPatch"):
        return False
    binary_offset = 20 + json_size + 8
    binary = bytearray(data[binary_offset:])
    old_accessors = gltf["accessors"]
    old_views = gltf["bufferViews"]

    def values(index):
        accessor = old_accessors[index]
        view = old_views[accessor["bufferView"]]
        component = {5121: "B", 5123: "H", 5126: "f"}[accessor["componentType"]]
        width = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4}[accessor["type"]]
        fmt = "<" + component * width
        start = view.get("byteOffset", 0) + accessor.get("byteOffset", 0)
        stride = view.get("byteStride", struct.calcsize(fmt))
        return [struct.unpack_from(fmt, binary, start + i * stride)
                for i in range(accessor["count"])]

    # Indices 7 and 8 in this skin are the upper and lower lip joints.
    top = [(-0.12, -0.485, 1.13), (0.0, -0.485, 1.13),
           (0.12, -0.485, 1.13)]
    bottom = [(-0.12, -0.505, 1.13), (0.0, -0.505, 1.13),
              (0.12, -0.505, 1.13)]
    additions = {
        "POSITION": top + bottom,
        "NORMAL": [(0.0, 0.0, 1.0)] * 6,
        "TEXCOORD_0": [(0.0, 0.0)] * 6,
        "TEXCOORD_1": [(0.0, 0.0)] * 6,
        "JOINTS_0": [(7, 0, 0, 0)] * 3 + [(8, 0, 0, 0)] * 3,
        "WEIGHTS_0": [(1.0, 0.0, 0.0, 0.0)] * 6,
    }

    def append_accessor(items, component_type, kind, target):
        while len(binary) % 4:
            binary.append(0)
        offset = len(binary)
        component = {5121: "B", 5123: "H", 5126: "f"}[component_type]
        width = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4}[kind]
        fmt = "<" + component * width
        for item in items:
            binary.extend(struct.pack(fmt, *item))
        view_index = len(gltf["bufferViews"])
        gltf["bufferViews"].append({"buffer": 0, "byteOffset": offset,
                                    "byteLength": len(binary) - offset,
                                    "target": target})
        result = {"bufferView": view_index, "componentType": component_type,
                  "count": len(items), "type": kind}
        if kind == "VEC3" and target == 34962:
            result["min"] = [min(item[i] for item in items) for i in range(3)]
            result["max"] = [max(item[i] for item in items) for i in range(3)]
        gltf["accessors"].append(result)
        return len(gltf["accessors"]) - 1

    original_count = old_accessors[primitive["attributes"]["POSITION"]]["count"]
    for name, extra in additions.items():
        old = old_accessors[primitive["attributes"][name]]
        primitive["attributes"][name] = append_accessor(
                values(primitive["attributes"][name]) + extra,
                old["componentType"], old["type"], 34962)
    index_accessor = old_accessors[primitive["indices"]]
    indices = values(primitive["indices"])
    for side in range(2):
        a = original_count + side
        b = original_count + side + 1
        c = original_count + 3 + side
        d = original_count + 3 + side + 1
        indices.extend([(a,), (c,), (b,), (b,), (c,), (d,)])
    primitive["indices"] = append_accessor(indices, index_accessor["componentType"],
                                            "SCALAR", 34963)
    primitive.setdefault("extras", {})["petalMouthCenterPatch"] = True

    gltf["buffers"][0]["byteLength"] = len(binary)
    json_bytes = json.dumps(gltf, separators=(",", ":")).encode("utf-8")
    json_bytes += b" " * (-len(json_bytes) % 4)
    binary.extend(b"\0" * (-len(binary) % 4))
    output = (struct.pack("<III", 0x46546C67, 2,
                          12 + 8 + len(json_bytes) + 8 + len(binary))
              + struct.pack("<I4s", len(json_bytes), b"JSON") + json_bytes
              + struct.pack("<I4s", len(binary), b"BIN\0") + binary)
    fd, temp = tempfile.mkstemp(dir=os.path.dirname(path), suffix=".glb")
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(output)
        os.replace(temp, path)
    finally:
        if os.path.exists(temp):
            os.unlink(temp)
    return True


if __name__ == "__main__":
    filename = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
            os.path.dirname(os.path.dirname(__file__)), "assets", "avatars",
            "free_old_man_head.glb")
    print("patched" if patch(filename) else "already patched")
