package org.osmdroid.tileprovider.modules;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.Map;

import org.apache.http.Header;
import org.apache.http.params.CoreProtocolPNames;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.conn.HttpHostConnectException;

import org.osmdroid.http.HttpClientFactory;
import org.osmdroid.tileprovider.MapTile;
import org.osmdroid.tileprovider.util.StreamUtils;
import org.osmdroid.tileprovider.tilesource.ITileSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*
 * This is a self contained tile downloader and writer to disk.
 *
 * Features that this has over the regular MapTileDownloader include:
 * - HTTP 404 filtering so that repeated requests that result in a 404 NotFound
 *   will be cached for one hour.
 * - ETag headers are written to disk in a .etag file so that
 *   condition-get can be implemented using an "If-None-Match" request header
 */
public class TileDownloaderDelegate {

    public static final String ETAG_MATCH_HEADER = "If-None-Match";

    public static final int ONE_HOUR_MS = 1000*60*60;

    private static final Logger logger = LoggerFactory.getLogger(TileDownloaderDelegate.class);
    private final INetworkAvailablityCheck networkAvailablityCheck;
    private final TileWriter tileWriter;

    // We use an LRU cache to track any URLs that give us a HTTP 404.
    private static final int HTTP404_CACHE_SIZE = 2000;
    Map<String, Long> HTTP404_CACHE = Collections.synchronizedMap(new LruCache<String, Long>(HTTP404_CACHE_SIZE));


    public TileDownloaderDelegate(INetworkAvailablityCheck pNetworkAvailablityCheck,
                                  TileWriter tw) {
        tileWriter = tw;
        networkAvailablityCheck = pNetworkAvailablityCheck;
    }

    /*
     * Check if the tile is current by running a conditional get on
     * the URL
     */
    public boolean isTileCurrent(ITileSource tileSource, MapTile tile) throws HttpHostConnectException {
        if (tileSource == null) {
            log("tileSource is null");
            return false;
        }

        if (networkIsUnavailable()) {
            return false;
        }

        final String tileURLString = tileSource.getTileURLString(tile);

        if (tileURLString == null || tileURLString.length() == 0) {
            log("No tile URL for ["+tile+"]");
            return false;
        }

        if (urlIs404Cached(tileURLString)) {
            // Note that this behaviour is different than
            // actually downloading a tile.  If the URL is 404 cached,
            // we just assume that the tile is 'current'
            return true;
        }

        try {
            String etag = tileWriter.readEtag(tileSource, tile);
            if (etag == null) {
                // No etags mean we want to download the file, no need
                // to go checking the etag status over the network.
                log("No etag found, forcing download ["+tileURLString+"]");
                return false;
            }

            final HttpClient client = HttpClientFactory.createHttpClient();
            final HttpGet httpGet = new HttpGet(tileURLString);
            httpGet.setHeader(CoreProtocolPNames.USER_AGENT, "osmdroid");
            httpGet.setHeader(ETAG_MATCH_HEADER, etag);
            final HttpResponse response = client.execute(httpGet);

            // Check to see if we got success
            final org.apache.http.StatusLine statusLine = response.getStatusLine();
            if (statusLine.getStatusCode() == 304) {
                log("Etag is already current ["+tileURLString+"]");
                return true;
            } else {
                log("Etag is not current - actual status line: ["+statusLine+"]");
            }
        } catch (HttpHostConnectException hostEx) {
            throw hostEx;
        } catch (IOException ioEx) {
            logger.error("IOException loading tile from network", ioEx);
            log("IOException loading tile from network:" + ioEx.toString());
        }

        log("Etag was not current ["+tileURLString+"]");
        return false;
    }
     * Write a tile from network to disk.
     */
    public boolean writeTileToDisk(ITileSource tileSource, MapTile tile) {
        if (tileSource == null) {
            log("tileSource is null");
            return false;
        }

        if (networkIsUnavailable()) {
            return false;
        }

        final String tileURLString = tileSource.getTileURLString(tile);

        if (tileURLString == null || tileURLString.length() == 0) {
            log("No tile URL for ["+tile+"]");
            return false;
        }

        if (urlIs404Cached(tileURLString)) {
            return false;
        }
        // Always try remove the tileURL from the cache
        HTTP404_CACHE.remove(tileURLString);

        try {
            final HttpClient client = HttpClientFactory.createHttpClient();
            final HttpResponse response = client.execute(new HttpGet(tileURLString));
            log("GET "+ tileURLString);

            // Check to see if we got success
            final org.apache.http.StatusLine statusLine = response.getStatusLine();
            log("Status: "+ statusLine);
            if (statusLine.getStatusCode() != 200) {
                if (statusLine.getStatusCode() == 404) {
                    HTTP404_CACHE.put(tileURLString, System.currentTimeMillis() + ONE_HOUR_MS);
                } else {
                    logger.warn("Unexpected response from tile server: [" + statusLine.toString() +"]");
                }
                return false;
            }

            final HttpEntity entity = response.getEntity();
            if (entity == null) {
                logger.warn("No content downloading MapTile: " + tile);
                return false;
            }

            final InputStream in = entity.getContent();
            final ByteArrayOutputStream dataStream = new ByteArrayOutputStream();
            final OutputStream out = new BufferedOutputStream(dataStream, StreamUtils.IO_BUFFER_SIZE);

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

            // write the data using the TileWriter
            tileWriter.saveFile(tileSource, tile, byteStream, etag);
            return true;

        } catch (IOException ioEx) {
            logger.error("IOException loading tile from network", ioEx);
        }

        return false;
    }

    /*
     * If a networkAvailabilityCheck object exists, check if the
     * network is *unavailable* and return true.
     *
     * In all other cases, assume the network is available.
     */
    private boolean networkIsUnavailable() {
        if (networkAvailablityCheck != null && !networkAvailablityCheck.getNetworkAvailable()) {
            log("networkIsUnavailable");
            return true;
        }
        return false;
    }

    /*
     * Check if this URL is already known to 404 on us.
     */
    private boolean urlIs404Cached(String url) {
        Long cacheTs = HTTP404_CACHE.get(url);
        if (cacheTs != null) {
            if (cacheTs.longValue() > System.currentTimeMillis()) {
                log("404 cached ["+url+"]");
                return true;
            }
        }
        return false;
    }

    private void log(String msg) {
        logger.info("osmdroid: " + msg);
    }
}

