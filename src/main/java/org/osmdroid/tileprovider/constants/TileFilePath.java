package org.osmdroid.tileprovider.constants;
import java.io.File;
import android.os.Environment;

public class TileFilePath {

  private static File directoryOverride;

  public synchronized static void setDirectoryOverride(File f) {
      directoryOverride = f;
  }


  public synchronized static File getStorageDirectory() {
    if (directoryOverride != null) {
      return directoryOverride;
    }
    return new File(Environment.getExternalStorageDirectory(), "osmdroid");
  }
}
