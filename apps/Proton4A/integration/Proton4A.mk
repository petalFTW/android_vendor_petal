# Proton4A runtime host (org.petalos.proton4a).
#
# Provides the PetalHub "Import Windows EXE" target (ImportActivity) and the
# Wine/DXVK engine. It is a normal product app because system_app (PetalHub)
# cannot execute or map its own storage.
#
# The host APK is built out of tree (tools/build-runtime-app.sh) and staged into
# rom/prebuilt by tools/stage-rom.sh; include it only once staged so a fresh
# checkout without the 140 MB payload still builds.
ifneq ($(wildcard vendor/petal/apps/Proton4A/rom/prebuilt/Proton4A.apk),)
PRODUCT_PACKAGES += Proton4ARuntime
endif
