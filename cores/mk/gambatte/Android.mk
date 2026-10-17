# Overlay build file: gambatte-libretro ships no Android.mk. Run via cores/build-cores.sh.
LOCAL_PATH := $(call my-dir)
CORE_DIR := $(GAMBATTE_SRC)/libgambatte/src
include $(GAMBATTE_SRC)/Makefile.common
include $(CLEAR_VARS)
LOCAL_MODULE    := retro
LOCAL_SRC_FILES := $(SOURCES_C) $(SOURCES_CXX)
LOCAL_C_INCLUDES := $(subst -I,,$(INCFLAGS))
# -DVIDEO_RGB565 matches upstream Makefile.libretro's default (the 32-bit path rendered ~10x too dark here, and RGB565 halves
# video bandwidth, which matters on the Mali-450 TV).
LOCAL_CFLAGS    := -O3 -DHAVE_STDINT_H -DHAVE_INTTYPES_H -DINLINE=inline -D__LIBRETRO__ -DHAVE_ZLIB -DVIDEO_RGB565 -DCC_RESAMPLER_NO_HIGHPASS
LOCAL_CPPFLAGS  := -std=c++11
LOCAL_LDFLAGS   := -Wl,-version-script=$(CORE_DIR)/../libretro/link.T,-z,max-page-size=16384
include $(BUILD_SHARED_LIBRARY)
