#
# Copyright (C) 2026 The petalOS Project
#
# SPDX-License-Identifier: Apache-2.0
#

# petalOS on Cuttlefish, for screenshots without a real phone. Same system
# chrome as petal_macanc, minus anything that needs the hardware: no Hexagon
# assistant runtime, no Proton, no Android Auto, no App Center payload.
# PetalMusic is out too, it installs to /system/app and cf forbids that.
# vendor/petal/sepolicy is out as well, it needs the Oplus charger HAL. Only
# Tuner, the blocker and virtual camera use it, none of them shipped here.
# The Trusty VM build bind mounts $PWD/$OUT_DIR, which breaks with OUT_DIR=~/out_cf.
# KeyMint falls back to the plain cf one, fine for a screenshot image.
TRUSTY_SYSTEM_VM := disabled

$(call inherit-product, device/google/cuttlefish/vsoc_x86_64/phone/aosp_cf.mk)

include vendor/lineage/build/target/product/lineage_generic_target.mk

TARGET_DISABLE_EPPE := true
TARGET_NO_KERNEL_OVERRIDE := true

# PetalGM always parses, its AIDL lives here even though the emulator skips it
PRODUCT_SOONG_NAMESPACES += hardware/oplus

# The genrule cps this relative to out/soong/.temp/sbox/<hash>, and this product
# builds in OUT_DIR=~/out_cf, so the path has to climb out of /home/rab1d.
TARGET_BOOTANIMATION := ../../android/vendor/lineage/bootanimation/petal.zip

$(call inherit-product, vendor/petal/apps/PetalSettings/settings.mk)

PRODUCT_PACKAGES += \
    PetalHub \
    FontCommissionerOverlay \
    PetalAdvanced \
    PetalDepth
PRODUCT_COPY_FILES += vendor/petal/products/petal_cf_overlay_config.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/overlay/config/config.xml

# cf inherits the generic system rules, petal apps live on system like the phone
PRODUCT_ARTIFACT_PATH_REQUIREMENT_ALLOWED_LIST += system/app/PetalMusic/%

PRODUCT_NAME := petal_cf_x86_64
PRODUCT_MODEL := petalOS Cuttlefish phone
