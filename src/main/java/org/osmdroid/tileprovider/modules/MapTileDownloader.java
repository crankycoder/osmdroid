package org.osmdroid.tileprovider.modules;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import org.osmdroid.http.HttpClientFactory;
import org.osmdroid.tileprovider.BitmapPool;
import org.osmdroid.tileprovider.MapTile;
import org.osmdroid.tileprovider.MapTileRequestState;
import org.osmdroid.tileprovider.ReusableBitmapDrawable;
import org.osmdroid.tileprovider.tilesource.BitmapTileSourceBase.LowMemoryException;
import org.osmdroid.tileprovider.tilesource.ITileSource;
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.tileprovider.util.StreamUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import android.graphics.drawable.Drawable;
import android.text.TextUtils;

/**
 * The {@link MapTileDownloader} loads tiles from an HTTP server. It saves downloaded tiles to an
 * IFilesystemCache if available.
 *
 * @author Marc Kurtz
 * @author Nicolas Gramlich
 * @author Manuel Stahl
 *
 */
public class MapTileDownloader extends MapTileModuleProviderBase {

    // ===========================================================
    // Constants
    // ===========================================================

    private static final Logger logger = LoggerFactory.getLogger(MapTileDownloader.class);
    public static final int ONE_HOUR_MS = 1000*60*60;

    // ===========================================================
    // Fields
    // ===========================================================

    // We use an LRU cache to track any URLs that give us a HTTP 404.
    private static final int HTTP404_CACHE_SIZE = 2000;
    Map<String, Long> HTTP404_CACHE = Collections.synchronizedMap(new LruCache<String, Long>(HTTP404_CACHE_SIZE));

    private final IFilesystemCache mFilesystemCache;

    private final AtomicReference<OnlineTileSourceBase> mTileSource = new AtomicReference<OnlineTileSourceBase>();

    private final INetworkAvailablityCheck mNetworkAvailablityCheck;

    // ===========================================================
    // Constructors
    // ===========================================================

    public MapTileDownloader(final ITileSource pTileSource) {
        this(pTileSource, null, null);
    }

    public MapTileDownloader(final ITileSource pTileSource, 
                             final IFilesystemCache pFilesystemCache) {
        this(pTileSource, pFilesystemCache, null);
    }

    public MapTileDownloader(final ITileSource pTileSource,
                             final IFilesystemCache pFilesystemCache,
                             final INetworkAvailablityCheck pNetworkAvailablityCheck) {
        this(pTileSource, pFilesystemCache, pNetworkAvailablityCheck,
                NUMBER_OF_TILE_DOWNLOAD_THREADS, TILE_DOWNLOAD_MAXIMUM_QUEUE_SIZE);
    }

    public MapTileDownloader(final ITileSource pTileSource,
            final IFilesystemCache pFilesystemCache,
            final INetworkAvailablityCheck pNetworkAvailablityCheck, int pThreadPoolSize,
            int pPendingQueueSize) {
        super(pThreadPoolSize, pPendingQueueSize);

        mFilesystemCache = pFilesystemCache;
        mNetworkAvailablityCheck = pNetworkAvailablityCheck;
        setTileSource(pTileSource);
    }

    // ===========================================================
    // Getter & Setter
    // ===========================================================

    public ITileSource getTileSource() {
        return mTileSource.get();
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
        return "Online Tile Download Provider";
    }

    @Override
    protected String getThreadGroupName() {
        return "downloader";
    }

    @Override
    protected Runnable getTileLoader() {
        return new TileLoader();
    }

    @Override
    public int getMinimumZoomLevel() {
        OnlineTileSourceBase tileSource = mTileSource.get();
        return (tileSource != null ? tileSource.getMinimumZoomLevel() : MINIMUM_ZOOMLEVEL);
    }

    @Override
    public int getMaximumZoomLevel() {
        OnlineTileSourceBase tileSource = mTileSource.get();
        return (tileSource != null ? tileSource.getMaximumZoomLevel() : MAXIMUM_ZOOMLEVEL);
    }

    @Override
    public void setTileSource(final ITileSource tileSource) {
        // We are only interested in OnlineTileSourceBase tile sources
        if (tileSource instanceof OnlineTileSourceBase) {
            mTileSource.set((OnlineTileSourceBase) tileSource);
        } else {
            // Otherwise shut down the tile downloader
            mTileSource.set(null);
        }
    }

    // ===========================================================
    // Inner and Anonymous Classes
    // ===========================================================

    long getThreadId() {
        return Thread.currentThread().getId();
    }

    protected class TileLoader extends MapTileModuleProviderBase.TileLoader {

