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

    /*
     * This method is pulled up from BetterMapTileProviderArray
     */
	@Override
	public Drawable getMapTile(final MapTile pTile) {
		final Drawable tile = mTileCache.getMapTile(pTile);
		if (tile != null) {
			return tile;
		} 

        // @TODO: vng - rewrite this whole thing, we need to create a
        // MapTileRequest and pass it through the chain of providers.
        // We need to manage a synchronized set of requests which are
        // in a request state.
        //
        // Any call to getMapTile that misses the cache, or is a
        // duplicate request for a tile that is already enqued will
        // yield a null.

        boolean alreadyInProgress = false;
        synchronized (mWorking) {
            alreadyInProgress = mWorking.containsKey(pTile);
        }

        if (!alreadyInProgress) {
            final MapTileRequestState state;
            synchronized (mTileProviderList) {
                final MapTileModuleProviderBase[] providerArray =
                    new MapTileModuleProviderBase[mTileProviderList.size()];
                state = new MapTileRequestState(pTile,
                        mTileProviderList.toArray(providerArray), this);
            }

            synchronized (mWorking) {
                // Check again
                alreadyInProgress = mWorking.containsKey(pTile);
                if (alreadyInProgress) {
                    return null;
                }

                mWorking.put(pTile, state);
            }

            final MapTileModuleProviderBase provider = findNextAppropriateProvider(state);
            if (provider != null) {
                // @TODO: loadMapTileAsync spawns a thread to download
                // the tile, I think this is racy as multiple async calls seem
                // to be happening on the same URL
                provider.loadMapTileAsync(state);
            } else {
                mapTileRequestFailed(state);
            }
        }
        return tile;
	}

}
