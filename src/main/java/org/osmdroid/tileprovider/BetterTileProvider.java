package org.osmdroid.tileprovider;

import org.osmdroid.tileprovider.modules.INetworkAvailablityCheck;
import org.osmdroid.tileprovider.modules.MapTileDownloader;
import org.osmdroid.tileprovider.modules.MapTileFileArchiveProvider;
import org.osmdroid.tileprovider.modules.MapTileFilesystemProvider;
import org.osmdroid.tileprovider.modules.NetworkAvailabliltyCheck;
import org.osmdroid.tileprovider.modules.TileWriter;
import org.osmdroid.tileprovider.tilesource.ITileSource;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver;
import org.osmdroid.tileprovider.modules.MapTileModuleProviderBase;

import android.content.Context;
import android.graphics.drawable.Drawable;

/**
 * This top-level tile provider implements a basic tile request chain which includes a
 * {@link MapTileFilesystemProvider} (a file-system cache), a {@link MapTileFileArchiveProvider}
 * (archive provider), and a {@link MapTileDownloader} (downloads map tiles via tile source).
 *
 * @author Marc Kurtz
 *
 */
public class BetterTileProvider extends BetterMapTileProviderArray implements IMapTileProviderCallback {

    // private static final Logger logger = LoggerFactory.getLogger(MapTileProviderBasic.class);

    /**
     * Creates a {@link MapTileProviderBasic}.
     */
    public BetterTileProvider(final Context pContext) {
        this(pContext, TileSourceFactory.DEFAULT_TILE_SOURCE);
    }

    /**
     * Creates a {@link MapTileProviderBasic}.
     */
    public BetterTileProvider(final Context pContext, final ITileSource pTileSource) {
        this(new SimpleRegisterReceiver(pContext), new NetworkAvailabliltyCheck(pContext),
                pTileSource);
    }

    /**
     * Creates a {@link MapTileProviderBasic}.
     */
    public BetterTileProvider(final IRegisterReceiver pRegisterReceiver,
                                final INetworkAvailablityCheck aNetworkAvailablityCheck, final ITileSource pTileSource) {
        super(pTileSource, pRegisterReceiver);

        final TileWriter tileWriter = new TileWriter();

        final MapTileFilesystemProvider fileSystemProvider = new MapTileFilesystemProvider(
                pRegisterReceiver, pTileSource);
        mTileProviderList.add(fileSystemProvider);

        final MapTileDownloader downloaderProvider = new MapTileDownloader(pTileSource, tileWriter,
                aNetworkAvailablityCheck);
        mTileProviderList.add(downloaderProvider);
    }

}
