package com.autousbcopier;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class UsbStorageDetector {
    private UsbStorageDetector() {
    }

    static List<String> getMountedRemovableVolumes(Context context) {
        List<String> paths = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            StorageManager manager = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
            if (manager == null) {
                return paths;
            }
            for (StorageVolume volume : manager.getStorageVolumes()) {
                if (!volume.isPrimary() && volume.isRemovable()
                        && Environment.MEDIA_MOUNTED.equals(volume.getState())) {
                    File volumeDirectory = getVolumeDirectory(context, volume);
                    paths.add(volumeDirectory == null
                            ? volume.getUuid() == null ? volume.getDescription(context) : volume.getUuid()
                            : volumeDirectory.getAbsolutePath());
                }
            }
            return paths;
        }

        File[] appDirectories = context.getExternalFilesDirs(null);
        if (appDirectories == null || appDirectories.length < 2) {
            return paths;
        }
        String primaryPath = Environment.getExternalStorageDirectory().getAbsolutePath();
        for (int i = 1; i < appDirectories.length; i++) {
            File appDirectory = appDirectories[i];
            if (appDirectory == null) {
                continue;
            }
            File volumeDirectory = getVolumeDirectory(appDirectory);
            if (volumeDirectory == null || primaryPath.equals(volumeDirectory.getAbsolutePath())) {
                continue;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                    && (!Environment.isExternalStorageRemovable(appDirectory)
                    || !Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState(appDirectory)))) {
                continue;
            }
            if (volumeDirectory.exists()) {
                paths.add(volumeDirectory.getAbsolutePath());
            }
        }
        return paths;
    }

    static boolean hasMountedStorage(Context context) {
        return !getMountedRemovableVolumes(context).isEmpty() && hasMassStorageDevice(context);
    }

    private static boolean hasMassStorageDevice(Context context) {
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (manager == null) {
            return false;
        }
        Map<String, UsbDevice> devices = manager.getDeviceList();
        for (UsbDevice device : devices.values()) {
            if (isMassStorageDevice(device)) {
                return true;
            }
        }
        return false;
    }

    static boolean isMassStorageDevice(UsbDevice device) {
        if (device.getDeviceClass() == UsbConstants.USB_CLASS_MASS_STORAGE) {
            return true;
        }
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface usbInterface = device.getInterface(i);
            if (usbInterface.getInterfaceClass() == UsbConstants.USB_CLASS_MASS_STORAGE) {
                return true;
            }
        }
        return false;
    }

    private static File getVolumeDirectory(Context context, StorageVolume volume) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return volume.getDirectory();
        }
        String uuid = volume.getUuid();
        File[] appDirectories = context.getExternalFilesDirs(null);
        if (appDirectories != null) {
            for (File appDirectory : appDirectories) {
                if (appDirectory == null) {
                    continue;
                }
                File root = getVolumeDirectory(appDirectory);
                if (root != null && uuid != null && uuid.equalsIgnoreCase(root.getName())) {
                    return root;
                }
            }
        }
        return null;
    }

    private static File getVolumeDirectory(File appDirectory) {
        String path = appDirectory.getAbsolutePath();
        String marker = File.separator + "Android" + File.separator + "data" + File.separator;
        int markerIndex = path.indexOf(marker);
        if (markerIndex < 0) {
            return null;
        }
        return new File(path.substring(0, markerIndex));
    }
}
