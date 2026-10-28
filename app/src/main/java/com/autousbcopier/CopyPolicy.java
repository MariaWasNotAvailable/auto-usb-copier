package com.autousbcopier;

final class CopyPolicy {
    private CopyPolicy() {
    }

    static boolean shouldSkipExisting(boolean overwrite, boolean skipWhenIdentical,
                                      long sourceSize, long sourceModified,
                                      long destinationSize, long destinationModified) {
        return !overwrite || skipWhenIdentical
                && sourceSize >= 0
                && sourceModified > 0
                && sourceSize == destinationSize
                && sourceModified == destinationModified;
    }
}
