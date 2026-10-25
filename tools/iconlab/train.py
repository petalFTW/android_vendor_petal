"""Train the tiny icon subject segmenter.

The reviewed masks are turned into synthetic app icons on random backgrounds, so
the model learns "ignore the tile, keep the subject" instead of memorising the
132 icons in the dump.

Windows:
  py -3.12 -m venv .venv
  .venv\\Scripts\\activate
  pip install torch torchvision --index-url https://download.pytorch.org/whl/cu124
  pip install -r requirements-train.txt
  python train.py --data work/export --out work/model --device cuda
"""

from __future__ import annotations

import argparse
import json
import math
import random
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageEnhance, ImageFilter, ImageOps

try:
    import torch
    import torch.nn as nn
    from torch.utils.data import DataLoader, Dataset
except ImportError as error:  # pragma: no cover
    raise SystemExit(
        "PyTorch is not installed. See README.md for the Windows CUDA setup."
    ) from error

IMAGENET_MEAN = np.array([0.485, 0.456, 0.406], dtype=np.float32)
IMAGENET_STD = np.array([0.229, 0.224, 0.225], dtype=np.float32)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Train the icon subject segmenter")
    parser.add_argument("--data", default="work/export", help="exported dataset directory")
    parser.add_argument("--out", default="work/model", help="output directory")
    parser.add_argument("--size", type=int, default=256)
    parser.add_argument("--epochs", type=int, default=40)
    parser.add_argument("--batch", type=int, default=16)
    parser.add_argument("--lr", type=float, default=1e-3)
    parser.add_argument("--repeat", type=int, default=24, help="synthetic samples per icon per epoch")
    parser.add_argument("--workers", type=int, default=0)
    parser.add_argument("--device", default="auto", choices=["auto", "cuda", "cpu"])
    parser.add_argument("--seed", type=int, default=1337)
    parser.add_argument("--limit", type=int, default=0, help="limit training records (debug)")
    parser.add_argument("--no-pretrained", action="store_true")
    parser.add_argument("--preview", action="store_true", help="save validation predictions")
    parser.add_argument("--encoder", default="mobilenet_v3_small")
    return parser.parse_args()


def resolve_device(name: str) -> torch.device:
    if name == "auto":
        return torch.device("cuda" if torch.cuda.is_available() else "cpu")
    return torch.device(name)


def load_manifest(data_dir: Path) -> dict:
    path = data_dir / "dataset.json"
    if not path.is_file():
        raise SystemExit(f"missing {path}; click Export dataset in IconLab first")
    return json.loads(path.read_text(encoding="utf-8"))


def load_pair(record: dict, data_dir: Path) -> tuple[Image.Image, Image.Image]:
    image = Image.open(data_dir / record["image"]).convert("RGBA")
    mask = Image.open(data_dir / record["mask"]).convert("L")
    if mask.size != image.size:
        mask = mask.resize(image.size, Image.Resampling.LANCZOS)
    mask = mask.point(lambda value: 255 if value >= 128 else 0, "L")
    return image, mask


def gradient(size: tuple[int, int], first: tuple[int, int, int],
             second: tuple[int, int, int], angle: float) -> Image.Image:
    width, height = size
    yy, xx = np.mgrid[0:height, 0:width].astype(np.float32)
    t = math.cos(angle) * (xx / max(1, width - 1)) + math.sin(angle) * (yy / max(1, height - 1))
    t = (t - t.min()) / (t.max() - t.min() + 1e-6)
    a = np.array(first, dtype=np.float32)
    b = np.array(second, dtype=np.float32)
    pixels = a[None, None, :] * (1 - t[..., None]) + b[None, None, :] * t[..., None]
    return Image.fromarray(pixels.astype(np.uint8), "RGB").convert("RGBA")


def radial(size: tuple[int, int], first: tuple[int, int, int],
           second: tuple[int, int, int]) -> Image.Image:
    width, height = size
    yy, xx = np.mgrid[0:height, 0:width].astype(np.float32)
    cx, cy = random.uniform(0.25, 0.75) * width, random.uniform(0.25, 0.75) * height
    distance = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2)
    t = np.clip(distance / (distance.max() + 1e-6), 0, 1)
    a = np.array(first, dtype=np.float32)
    b = np.array(second, dtype=np.float32)
    pixels = a[None, None, :] * (1 - t[..., None]) + b[None, None, :] * t[..., None]
    return Image.fromarray(pixels.astype(np.uint8), "RGB").convert("RGBA")


