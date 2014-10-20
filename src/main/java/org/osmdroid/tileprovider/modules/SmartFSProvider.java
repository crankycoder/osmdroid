package org.osmdroid.tileprovider.modules;

import java.io.File;
import java.util.concurrent.atomic.AtomicReference;
import java.io.IOException;

import org.osmdroid.tileprovider.modules.TileDownloaderDelegate;
import org.osmdroid.tileprovider.ExpirableBitmapDrawable;
import org.osmdroid.tileprovider.IRegisterReceiver;
import org.osmdroid.tileprovider.MapTile;
import org.osmdroid.tileprovider.MapTileRequestState;
import org.osmdroid.tileprovider.tilesource.BitmapTileSourceBase.LowMemoryException;
import org.osmdroid.tileprovider.tilesource.ITileSource;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import android.graphics.drawable.Drawable;

/**
 * This is a fork of the  MapTileFilesystemProvider which also spawns
 * off threads to download tiles asynchronously if a file on local
 * storage cannot be found.
 *
 * Concurrent tile access controls are required when spawning threads
 * to ensure that multiple requests for the same tile are not made to
 * the network.
 *
 * @author Victor Ng
 * @author Marc Kurtz
 * @author Nicolas Gramlich
 *
 */
public class SmartFSProvider extends MapTileFileStorageProviderBase {

    // ===========================================================
    // Constants
    // ===========================================================

    private static final Logger logger = LoggerFactory.getLogger(SmartFSProvider.class);
    public static final int ONE_HOUR_MS = 1000*60*60;

    // ===========================================================
    // Fields
    // ===========================================================

    private final AtomicReference<ITileSource> mTileSource = new AtomicReference<ITileSource>();
    private TileDownloaderDelegate delegate;

    // ===========================================================
    // Constructors
    // ===========================================================

    public SmartFSProvider(final IRegisterReceiver pRegisterReceiver) {
        this(pRegisterReceiver, TileSourceFactory.DEFAULT_TILE_SOURCE);
    }

    public SmartFSProvider(final IRegisterReceiver pRegisterReceiver,
                           final ITileSource pTileSource) {
        this(pRegisterReceiver, 
             pTileSource, 
             NUMBER_OF_TILE_FILESYSTEM_THREADS,
             TILE_FILESYSTEM_MAXIMUM_QUEUE_SIZE);
    }

    /**
     * Provides a file system based cache tile provider. Other providers can register and store data
     * in the cache.
     *
     * @param pRegisterReceiver
     */
    public SmartFSProvider(final IRegisterReceiver pRegisterReceiver,
                           final ITileSource pTileSource, 
                           int pThreadPoolSize,
                           int pPendingQueueSize) {
        super(pRegisterReceiver, pThreadPoolSize, pPendingQueueSize);
        setTileSource(pTileSource);
    }
    // ===========================================================
    // Getter & Setter
    // ===========================================================

    public void configureDelegate(TileDownloaderDelegate d) {
        delegate = d;
    }
    // ===========================================================
    // Methods from SuperClass/Interfaces
    // ===========================================================

    @Override
    public boolean getUsesDataConnection() {
        return true;
    }

    @Override
    protected String getName() {
        return "SmartFSProvider";
    }

    @Override
    protected String getThreadGroupName() {
        return "smartFsProvider";
    }

    @Override
    protected Runnable getTileLoader() {
        return new TileLoader();
    }

    @Override
    public int getMinimumZoomLevel() {
        ITileSource tileSource = mTileSource.get();
        return tileSource != null ? tileSource.getMinimumZoomLevel() : MINIMUM_ZOOMLEVEL;
    }

    @Override
    public int getMaximumZoomLevel() {
        ITileSource tileSource = mTileSource.get();
        return tileSource != null ? tileSource.getMaximumZoomLevel() : MAXIMUM_ZOOMLEVEL;
    }

    @Override
    public void setTileSource(final ITileSource pTileSource) {
        mTileSource.set(pTileSource);
    }

    // ===========================================================
    // Inner and Anonymous Classes
    // ===========================================================

    protected class TileLoader extends MapTileModuleProviderBase.TileLoader {

        @Override
        public Drawable loadTile(final MapTileRequestState pState) throws CantContinueException {

            ITileSource tileSource = mTileSource.get();
            if (tileSource == null) {
                return null;
            }

            final MapTile tile = pState.getMapTile();

            // if there's no sdcard then don't do anything
            if (!getSdCardAvailable()) {
                if (DEBUGMODE) {
                    logger.debug("No sdcard - do nothing for tile: " + tile);
                }
                return null;
            }

            // Check the tile source to see if its file is available and if so, then render the
            // drawable and return the tile
            File file = new File(TILE_PATH_BASE,
                    tileSource.getTileRelativeFilenameString(tile) + TILE_PATH_EXTENSION);

            final Drawable drawable; 
            if (file.exists()) {
                boolean tileIsCurrent = false;
                try {
                    tileIsCurrent = delegate.isTileCurrent(tileSource, tile);
                } catch (IOException ioEx) {
                    log("Error fetching etag status of file");
                    logger.error("Error checking etag status", ioEx);
                    return null;
                }

                if (tileIsCurrent) {
                    // Use the ondisk tile
                    try {
                        drawable = tileSource.getDrawable(file.getPath());
                        log("returning working tile");
                        return drawable;
                    } catch (final LowMemoryException e) {
                        // low memory so empty the queue
                        logger.warn("LowMemoryException downloading MapTile: " + tile + " : " + e);
                        throw new CantContinueException(e);
                    }
                }
            }

            // call the delegate and load a tile from the network
            if (delegate == null) {
                log("delegate is null");
                return null;
            }

            boolean writeOK = false;
            try {
                writeOK = delegate.downloadTile(tileSource, tile);
            } catch (IOException ioEx) {
                log("Error fetching tile from map server");
                logger.error("Error fetching tile from map server", ioEx);
                return null;
            }
            // @TODO: the writeOK flag isn't always correct - just
            // ignore it for now and test for file existence. The tile
            // will get updated anyway on the next redraw using
            // conditional get

            file = new File(TILE_PATH_BASE, tileSource.getTileRelativeFilenameString(tile) + TILE_PATH_EXTENSION);
            if (file.exists()) {
                try {
                    drawable = tileSource.getDrawable(file.getPath());
                    log("returning working tile!");
                    return drawable;
                } catch (final LowMemoryException e) {
                    // low memory so empty the queue
                    logger.warn("LowMemoryException downloading MapTile: " + tile + " : " + e);
                    throw new CantContinueException(e);
                }
            }

            log("Error loading tile writeOK: ["+writeOK+"] File Status: ["+file.exists()+"]");
            // If we get here then there is no file in the file cache
            return null;
        }
    }

    private void log(String msg) {
        logger.info("osmdroid: " + msg);
    }
}
