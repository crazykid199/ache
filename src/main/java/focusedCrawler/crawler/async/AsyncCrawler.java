package focusedCrawler.crawler.async;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import focusedCrawler.config.ConfigService;
import focusedCrawler.crawler.async.HttpDownloader.Callback;
import focusedCrawler.link.LinkStorage;
import focusedCrawler.link.frontier.LinkRelevance;
import focusedCrawler.util.DataNotFoundException;
import focusedCrawler.util.storage.Storage;
import focusedCrawler.util.storage.StorageConfig;
import focusedCrawler.util.storage.StorageException;
import focusedCrawler.util.storage.StorageFactoryException;
import focusedCrawler.util.storage.distribution.StorageCreator;

public class AsyncCrawler {
	
    private static final Logger logger = LoggerFactory.getLogger(AsyncCrawler.class);

    private final LinkStorage linkStorage;
    private final HttpDownloader downloader;
    private final Map<LinkRelevance.Type, HttpDownloader.Callback> handlers = new HashMap<>();
    
    private volatile boolean shouldStop = false;
    private Object running = new Object();
    
    public AsyncCrawler(Storage targetStorage, LinkStorage linkStorage, AsyncCrawlerConfig crawlerConfig) {
        this.linkStorage = linkStorage;
        this.downloader = new HttpDownloader(crawlerConfig.getDownloaderConfig());
        
        this.handlers.put(LinkRelevance.Type.FORWARD, new FetchedResultHandler(targetStorage));
        this.handlers.put(LinkRelevance.Type.SITEMAP, new SitemapXmlHandler(linkStorage));
        this.handlers.put(LinkRelevance.Type.ROBOTS, new RobotsTxtHandler(linkStorage, crawlerConfig.getDownloaderConfig().getUserAgentName()));
        
        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                shutdown();
            }
        });
    }
    
    public void run() {
        synchronized (running) {
            while(!this.shouldStop) {
                try {
                    LinkRelevance link = (LinkRelevance) linkStorage.select(null);
                    if(link != null) {
                        Callback handler = handlers.get(link.getType());
                        if(handler == null) {
                            logger.error("No registered handler for link type: "+link.getType());
                            continue;
                        }
                        downloader.dipatchDownload(link, handler);
                    }
                }
                catch (DataNotFoundException e) {
                    // There are no more links available in the frontier right now
                    if(downloader.hasPendingDownloads() || !e.ranOutOfLinks()) {
                        // If there are still pending downloads, new links 
                        // may be found in these pages, so we should wait some
                        // time until more links are available and try again
                        try {
                            logger.info("Waiting for links from pages being downloaded...");
                            Thread.sleep(1000);
                        } catch (InterruptedException ie) { }
                        continue;
                    }
                    // There are no more pending downloads and there are no
                    // more links available in the frontier, so stop crawler
                    logger.info("LinkStorage ran out of links, stopping crawler.");
                    this.shouldStop = true;
                    break;
                } catch (StorageException e) {
                    logger.error("Problem when selecting link from LinkStorage.", e);
                } catch (Exception e) {
                    logger.error("An unexpected error happened.", e);
                }
            }
        }
    }

    private void shutdown() {
        logger.info("Starting crawler shuttdown...");
        shouldStop = true;
        synchronized(running) {
            logger.info("Waiting downloader to finish...");
            downloader.await();
            downloader.close();
            linkStorage.close();
            logger.info("Done.");
        }
    }

    public static void run(ConfigService config) throws IOException, NumberFormatException {
        logger.info("Starting CrawlerManager...");
        try {
            StorageConfig linkStorageServerConfig = config.getLinkStorageConfig().getStorageServerConfig();
            Storage linkStorage = new StorageCreator(linkStorageServerConfig).produce();
            
            StorageConfig targetServerConfig = config.getTargetStorageConfig().getStorageServerConfig();
            Storage targetStorage = new StorageCreator(targetServerConfig).produce();
            
            AsyncCrawlerConfig crawlerConfig = config.getCrawlerConfig();
            AsyncCrawler crawler = new AsyncCrawler(targetStorage, (LinkStorage) linkStorage, crawlerConfig);
            crawler.run();

        } catch (StorageFactoryException ex) {
            logger.error("An error occurred while starting CrawlerManager. ", ex);
        }
    }

}
