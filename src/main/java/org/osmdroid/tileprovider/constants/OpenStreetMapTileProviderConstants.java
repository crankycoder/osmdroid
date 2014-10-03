package org.osmdroid.tileprovider.constants;

import java.io.File;

import android.os.Environment;

/**
 *
 * This class contains constants used by the tile provider.
 *
 * @author Neil Boyd
 *
 */
public class OpenStreetMapTileProviderConstants 
        implements IOpenStreetMapTileProviderConstants 
{
    static OpenStreetMapTileProviderConstants instance;

    public static synchronized OpenStreetMapTileProviderConstants getInstance() {
        if (instance == null) {
            instance = new OpenStreetMapTileProviderConstants();
        }
        return instance;
    }

    private OpenStreetMapTileProviderConstants() {}

	/** Base path for osmdroid files. Zip files are in this folder. */
	public File OSMDROID_PATH() {
        return TileFilePath.getStorageDirectory();
    }

	/** Base path for tiles. */
	public File TILE_PATH_BASE() {
        return new File(OSMDROID_PATH(), "tiles");
    }
}
