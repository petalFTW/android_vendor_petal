# Network location is an optional OS service, independent of sandboxed Play services.
PRODUCT_PACKAGES += \
    NetworkLocation \
    PetalNetworkLocationPermissionOverlay
PRODUCT_PACKAGE_OVERLAYS += vendor/petal/overlays/network-location
