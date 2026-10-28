package com.autousbcopier;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

final class MediaFileFilter {
    private static final Set<String> IMAGE_EXTENSIONS = immutableSet(
            "jpg", "jpeg", "jpe", "jfif", "png", "gif", "bmp", "tif", "tiff", "webp", "heic", "heif",
            "avif", "dng", "3fr", "ari", "arw", "bay", "cap", "cr2", "cr3", "crw",
            "dcs", "dcr", "drf", "eip", "erf", "fff", "gpr", "iiq", "k25", "kdc",
            "mef", "mos", "mrw", "nef", "nrw", "obm", "orf", "ori", "pef", "ptx",
            "pxn", "r3d", "raf", "raw", "rw2", "rwl", "rwz", "sr2", "srf", "srw",
            "x3f", "jxl");
    private static final Set<String> VIDEO_EXTENSIONS = immutableSet(
            "mp4", "m4v", "3gp", "3g2", "mkv", "mov", "avi", "wmv", "webm", "flv",
            "ts", "mts", "m2ts", "mpg", "mpeg", "mpe", "mpv", "m2v", "vob", "ogv",
            "ogg", "qt", "rm", "rmvb", "f4v", "divx", "asf", "mxf");

    private MediaFileFilter() {
    }

    static boolean matches(int mode, String name) {
        if (name == null) {
            return false;
        }
        if (mode == CopyService.MODE_EVERYTHING || mode == CopyService.MODE_CUSTOM) {
            return true;
        }
        String extension = extensionOf(name);
        if (mode == CopyService.MODE_PHOTOS) {
            return IMAGE_EXTENSIONS.contains(extension);
        }
        if (mode == CopyService.MODE_VIDEOS) {
            return VIDEO_EXTENSIONS.contains(extension);
        }
        if (mode == CopyService.MODE_PHOTOS_AND_VIDEOS) {
            return IMAGE_EXTENSIONS.contains(extension) || VIDEO_EXTENSIONS.contains(extension);
        }
        return false;
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static Set<String> immutableSet(String... values) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(values)));
    }
}