        @Override
        public Drawable loadTile(final MapTileRequestState aState) throws CantContinueException {

            OnlineTileSourceBase tileSource = mTileSource.get();
            if (tileSource == null) {
                return null;
            }

            InputStream in = null;
            OutputStream out = null;
            final MapTile tile = aState.getMapTile();

            try {

                if (mNetworkAvailablityCheck != null
                        && !mNetworkAvailablityCheck.getNetworkAvailable()) {
                    if (DEBUGMODE) {
                        logger.debug("Skipping " + getName() + " due to NetworkAvailabliltyCheck.");
                    }
                    return null;
                }

                final String tileURLString = tileSource.getTileURLString(tile);

                if (DEBUGMODE) {
                    logger.debug("Downloading Maptile from url: " + tileURLString);
                }

                if (TextUtils.isEmpty(tileURLString)) {
                    return null;
                }

                // @TODO: vng this should use DefaultHttpClient for API
                // level < 19 and HttpUrlConnection otherwise.
                // Refactor from here ---->

                // 404 caching
                Long cacheTs = HTTP404_CACHE.get(tileURLString);
                if (cacheTs != null) {
                    // check the timestamp, if it's too far into the
                    // future, we use the cached null value and skip
                    // the entire HTTP call.
                    if (cacheTs.longValue() > System.currentTimeMillis()) {
                        return null;
                    }
                    HTTP404_CACHE.remove(tileURLString);
                }

                final HttpClient client = HttpClientFactory.createHttpClient();
                final HttpUriRequest head = new HttpGet(tileURLString);
                final HttpResponse response = client.execute(head);

                // Check to see if we got success
                final org.apache.http.StatusLine line = response.getStatusLine();
                if (line.getStatusCode() != 200) {
                    if (line.getStatusCode() == 404) {
                        HTTP404_CACHE.put(tileURLString, System.currentTimeMillis() + ONE_HOUR_MS);
                    }
                    logger.warn("Problem downloading MapTile: " + tile + " HTTP response: " + line);
                    return null;
                }

                final HttpEntity entity = response.getEntity();
                if (entity == null) {
                    logger.warn("No content downloading MapTile: " + tile);
                    return null;
                }
                in = entity.getContent();

                final ByteArrayOutputStream dataStream = new ByteArrayOutputStream();
                out = new BufferedOutputStream(dataStream, StreamUtils.IO_BUFFER_SIZE);

                // <------
                // @ TODO vng refactor to here so that a GET will
                // return an inputstream that we can write to disk.

                StreamUtils.copy(in, out);
                out.flush();
                final byte[] data = dataStream.toByteArray();
                final ByteArrayInputStream byteStream = new ByteArrayInputStream(data);
                Header etagHeader = response.getFirstHeader("etag");

                String etag;
                if (etagHeader == null) {
                    etag = null;
                } else {
                    etag = etagHeader.getValue();
                }

                // Save the data to the filesystem cache
                if (mFilesystemCache != null) {
                    mFilesystemCache.saveFile(tileSource, tile, byteStream, etag);
                    byteStream.reset();
                }
                final Drawable result = tileSource.getDrawable(byteStream);

                return result;
            } catch (final UnknownHostException e) {
                // no network connection so empty the queue
                logger.warn("UnknownHostException downloading MapTile: " + tile + " : " + e);
                throw new CantContinueException(e);
            } catch (final LowMemoryException e) {
                // low memory so empty the queue
                logger.warn("LowMemoryException downloading MapTile: " + tile + " : " + e);
                throw new CantContinueException(e);
            } catch (final FileNotFoundException e) {
                logger.warn("Tile not found: " + tile + " : " + e);
            } catch (final IOException e) {
                logger.warn("IOException downloading MapTile: " + tile + " : " + e);
            } catch (final Throwable e) {
                logger.error("Error downloading MapTile: " + tile, e);
            } finally {
                StreamUtils.closeStream(in);
                StreamUtils.closeStream(out);
            }

            return null;
        }

        @Override
        protected void tileLoaded(final MapTileRequestState pState, final Drawable pDrawable) {
            removeTileFromQueues(pState.getMapTile());
            // don't return the tile because we'll wait for the fs provider to ask for it
            // this prevent flickering when a load of delayed downloads complete for tiles
            // that we might not even be interested in any more
            pState.getCallback().mapTileRequestCompleted(pState, null);
            // We want to return the Bitmap to the BitmapPool if applicable
            if (pDrawable instanceof ReusableBitmapDrawable)
                BitmapPool.getInstance().returnDrawableToPool((ReusableBitmapDrawable) pDrawable);
        }

    }

    private class LruCache<A, B> extends LinkedHashMap<A, B> {
        private final int maxEntries;

        public LruCache(final int maxEntries) {
            super(maxEntries + 1, 1.0f, true);
            this.maxEntries = maxEntries;
        }

        /**
         * Returns <tt>true</tt> if this <code>LruCache</code> has more entries than the maximum specified when it was
         * created.
         *
         * <p>
         * This method <em>does not</em> modify the underlying <code>Map</code>; it relies on the implementation of
         * <code>LinkedHashMap</code> to do that, but that behavior is documented in the JavaDoc for
         * <code>LinkedHashMap</code>.
         * </p>
         *
         * @param eldest
         *            the <code>Entry</code> in question; this implementation doesn't care what it is, since the
         *            implementation is only dependent on the size of the cache
         * @return <tt>true</tt> if the oldest
         * @see java.util.LinkedHashMap#removeEldestEntry(Map.Entry)
         */
        @Override
        protected boolean removeEldestEntry(final Map.Entry<A, B> eldest) {
            return super.size() > maxEntries;
        }
    }

}