def noise_background(size: tuple[int, int]) -> Image.Image:
    width, height = size
    array = np.random.randint(0, 256, (height, width, 3), dtype=np.uint8)
    image = Image.fromarray(array, "RGB").filter(ImageFilter.GaussianBlur(random.uniform(0, 3)))
    return image.convert("RGBA")


def pattern_background(size: tuple[int, int]) -> Image.Image:
    width, height = size
    c1 = tuple(random.randint(20, 240) for _ in range(3))
    c2 = tuple(random.randint(20, 240) for _ in range(3))
    image = Image.new("RGBA", size, c1 + (255,))
    draw = ImageDraw.Draw(image)
    if random.random() < 0.5:
        unit = random.randint(12, 48)
        for y in range(0, height, unit):
            for x in range(0, width, unit):
                if ((x // unit) + (y // unit)) & 1:
                    draw.rectangle([x, y, x + unit, y + unit], fill=c2 + (255,))
    else:
        step = random.randint(10, 40)
        width_line = random.randint(3, 12)
        for x in range(-height, width + height, step):
            draw.line([(x, 0), (x + height, height)], fill=c2 + (255,), width=width_line)
    return image


def random_background(size: tuple[int, int]) -> Image.Image:
    mode = random.choice(["solid", "linear", "radial", "noise", "pattern"])
    if mode == "solid":
        return Image.new("RGBA", size, tuple(random.randint(14, 245) for _ in range(3)) + (255,))
    if mode == "linear":
        return gradient(size, tuple(random.randint(14, 245) for _ in range(3)),
                        tuple(random.randint(14, 245) for _ in range(3)),
                        random.uniform(0, math.pi * 2))
    if mode == "radial":
        return radial(size, tuple(random.randint(14, 245) for _ in range(3)),
                      tuple(random.randint(14, 245) for _ in range(3)))
    if mode == "noise":
        return noise_background(size)
    return pattern_background(size)


def add_tile(background: Image.Image) -> Image.Image:
    width, height = background.size
    tile_w = int(width * random.uniform(0.62, 0.94))
    tile_h = int(height * random.uniform(0.62, 0.94))
    x = (width - tile_w) // 2 + random.randint(-8, 8)
    y = (height - tile_h) // 2 + random.randint(-8, 8)
    radius = int(min(tile_w, tile_h) * random.uniform(0.12, 0.32))
    color = tuple(random.randint(14, 245) for _ in range(3)) + (255,)
    tile = Image.new("RGBA", (tile_w, tile_h), (0, 0, 0, 0))
    draw = ImageDraw.Draw(tile)
    draw.rounded_rectangle([0, 0, tile_w - 1, tile_h - 1], radius=radius, fill=color)
    shadow = Image.new("RGBA", background.size, (0, 0, 0, 0))
    shadow_draw = ImageDraw.Draw(shadow)
    shadow_draw.rounded_rectangle([x, y + 6, x + tile_w, y + tile_h + 6], radius=radius,
                                  fill=(0, 0, 0, 90))
    shadow = shadow.filter(ImageFilter.GaussianBlur(6))
    background.alpha_composite(shadow)
    background.alpha_composite(tile, (x, y))
    return background


def subject_cutout(image: Image.Image, mask: Image.Image) -> tuple[Image.Image, Image.Image]:
    subject = image.copy()
    subject.putalpha(mask)
    bbox = mask.getbbox()
    if bbox:
        left = max(0, bbox[0] - 2)
        top = max(0, bbox[1] - 2)
        right = min(mask.width, bbox[2] + 2)
        bottom = min(mask.height, bbox[3] + 2)
        subject = subject.crop((left, top, right, bottom))
        mask = mask.crop((left, top, right, bottom))
    return subject, mask


def compose_synthetic(image: Image.Image, mask: Image.Image, size: int) -> tuple[Image.Image, Image.Image]:
    subject, subject_mask = subject_cutout(image, mask)
    if subject.width == 0 or subject.height == 0:
        return image.resize((size, size), Image.Resampling.LANCZOS), mask.resize((size, size), Image.Resampling.LANCZOS)

    canvas = random_background((size, size))
    if random.random() < 0.75:
        canvas = add_tile(canvas)

    target = random.uniform(0.48, 0.88) * size
    scale = min(target / subject.width, target / subject.height)
    scale = max(scale, 0.15)
    new_w = max(1, int(subject.width * scale))
    new_h = max(1, int(subject.height * scale))
    subject = subject.resize((new_w, new_h), Image.Resampling.LANCZOS)
    subject_mask = subject_mask.resize((new_w, new_h), Image.Resampling.LANCZOS)

    margin_x = max(0, (size - new_w) // 2)
    margin_y = max(0, (size - new_h) // 2)
    x = random.randint(-margin_x // 3, margin_x // 3) + (size - new_w) // 2
    y = random.randint(-margin_y // 3, margin_y // 3) + (size - new_h) // 2
    x = max(0, min(size - new_w, x))
    y = max(0, min(size - new_h, y))

    shadow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    shadow_mask = Image.new("L", (size, size), 0)
    shadow_mask.paste(subject_mask, (x + random.randint(2, 12), y + random.randint(4, 16)))
    shadow_mask = shadow_mask.filter(ImageFilter.GaussianBlur(random.uniform(2, 9)))
    shadow.putalpha(shadow_mask.point(lambda value: int(value * random.uniform(0.25, 0.55))))
    canvas.alpha_composite(shadow)
    canvas.alpha_composite(subject, (x, y))

    label = Image.new("L", (size, size), 0)
    label.paste(subject_mask, (x, y))
    return canvas, label


def finish(image: Image.Image, mask: Image.Image, size: int, train: bool) -> tuple[Image.Image, Image.Image]:
    if image.size != (size, size):
        image = image.resize((size, size), Image.Resampling.LANCZOS)
    if mask.size != (size, size):
        mask = mask.resize((size, size), Image.Resampling.LANCZOS)
    if train:
        if random.random() < 0.5:
            image = ImageOps.mirror(image)
            mask = ImageOps.mirror(mask)
        if random.random() < 0.35:
            angle = random.uniform(-10, 10)
            image = image.rotate(angle, resample=Image.Resampling.BICUBIC, expand=False,
                                 fillcolor=(0, 0, 0, 0))
            mask = mask.rotate(angle, resample=Image.Resampling.BILINEAR, expand=False, fillcolor=0)
        if random.random() < 0.8:
            image = ImageEnhance.Brightness(image).enhance(random.uniform(0.75, 1.25))
            image = ImageEnhance.Contrast(image).enhance(random.uniform(0.75, 1.25))
            image = ImageEnhance.Color(image).enhance(random.uniform(0.7, 1.3))
        if random.random() < 0.3:
            image = image.filter(ImageFilter.GaussianBlur(random.uniform(0, 1.2)))
        if random.random() < 0.25:
            array = np.asarray(image.convert("RGB"), dtype=np.int16)
            array += np.random.randint(-10, 11, array.shape, dtype=np.int16)
            image = Image.fromarray(np.clip(array, 0, 255).astype(np.uint8), "RGB").convert("RGBA")
    mask = mask.point(lambda value: 255 if value >= 128 else 0, "L")
    return image, mask


def to_tensor(image: Image.Image, mask: Image.Image) -> tuple[torch.Tensor, torch.Tensor]:
    array = np.asarray(image.convert("RGB"), dtype=np.float32) / 255.0
    array = (array - IMAGENET_MEAN) / IMAGENET_STD
    image_tensor = torch.from_numpy(np.ascontiguousarray(array.transpose(2, 0, 1)))
    mask_array = (np.asarray(mask, dtype=np.float32) / 255.0)[None, ...]
    return image_tensor, torch.from_numpy(mask_array)


class IconDataset(Dataset):
    def __init__(self, records: list[dict], data_dir: Path, size: int, train: bool, repeat: int = 1):
        self.records = records
        self.data_dir = data_dir
        self.size = size
        self.train = train
        self.repeat = max(1, repeat)

    def __len__(self) -> int:
        return len(self.records) * (self.repeat if self.train else 1)

    def __getitem__(self, index: int):
        record = self.records[index % len(self.records)]
        image, mask = load_pair(record, self.data_dir)
        if self.train:
            if random.random() < 0.42:
                composed, label = image, mask
            else:
                composed, label = compose_synthetic(image, mask, self.size)
            composed, label = finish(composed, label, self.size, train=True)
        else:
            composed, label = finish(image, mask, self.size, train=False)
        return to_tensor(composed, label)


class DiceLoss(nn.Module):
    def forward(self, logits: torch.Tensor, targets: torch.Tensor) -> torch.Tensor:
        probs = torch.sigmoid(logits)
        numerator = 2 * (probs * targets).sum(dim=(1, 2, 3))
        denominator = probs.sum(dim=(1, 2, 3)) + targets.sum(dim=(1, 2, 3))
        return 1 - ((numerator + 1e-6) / (denominator + 1e-6)).mean()


def build_model(encoder: str, pretrained: bool):
    try:
        import segmentation_models_pytorch as smp
    except ImportError as error:
        raise SystemExit(
            "segmentation-models-pytorch is not installed. "
            "pip install -r requirements-train.txt"
        ) from error
    try:
        return smp.Unet(
            encoder_name=encoder,
            encoder_weights="imagenet" if pretrained else None,
            in_channels=3,
            classes=1,
        )
    except Exception as error:
        print(f"encoder {encoder} failed ({error}); falling back to resnet18")
        return smp.Unet(
            encoder_name="resnet18",
            encoder_weights="imagenet" if pretrained else None,
            in_channels=3,
            classes=1,
        )


def evaluate(model, loader, device) -> dict[str, float]:
    model.eval()
    intersection = union = dice_num = dice_den = 0.0
    with torch.no_grad():
        for images, masks in loader:
            images = images.to(device, non_blocking=True)
            masks = masks.to(device, non_blocking=True)
            logits = model(images)
            probs = torch.sigmoid(logits)
            pred = (probs >= 0.5).float()
            intersection += float((pred * masks).sum())
            union += float(((pred + masks) > 0).sum())
            dice_num += float(2 * (pred * masks).sum())
            dice_den += float(pred.sum() + masks.sum())
    iou = intersection / union if union else 0.0
    dice = dice_num / dice_den if dice_den else 0.0
    return {"iou": iou, "dice": dice}


def save_preview(model, records, data_dir, size, out_dir, device) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    model.eval()
    with torch.no_grad():
        for index, record in enumerate(records[:8]):
            image, mask = load_pair(record, data_dir)
            image, mask = finish(image, mask, size, train=False)
            tensor, _ = to_tensor(image, mask)
            logits = model(tensor.unsqueeze(0).to(device))
            pred = (torch.sigmoid(logits)[0, 0].cpu().numpy() * 255).astype(np.uint8)
            stem = record["id"]
            image.convert("RGB").save(out_dir / f"{index:02d}_{stem}_input.png")
            mask.save(out_dir / f"{index:02d}_{stem}_gt.png")
            Image.fromarray(pred, "L").save(out_dir / f"{index:02d}_{stem}_pred.png")


def export_onnx(model, size: int, out_dir: Path) -> None:
    model = model.eval().cpu()
    dummy = torch.randn(1, 3, size, size)
    onnx_path = out_dir / "model.onnx"
    torch.onnx.export(
        model,
        dummy,
        onnx_path,
        opset_version=17,
        input_names=["input"],
        output_names=["logits"],
        dynamic_axes={"input": {0: "batch"}, "logits": {0: "batch"}},
    )
    try:
        import onnx
        onnx.checker.check_model(str(onnx_path))
    except Exception as error:
        print(f"onnx check skipped: {error}")
    (out_dir / "preprocess.json").write_text(
        json.dumps(
            {
                "size": size,
                "colorspace": "RGB",
                "mean": IMAGENET_MEAN.tolist(),
                "std": IMAGENET_STD.tolist(),
                "input": "NCHW float32, normalised 0..1",
                "output": "logits (apply sigmoid, threshold 0.5)",
            },
            indent=2,
        ),
        encoding="utf-8",
    )
    print(f"wrote {onnx_path}")


def main() -> None:
    args = parse_args()
    random.seed(args.seed)
    np.random.seed(args.seed)
    torch.manual_seed(args.seed)
    if torch.cuda.is_available():
        torch.cuda.manual_seed_all(args.seed)
        torch.backends.cudnn.benchmark = True

    data_dir = Path(args.data)
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    manifest = load_manifest(data_dir)
    records = manifest["records"]
    if args.limit:
        records = records[: args.limit]
    train_records = [record for record in records if record.get("split") == "train"]
    val_records = [record for record in records if record.get("split") == "val"]
    if not train_records:
        raise SystemExit("no training records; export accepted masks first")
    if not val_records:
        random.shuffle(train_records)
        cut = max(1, len(train_records) // 7)
        val_records = train_records[:cut]
        train_records = train_records[cut:]
    print(f"records: train={len(train_records)} val={len(val_records)} size={args.size}")

    device = resolve_device(args.device)
    print(f"device: {device}")
    model = build_model(args.encoder, pretrained=not args.no_pretrained).to(device)
    parameters = sum(parameter.numel() for parameter in model.parameters())
    print(f"parameters: {parameters / 1e6:.2f}M")

    train_dataset = IconDataset(train_records, data_dir, args.size, train=True, repeat=args.repeat)
    val_dataset = IconDataset(val_records, data_dir, args.size, train=False)
    train_loader = DataLoader(
        train_dataset,
        batch_size=args.batch,
        shuffle=True,
        num_workers=args.workers,
        pin_memory=device.type == "cuda",
        drop_last=len(train_dataset) > args.batch,
    )
    val_loader = DataLoader(
        val_dataset,
        batch_size=args.batch,
        shuffle=False,
        num_workers=args.workers,
        pin_memory=device.type == "cuda",
    )

    dice_loss = DiceLoss()
    bce_loss = nn.BCEWithLogitsLoss()
    optimizer = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=1e-4)
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=max(1, args.epochs))
    use_amp = device.type == "cuda"
    scaler = torch.cuda.amp.GradScaler(enabled=use_amp)
    best_iou = -1.0

    for epoch in range(1, args.epochs + 1):
        model.train()
        running = 0.0
        for images, masks in train_loader:
            images = images.to(device, non_blocking=True)
            masks = masks.to(device, non_blocking=True)
            optimizer.zero_grad(set_to_none=True)
            with torch.cuda.amp.autocast(enabled=use_amp):
                logits = model(images)
                loss = bce_loss(logits, masks) + dice_loss(logits, masks)
            scaler.scale(loss).backward()
            scaler.step(optimizer)
            scaler.update()
            running += float(loss.detach())
        scheduler.step()
        metrics = evaluate(model, val_loader, device)
        average = running / max(1, len(train_loader))
        print(
            f"epoch {epoch:03d}/{args.epochs} loss={average:.4f} "
            f"val_iou={metrics['iou']:.4f} val_dice={metrics['dice']:.4f} "
            f"lr={scheduler.get_last_lr()[0]:.2e}"
        )
        if metrics["iou"] > best_iou:
            best_iou = metrics["iou"]
            torch.save(
                {
                    "state_dict": model.state_dict(),
                    "encoder": args.encoder,
                    "size": args.size,
                    "mean": IMAGENET_MEAN.tolist(),
                    "std": IMAGENET_STD.tolist(),
                    "iou": best_iou,
                },
                out_dir / "model.pt",
            )
            print(f"  saved best (iou={best_iou:.4f})")

    checkpoint = torch.load(out_dir / "model.pt", map_location=device, weights_only=True)
    model.load_state_dict(checkpoint["state_dict"])
    final = evaluate(model, val_loader, device)
    print(f"best val iou={final['iou']:.4f} dice={final['dice']:.4f}")
    if args.preview:
        save_preview(model, val_records, data_dir, args.size, out_dir / "preview", device)
    export_onnx(model, args.size, out_dir)
    (out_dir / "metrics.json").write_text(json.dumps(final, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
