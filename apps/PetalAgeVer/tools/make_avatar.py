#!/usr/bin/env python3
# Copyright (C) 2026 petalOS
# SPDX-License-Identifier: Apache-2.0
#
# Generates the bundled privacy avatar (assets/avatars/default.glb).
#
# The avatar is intentionally generic: a stylized, feature-light figure whose
# face is driven by a handful of named nodes (Head, EyeL, EyeR, Mouth) and a
# body that can turn. No third-party model is vendored, so the asset is clean
# to redistribute and can be regenerated at any time.
#
# Usage: python3 tools/make_avatar.py [output.glb]

import json
import math
import os
import struct
import sys

COMPONENT_FLOAT = 5126
COMPONENT_USHORT = 5123
COMPONENT_UINT = 5125
ARRAY_BUFFER = 34962
ELEMENT_ARRAY_BUFFER = 34963


def normalize(v):
    length = math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]) or 1.0
    return (v[0] / length, v[1] / length, v[2] / length)


def add_triangle(indices, verts, a, b, c):
    """Append a triangle, flipping winding so it faces along the vertex normals."""
    normal = normalize(verts[a]["n"])
    ax, ay, az = verts[a]["p"]
    bx, by, bz = verts[b]["p"]
    cx, cy, cz = verts[c]["p"]
    ux, uy, uz = bx - ax, by - ay, bz - az
    vx, vy, vz = cx - ax, cy - ay, cz - az
    nx, ny, nz = uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx
    if nx * normal[0] + ny * normal[1] + nz * normal[2] < 0:
        indices += [a, c, b]
    else:
        indices += [a, b, c]


def uv_sphere(radius, segments=24, rings=16):
    verts = []
    for i in range(rings + 1):
        v = i / rings
        theta = v * math.pi
        for j in range(segments + 1):
            u = j / segments
            phi = u * 2 * math.pi
            nx = math.sin(theta) * math.cos(phi)
            ny = math.cos(theta)
            nz = math.sin(theta) * math.sin(phi)
            verts.append({
                "p": (nx * radius, ny * radius, nz * radius),
                "n": (nx, ny, nz),
                "uv": (u, 1.0 - v),
            })
    indices = []
    stride = segments + 1
    for i in range(rings):
        for j in range(segments):
            a = i * stride + j
            b = a + 1
            c = a + stride
            d = c + 1
            add_triangle(indices, verts, a, b, d)
            add_triangle(indices, verts, a, d, c)
    return verts, indices


def cylinder(r_bottom, r_top, y_bottom, y_top, segments=24, caps=True):
    verts = []
    indices = []
    for j in range(segments + 1):
        u = j / segments
        phi = u * 2 * math.pi
        cos_p = math.cos(phi)
        sin_p = math.sin(phi)
        verts.append({"p": (cos_p * r_bottom, y_bottom, sin_p * r_bottom),
                      "n": (cos_p, 0.0, sin_p), "uv": (u, 0.0)})
        verts.append({"p": (cos_p * r_top, y_top, sin_p * r_top),
                      "n": (cos_p, 0.0, sin_p), "uv": (u, 1.0)})
    stride = 2
    for j in range(segments):
        a = j * stride
        b = a + 1
        c = a + stride
        d = c + 1
        add_triangle(indices, verts, a, b, d)
        add_triangle(indices, verts, a, d, c)
    if caps:
        bottom_center = len(verts)
        verts.append({"p": (0.0, y_bottom, 0.0), "n": (0.0, -1.0, 0.0), "uv": (0.5, 0.5)})
        top_center = len(verts)
        verts.append({"p": (0.0, y_top, 0.0), "n": (0.0, 1.0, 0.0), "uv": (0.5, 0.5)})
        for j in range(segments):
            a = j * stride
            c = a + stride
            add_triangle(indices, verts, bottom_center, c, a)
            add_triangle(indices, verts, top_center, a + 1, c + 1)
    return verts, indices


def merge(*parts):
    verts = []
    indices = []
    for part_verts, part_indices in parts:
        offset = len(verts)
        verts.extend(part_verts)
        indices.extend(i + offset for i in part_indices)
    return verts, indices


