package org.osmdroid.tileprovider;


import org.osmdroid.tileprovider.modules.INetworkAvailablityCheck;
import org.osmdroid.tileprovider.modules.MapTileDownloader;
import org.osmdroid.tileprovider.modules.MapTileFileArchiveProvider;

import org.osmdroid.tileprovider.modules.SmartFSProvider;

import org.osmdroid.tileprovider.modules.MapTileModuleProviderBase;
import org.osmdroid.tileprovider.modules.NetworkAvailabliltyCheck;
import org.osmdroid.tileprovider.modules.TileWriter;
import org.osmdroid.tileprovider.tilesource.ITileSource;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.osmdroid.tileprovider.modules.TileDownloaderDelegate;

import android.content.Context;
import android.graphics.drawable.Drawable;

/**
 * This top-level tile provider implements a basic tile request chain which includes a
 * {@link SmartFSProvider}.
 *
 * @author Marc Kurtz
 *
 */
public class BetterTileProvider extends BetterMapTileProviderArray implements IMapTileProviderCallback {

    private static final Logger logger = LoggerFactory.getLogger(BetterTileProvider.class);

    /**
     * Creates a {@link MapTileProviderBasic}.
     */
    public BetterTileProvider(final Context pContext) {
        // @TODO vng - this needs to be deleted as we are getting the
        // wrong tile source sometimes, and rely on some caller to
        // properly set the TileSource. This is a potential source of
        // race conditions.
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

        final SmartFSProvider smartProvider = new SmartFSProvider(pRegisterReceiver, pTileSource);

        // @TODO vng - something screwy is happening.  Passing in the
        // pTileSource into TileDownloaderDelegate here gives us the
        // wrong tilesource when we try to write to disk.  Something
        // must be clobbering the tilesource dynamically.
        //
        // The pTileSource being passed into SmartFSProvider must be
        // incorrect - which means that the single argument
        // constructor - BetterTileProvider(pContext) must be being
        // used.  This mostly affects the overlay architecture as the
        // MozStumbler's MapFragment does dynamic substitution of the
        // TileSource.
        //
        // Suggested refactoring - create an interface that inherits
        // from ITileSource that provides a callback hooks.
        // In particular, we want a TileSource to subscribe to zoom
        // level changes a generic Map<String, String> callback so
        // that we can pass the equivalent of intents back without
        // having to implement bits of android.
        //
        // After that, remove the option to construct the TileProvider
        // with just a context.  We never want to allow
        // TileSourceFactory.DEFAULT_TILE_SOURCE to be used.
        // 
        // Once that is done, we should be able to remove all the
        // hacks in the MozStumbler MapFragment which swap the
        // TileSource within the TileProvider.

        TileDownloaderDelegate tileDelegate = new TileDownloaderDelegate(aNetworkAvailablityCheck, tileWriter);
        smartProvider.configureDelegate(tileDelegate);

        mTileProviderList.add(smartProvider);
    }

}
