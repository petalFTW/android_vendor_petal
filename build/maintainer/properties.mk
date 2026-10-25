# SPDX-License-Identifier: Apache-2.0

PETAL_VERSION := 1.0.1
override LINEAGE_BUILDTYPE := UNOFFICIAL
override LINEAGE_EXTRAVERSION :=
# stamped when the config gets read, close enough to the actual build
PETAL_BUILD_DATE := $(shell date +%Y-%m-%d)
PETAL_BUILD_TIME := $(shell date +%H:%M)
PETAL_BUILD_MAINTAINER := unknown
PETAL_BUILD_AUTHORIZED := false
PETAL_BUILD_AUTH_REASON := not_checked

ifeq ($(PETAL_AUTH_AUTHORIZED),true)
    PETAL_BUILD_AUTHORIZED := true
    PETAL_BUILD_MAINTAINER := $(PETAL_AUTH_MAINTAINER)
    ifeq ($(PETAL_AUTH_BUILD_TYPE),OFFICIAL)
        override LINEAGE_BUILDTYPE := OFFICIAL
    endif
endif
ifneq ($(PETAL_AUTH_REASON),)
    PETAL_BUILD_AUTH_REASON := $(PETAL_AUTH_REASON)
endif

PRODUCT_PRODUCT_PROPERTIES += \
    ro.petal.version=$(PETAL_VERSION) \
    ro.petal.build.type=$(LINEAGE_BUILDTYPE) \
    ro.petal.build.date=$(PETAL_BUILD_DATE) \
    ro.petal.build.time=$(PETAL_BUILD_TIME) \
    ro.petal.maintainer=$(PETAL_BUILD_MAINTAINER) \
    ro.petal.maintainer.authorized=$(PETAL_BUILD_AUTHORIZED) \
    ro.petal.auth.device=$(if $(PETAL_AUTH_DEVICE),$(PETAL_AUTH_DEVICE),unknown) \
    ro.petal.auth.reason=$(PETAL_BUILD_AUTH_REASON)