class GlbBuilder:
    def __init__(self):
        self.bin = bytearray()
        self.buffer_views = []
        self.accessors = []
        self.meshes = []

    def _align(self, size=4):
        while len(self.bin) % size:
            self.bin.append(0)

    def _add_view(self, data, target=None):
        self._align(4)
        offset = len(self.bin)
        self.bin.extend(data)
        view = {"buffer": 0, "byteOffset": offset, "byteLength": len(data)}
        if target is not None:
            view["target"] = target
        self.buffer_views.append(view)
        return len(self.buffer_views) - 1

    def add_positions(self, positions):
        flat = [c for p in positions for c in p]
        view = self._add_view(struct.pack("<%df" % len(flat), *flat), ARRAY_BUFFER)
        mins = [min(p[i] for p in positions) for i in range(3)]
        maxs = [max(p[i] for p in positions) for i in range(3)]
        self.accessors.append({
            "bufferView": view, "componentType": COMPONENT_FLOAT,
            "count": len(positions), "type": "VEC3", "min": mins, "max": maxs,
        })
        return len(self.accessors) - 1

    def add_normals(self, normals):
        flat = [c for n in normals for c in n]
        view = self._add_view(struct.pack("<%df" % len(flat), *flat), ARRAY_BUFFER)
        self.accessors.append({
            "bufferView": view, "componentType": COMPONENT_FLOAT,
            "count": len(normals), "type": "VEC3",
        })
        return len(self.accessors) - 1

    def add_uvs(self, uvs):
        flat = [c for uv in uvs for c in uv]
        view = self._add_view(struct.pack("<%df" % len(flat), *flat), ARRAY_BUFFER)
        self.accessors.append({
            "bufferView": view, "componentType": COMPONENT_FLOAT,
            "count": len(uvs), "type": "VEC2",
        })
        return len(self.accessors) - 1

    def add_indices(self, indices):
        if max(indices) < 65536:
            data = struct.pack("<%dH" % len(indices), *indices)
            component = COMPONENT_USHORT
        else:
            data = struct.pack("<%dI" % len(indices), *indices)
            component = COMPONENT_UINT
        view = self._add_view(data, ELEMENT_ARRAY_BUFFER)
        self.accessors.append({
            "bufferView": view, "componentType": component,
            "count": len(indices), "type": "SCALAR",
        })
        return len(self.accessors) - 1

    def add_mesh(self, name, verts, indices, material):
        positions = self.add_positions([v["p"] for v in verts])
        normals = self.add_normals([v["n"] for v in verts])
        uvs = self.add_uvs([v["uv"] for v in verts])
        index_accessor = self.add_indices(indices)
        self.meshes.append({
            "name": name,
            "primitives": [{
                "attributes": {"POSITION": positions, "NORMAL": normals, "TEXCOORD_0": uvs},
                "indices": index_accessor,
                "material": material,
            }],
        })
        return len(self.meshes) - 1


def build():
    builder = GlbBuilder()

    materials = [
        {"name": "Skin", "pbrMetallicRoughness": {
            "baseColorFactor": [0.95, 0.79, 0.69, 1.0],
            "metallicFactor": 0.0, "roughnessFactor": 0.62}},
        {"name": "Dark", "pbrMetallicRoughness": {
            "baseColorFactor": [0.10, 0.10, 0.13, 1.0],
            "metallicFactor": 0.0, "roughnessFactor": 0.35}},
        {"name": "Shirt", "pbrMetallicRoughness": {
            "baseColorFactor": [0.30, 0.44, 0.78, 1.0],
            "metallicFactor": 0.0, "roughnessFactor": 0.8}},
    ]

    head_mesh = builder.add_mesh("HeadMesh", *uv_sphere(0.125, 28, 20), 0)
    torso = cylinder(0.16, 0.20, 0.0, 1.42, 24)
    neck = cylinder(0.052, 0.048, 1.40, 1.58, 16, caps=True)
    body_mesh = builder.add_mesh("BodyMesh", *merge(torso, neck), 2)
    eye_mesh = builder.add_mesh("EyeMesh", *uv_sphere(0.021, 12, 8), 1)

    mouth_verts, mouth_indices = uv_sphere(1.0, 14, 8)
    mouth_verts = [{"p": (v["p"][0] * 0.05, v["p"][1] * 0.014, v["p"][2] * 0.02),
                    "n": v["n"], "uv": v["uv"]} for v in mouth_verts]
    mouth_mesh = builder.add_mesh("MouthMesh", mouth_verts, mouth_indices, 1)

    nodes = [
        {"name": "Avatar", "children": [1]},
        {"name": "Body", "mesh": body_mesh, "children": [2]},
        {"name": "Head", "mesh": head_mesh, "translation": [0.0, 1.70, 0.0],
         "scale": [0.94, 1.06, 0.96], "children": [3, 4, 5]},
        {"name": "EyeL", "mesh": eye_mesh, "translation": [-0.048, 0.022, 0.106]},
        {"name": "EyeR", "mesh": eye_mesh, "translation": [0.048, 0.022, 0.106]},
        {"name": "Mouth", "mesh": mouth_mesh, "translation": [0.0, -0.055, 0.104]},
    ]

    gltf = {
        "asset": {"version": "2.0", "generator": "petalOS make_avatar.py"},
        "scene": 0,
        "scenes": [{"nodes": [0]}],
        "nodes": nodes,
        "meshes": builder.meshes,
        "materials": materials,
        "buffers": [{"byteLength": len(builder.bin)}],
        "bufferViews": builder.buffer_views,
        "accessors": builder.accessors,
    }
    return gltf, bytes(builder.bin)


def write_glb(gltf, binary, path):
    json_bytes = json.dumps(gltf, separators=(",", ":")).encode("utf-8")
    while len(json_bytes) % 4:
        json_bytes += b" "
    bin_bytes = bytearray(binary)
    while len(bin_bytes) % 4:
        bin_bytes.append(0)

    total = 12 + 8 + len(json_bytes) + 8 + len(bin_bytes)
    with open(path, "wb") as f:
        f.write(struct.pack("<III", 0x46546C67, 2, total))
        f.write(struct.pack("<II", len(json_bytes), 0x4E4F534A))
        f.write(json_bytes)
        f.write(struct.pack("<II", len(bin_bytes), 0x004E4942))
        f.write(bytes(bin_bytes))


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "assets", "avatars", "default.glb")
    gltf, binary = build()
    os.makedirs(os.path.dirname(out), exist_ok=True)
    write_glb(gltf, binary, out)
    print("wrote %s (%d bytes)" % (out, os.path.getsize(out)))


if __name__ == "__main__":
    main()
