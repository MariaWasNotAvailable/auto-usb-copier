package com.autousbcopier;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CopyPolicyTest {
    @Test
    public void existingFileIsSkippedWhenOverwriteIsDisabled() {
        assertTrue(CopyPolicy.shouldSkipExisting(false, true, 12, 100, 4, 50));
    }

    @Test
    public void matchingSizeAndDateAreSkippedWhenConfigured() {
        assertTrue(CopyPolicy.shouldSkipExisting(true, true, 12, 100, 12, 100));
    }

    @Test
    public void differentSizeOrDateIsOverwrittenWhenConfigured() {
        assertFalse(CopyPolicy.shouldSkipExisting(true, true, 12, 100, 13, 100));
        assertFalse(CopyPolicy.shouldSkipExisting(true, true, 12, 100, 12, 101));
    }

    @Test
    public void identicalFilesAreOverwrittenWhenComparisonIsDisabled() {
        assertFalse(CopyPolicy.shouldSkipExisting(true, false, 12, 100, 12, 100));
    }

    @Test
    public void missingMetadataDoesNotTreatFilesAsIdentical() {
        assertFalse(CopyPolicy.shouldSkipExisting(true, true, -1, 0, -1, 0));
    }
}
