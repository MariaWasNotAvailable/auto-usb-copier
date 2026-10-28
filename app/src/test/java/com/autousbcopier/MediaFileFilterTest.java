package com.autousbcopier;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MediaFileFilterTest {
    @Test
    public void photosModeAcceptsCommonAndRawPhotoExtensions() {
        assertTrue(MediaFileFilter.matches(CopyService.MODE_PHOTOS, "DCIM/Camera/IMG_0001.JPG"));
        assertTrue(MediaFileFilter.matches(CopyService.MODE_PHOTOS, "capture.NEF"));
        assertTrue(MediaFileFilter.matches(CopyService.MODE_PHOTOS, "capture.CR3"));
        assertTrue(MediaFileFilter.matches(CopyService.MODE_PHOTOS, "scan.tiff"));
    }

    @Test
    public void photosModeRejectsVideosAndUnknownExtensions() {
        assertFalse(MediaFileFilter.matches(CopyService.MODE_PHOTOS, "movie.mp4"));
        assertFalse(MediaFileFilter.matches(CopyService.MODE_PHOTOS, "document.pdf"));
        assertFalse(MediaFileFilter.matches(CopyService.MODE_PHOTOS, "extensionless"));
        assertFalse(MediaFileFilter.matches(CopyService.MODE_PHOTOS, null));
    }

    @Test
    public void videosModeMatchesVideoExtensionsCaseInsensitively() {
        assertTrue(MediaFileFilter.matches(CopyService.MODE_VIDEOS, "DCIM/Camera/clip.MOV"));
        assertTrue(MediaFileFilter.matches(CopyService.MODE_VIDEOS, "clip.m2ts"));
        assertFalse(MediaFileFilter.matches(CopyService.MODE_VIDEOS, "image.dng"));
    }

    @Test
    public void combinedModeAcceptsPhotosAndVideosButNotOtherFiles() {
        assertTrue(MediaFileFilter.matches(CopyService.MODE_PHOTOS_AND_VIDEOS, "image.heic"));
        assertTrue(MediaFileFilter.matches(CopyService.MODE_PHOTOS_AND_VIDEOS, "clip.webm"));
        assertFalse(MediaFileFilter.matches(CopyService.MODE_PHOTOS_AND_VIDEOS, "notes.txt"));
    }

    @Test
    public void everythingAndCustomModesDoNotFilterByExtension() {
        assertTrue(MediaFileFilter.matches(CopyService.MODE_EVERYTHING, "notes.txt"));
        assertTrue(MediaFileFilter.matches(CopyService.MODE_CUSTOM, "notes.txt"));
    }
}
